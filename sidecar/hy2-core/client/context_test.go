package client

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"io"
	"math/big"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/apernet/hysteria/core/v2/internal/protocol"
	"github.com/apernet/quic-go"
)

const testALPN = "hysteria-context-test"

// trackingPacketConn records whether the underlying packet conn was closed.
type trackingPacketConn struct {
	net.PacketConn
	closed *atomic.Bool
}

func (c *trackingPacketConn) Close() error {
	c.closed.Store(true)
	return c.PacketConn.Close()
}

type trackingConnFactory struct {
	conn     net.PacketConn
	closed   atomic.Bool
	newCalls atomic.Int64
}

func (f *trackingConnFactory) New(net.Addr) (net.PacketConn, error) {
	f.newCalls.Add(1)
	return &trackingPacketConn{PacketConn: f.conn, closed: &f.closed}, nil
}

// newSilentEndpoint creates a local UDP endpoint that receives and discards
// packets without ever replying, simulating an unreachable/silent server.
func newSilentEndpoint(t *testing.T) (net.Addr, *trackingConnFactory) {
	t.Helper()
	server, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	require.NoError(t, err)
	t.Cleanup(func() { _ = server.Close() })
	go func() {
		buf := make([]byte, 1500)
		for {
			if _, _, err := server.ReadFromUDP(buf); err != nil {
				return
			}
		}
	}()

	clientConn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	require.NoError(t, err)
	t.Cleanup(func() { _ = clientConn.Close() })
	return server.LocalAddr(), &trackingConnFactory{conn: clientConn}
}

func newContextTestConfig(addr net.Addr, factory ConnFactory) *Config {
	return &Config{
		ConnFactory: factory,
		ServerAddr:  addr,
		Auth:        "test",
		TLSConfig: TLSConfig{
			ServerName:         "localhost",
			InsecureSkipVerify: true,
		},
	}
}

func TestNewClientContextDeadline(t *testing.T) {
	addr, factory := newSilentEndpoint(t)
	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()

	start := time.Now()
	c, info, err := NewClientContext(ctx, newContextTestConfig(addr, factory))
	elapsed := time.Since(start)

	require.Nil(t, c)
	require.Nil(t, info)
	require.Error(t, err)
	require.ErrorIs(t, err, context.DeadlineExceeded)
	require.Less(t, elapsed, 5*time.Second, "NewClientContext should return promptly when ctx expires")
	require.True(t, factory.closed.Load(), "packet conn should be closed after a failed connect")
}

func TestNewClientContextAlreadyCanceled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	factory := &trackingConnFactory{}
	c, info, err := NewClientContext(ctx, &Config{
		ConnFactory: factory,
		ServerAddr:  &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 1},
		Auth:        "test",
	})

	require.Nil(t, c)
	require.Nil(t, info)
	require.ErrorIs(t, err, context.Canceled)
	require.Zero(t, factory.newCalls.Load(), "no socket should be created when ctx is already canceled")
}

func TestNewClientContextCancel(t *testing.T) {
	addr, factory := newSilentEndpoint(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() {
		time.Sleep(100 * time.Millisecond)
		cancel()
	}()

	start := time.Now()
	c, info, err := NewClientContext(ctx, newContextTestConfig(addr, factory))
	elapsed := time.Since(start)

	require.Nil(t, c)
	require.Nil(t, info)
	require.Error(t, err)
	require.ErrorIs(t, err, context.Canceled)
	require.Less(t, elapsed, 5*time.Second, "NewClientContext should return promptly when ctx is canceled")
	require.True(t, factory.closed.Load(), "packet conn should be closed after a canceled connect")
}

func TestTCPContextCanceledBeforeOpen(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	c := &clientImpl{}
	conn, err := c.TCPContext(ctx, "example.com:443")

	require.Nil(t, conn)
	require.ErrorIs(t, err, context.Canceled)
}

func newTestTLSConfig(t *testing.T) *tls.Config {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	require.NoError(t, err)
	template := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "localhost"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IsCA:                  true,
		DNSNames:              []string{"localhost"},
		IPAddresses:           []net.IP{net.IPv4(127, 0, 0, 1)},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	require.NoError(t, err)
	return &tls.Config{
		Certificates: []tls.Certificate{{
			Certificate: [][]byte{der},
			PrivateKey:  key,
		}},
		NextProtos: []string{testALPN},
	}
}

// newTestQUICPair starts a local QUIC listener whose streams read the TCP
// request but never send a response, then dials it and returns the client side.
func newTestQUICPair(t *testing.T) *quic.Conn {
	t.Helper()
	listener, err := quic.ListenAddr("127.0.0.1:0", newTestTLSConfig(t), &quic.Config{})
	require.NoError(t, err)
	t.Cleanup(func() { _ = listener.Close() })

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	clientConn, err := quic.DialAddr(ctx, listener.Addr().String(), &tls.Config{
		InsecureSkipVerify: true,
		NextProtos:         []string{testALPN},
	}, &quic.Config{})
	require.NoError(t, err)
	t.Cleanup(func() { _ = clientConn.CloseWithError(0, "") })

	go func() {
		for {
			serverConn, err := listener.Accept(context.Background())
			if err != nil {
				return
			}
			go func() {
				for {
					stream, err := serverConn.AcceptStream(context.Background())
					if err != nil {
						return
					}
					go func() {
						_, _ = protocol.ReadTCPRequest(stream)
						// Keep the stream open without replying so the client
						// blocks in ReadTCPResponse until its context expires.
						_, _ = io.Copy(io.Discard, stream)
						_ = stream.Close()
					}()
				}
			}()
		}
	}()

	return clientConn
}

func TestTCPContextCancelKeepsQUICConn(t *testing.T) {
	quicConn := newTestQUICPair(t)
	c := &clientImpl{
		config: &Config{},
		conn:   quicConn,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()

	start := time.Now()
	conn, err := c.TCPContext(ctx, "example.com:443")
	elapsed := time.Since(start)

	require.Nil(t, conn)
	require.ErrorIs(t, err, context.DeadlineExceeded)
	require.Less(t, elapsed, 5*time.Second, "TCPContext should return promptly when ctx expires")
	require.NoError(t, quicConn.Context().Err(), "canceling a stream must not close the QUIC connection")

	// The connection must still be usable for new streams.
	stream, err := quicConn.OpenStream()
	require.NoError(t, err)
	require.NoError(t, stream.Close())
}

func TestDoneClosesWithQUICConn(t *testing.T) {
	quicConn := newTestQUICPair(t)
	c := &clientImpl{conn: quicConn}

	select {
	case <-c.Done():
		t.Fatal("Done should not be closed while the QUIC connection is alive")
	default:
	}

	require.NoError(t, quicConn.CloseWithError(0, ""))
	select {
	case <-c.Done():
	case <-time.After(5 * time.Second):
		t.Fatal("Done was not closed after the QUIC connection was closed")
	}
}
