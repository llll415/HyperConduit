package tunnel

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/llll415/HyperConduit/sidecar/internal/config"
)

func TestContextOutboundCancelReleasesLimit(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	status := newStatusStore()
	set := newConnSet(1)
	defer set.close()
	o := &fixedOutbound{ctx: context.Background(), target: "127.0.0.1:25565", timeout: time.Second, conns: set, status: status}
	conn, err := o.TCPContext(ctx, serviceAddress)
	if conn != nil || !errors.Is(err, context.Canceled) {
		t.Fatalf("got connection %v, error %v", conn, err)
	}
	var classified *Error
	if !errors.As(err, &classified) || classified.Code != CodeCanceled {
		t.Fatalf("wrong cancellation classification: %v", err)
	}
	if len(set.limit) != 0 {
		t.Fatal("canceled dial retained a connection slot")
	}
	if got := status.snapshot(); len(got.Bindings) != 0 || got.Errors[CodeCanceled] != 1 {
		t.Fatalf("unexpected canceled dial status: %+v", got)
	}
}

func TestStreamLimitIsObservedWithoutClosingConnection(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	s, err := StartServer(ctx, config.Config{Target: echo.Addr(), CertFile: id.certFile, KeyFile: id.keyFile, MaxStreams: 8})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	c := startClient(t, ctx, s.Addr().String(), id.certFile, "", "")
	for range 8 {
		conn := dialLocal(t, c)
		assertEcho(t, conn, []byte("hold stream slot"))
	}
	blocked := dialLocal(t, c)
	if _, err := blocked.Read(make([]byte, 1)); err == nil {
		t.Fatal("stream limit did not close extra local connection")
	}
	waitForCondition(t, ctx, 2*time.Second, "stream limit counter", func() bool { return c.Status().Errors[CodeLimit] > 0 })
	if got := c.Status(); len(got.Connections) != 1 || got.Connections[0].State != "established" {
		t.Fatalf("stream rejection broke the connection: %+v", got)
	}
}
