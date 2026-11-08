package tunnel

import (
	"context"
	"crypto/tls"
	"errors"
	"io"
	"net"
	"net/http"
	"testing"
	"time"

	"github.com/apernet/quic-go"
	"github.com/apernet/quic-go/http3"

	"github.com/llll415/HyperConduit/sidecar/internal/config"
)

func TestServerCloseStopsClientListener(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	s := startServer(t, ctx, echo.Addr(), id, "")
	c := startClient(t, ctx, s.Addr().String(), id.certFile, "", "")
	conn := dialLocal(t, c)
	assertEcho(t, conn, []byte("before shutdown"))
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	if err := waitEndpoint(t, c, 2*time.Second); err == nil {
		t.Fatal("unexpected transport shutdown was not reported")
	}
	if conn, err := net.DialTimeout("tcp", c.Addr().String(), time.Second); err == nil {
		conn.Close()
		t.Fatal("client listener survived remote shutdown")
	}
}

func TestRequestTimeoutClosesPartialHeader(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	s, err := StartServer(ctx, config.Config{Target: echo.Addr(), CertFile: id.certFile, KeyFile: id.keyFile, RequestTimeoutMS: 100})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	connected := make(chan *quic.Conn, 1)
	rt := &http3.Transport{
		TLSClientConfig: &tls.Config{ServerName: "localhost", RootCAs: id.pool},
		Dial: func(ctx context.Context, _ string, tc *tls.Config, qc *quic.Config) (*quic.Conn, error) {
			conn, err := quic.DialAddr(ctx, s.Addr().String(), tc, qc)
			if err == nil {
				connected <- conn
			}
			return conn, err
		},
	}
	defer rt.Close()
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, "https://hysteria/auth", nil)
	resp, err := rt.RoundTrip(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 233 {
		t.Fatalf("authentication status: %d", resp.StatusCode)
	}
	qc := <-connected
	stream, err := qc.OpenStream()
	if err != nil {
		t.Fatal(err)
	}
	defer stream.Close()
	_ = stream.SetDeadline(time.Now().Add(2 * time.Second))
	// Valid frame type 0x401, but no address length or payload follows.
	if _, err := stream.Write([]byte{0x44, 0x01}); err != nil {
		t.Fatal(err)
	}
	_, err = stream.Read(make([]byte, 1))
	var ne net.Error
	if err == nil || (errors.As(err, &ne) && ne.Timeout()) {
		t.Fatalf("partial request did not close before local deadline: %v", err)
	}
	if qc.Context().Err() != nil {
		t.Fatal("request timeout closed the whole QUIC connection")
	}
	if echo.Active() != 0 {
		t.Fatal("partial request reached backend")
	}
}

func TestBackendFailureDoesNotFallback(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	target := ln.Addr().String()
	ln.Close()
	s := startServer(t, ctx, target, id, "")
	c := startClient(t, ctx, s.Addr().String(), id.certFile, "", "")
	conn := dialLocal(t, c)
	_, _ = conn.Write([]byte("no fallback"))
	if _, err := io.ReadFull(conn, make([]byte, 1)); err == nil {
		t.Fatal("backend failure unexpectedly returned data")
	}
}

func TestCanceledServerDoesNotBind(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	id := newTestIdentity(t, "localhost")
	s, err := StartServer(ctx, config.Config{Target: "127.0.0.1:25565", CertFile: id.certFile, KeyFile: id.keyFile})
	if s != nil || !errors.Is(err, context.Canceled) {
		t.Fatalf("got endpoint %v, error %v", s, err)
	}
}

func TestNativeCongestionPaths(t *testing.T) {
	for _, tc := range []struct {
		name    string
		cc      string
		mbps    uint64
		disable bool
	}{
		{"bbr", "bbr", 0, false},
		{"reno", "reno", 0, false},
		{"brutal", "bbr", 10, false},
		{"server disables brutal", "reno", 10, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ctx := newTestContext(t)
			id := newTestIdentity(t, "localhost")
			echo := newEchoServer(t)
			s, err := StartServer(ctx, config.Config{Target: echo.Addr(), CertFile: id.certFile, KeyFile: id.keyFile, Congestion: tc.cc, UpMbps: tc.mbps, DownMbps: tc.mbps, DisableBrutal: tc.disable})
			if err != nil {
				t.Fatal(err)
			}
			defer s.Close()
			c, err := StartClient(ctx, config.Config{Server: s.Addr().String(), CAFile: id.certFile, Congestion: tc.cc, UpMbps: tc.mbps, DownMbps: tc.mbps})
			if err != nil {
				t.Fatal(err)
			}
			defer c.Close()
			conn := dialLocal(t, c)
			assertEcho(t, conn, []byte("native congestion path"))
		})
	}
}

func TestAuthenticationTimeout(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	s, err := StartServer(ctx, config.Config{Target: echo.Addr(), CertFile: id.certFile, KeyFile: id.keyFile, RequestTimeoutMS: 100})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	qc, err := quic.DialAddr(ctx, s.Addr().String(), &tls.Config{ServerName: "localhost", RootCAs: id.pool, NextProtos: []string{"h3"}}, &quic.Config{})
	if err != nil {
		t.Fatal(err)
	}
	defer qc.CloseWithError(0, "")
	select {
	case <-qc.Context().Done():
	case <-time.After(2 * time.Second):
		t.Fatal("idle unauthenticated connection was not closed")
	}
	if echo.Active() != 0 {
		t.Fatal("unauthenticated client reached backend")
	}
}
