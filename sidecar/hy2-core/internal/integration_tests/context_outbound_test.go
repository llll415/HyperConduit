package integration_tests

import (
	"context"
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/apernet/hysteria/core/v2/observe"
	"github.com/apernet/hysteria/core/v2/server"
)

// contextProbeResult is what the first TCPContext call reports after its
// context is canceled.
type contextProbeResult struct {
	meta        observe.StreamContext
	start       time.Time
	deadline    time.Time
	hasDeadline bool
	doneElapsed time.Duration
	err         error
}

// contextProbeOutbound implements server.ContextOutbound by embedding the
// regular testOutbound. Its first TCPContext call blocks until the dial context
// is canceled; later calls are forwarded to the real echo target.
type contextProbeOutbound struct {
	testOutbound

	calls  atomic.Int64
	ctxCh  chan context.Context
	doneCh chan contextProbeResult
}

func newContextProbeOutbound() *contextProbeOutbound {
	return &contextProbeOutbound{
		ctxCh:  make(chan context.Context, 1),
		doneCh: make(chan contextProbeResult, 1),
	}
}

// TCP is intentionally unusable. If the server ever falls back to the legacy
// Outbound interface instead of ContextOutbound, the panic makes that visible.
func (o *contextProbeOutbound) TCP(reqAddr string) (net.Conn, error) {
	panic("legacy Outbound.TCP must not be called for a ContextOutbound implementation")
}

func (o *contextProbeOutbound) TCPContext(ctx context.Context, reqAddr string) (net.Conn, error) {
	if o.calls.Add(1) != 1 {
		return o.testOutbound.TCP(reqAddr)
	}

	meta, _ := observe.StreamFromContext(ctx)
	deadline, hasDeadline := ctx.Deadline()
	start := time.Now()
	o.ctxCh <- ctx
	<-ctx.Done()
	o.doneCh <- contextProbeResult{
		meta:        meta,
		start:       start,
		deadline:    deadline,
		hasDeadline: hasDeadline,
		doneElapsed: time.Since(start),
		err:         ctx.Err(),
	}
	return nil, ctx.Err()
}

func (o *contextProbeOutbound) waitFirst(t *testing.T, timeout time.Duration) contextProbeResult {
	t.Helper()

	select {
	case ctx := <-o.ctxCh:
		require.NotNil(t, ctx, "server outbound received a nil dial context")
	case <-time.After(timeout):
		t.Fatal("server outbound did not receive a dial context in time")
	}

	select {
	case result := <-o.doneCh:
		return result
	case <-time.After(timeout):
		t.Fatal("server dial context was not canceled in time")
		return contextProbeResult{}
	}
}

type contextTestAuthenticator struct{}

func (contextTestAuthenticator) Authenticate(net.Addr, string, uint64) (bool, string) {
	return true, "context-outbound-test"
}

// startContextEchoServer starts the shared TCP echo helper on an ephemeral
// loopback port and returns its address.
func startContextEchoServer(t *testing.T) string {
	t.Helper()

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	echoServer := &tcpEchoServer{Listener: listener}
	t.Cleanup(func() { _ = echoServer.Close() })
	go func() { _ = echoServer.Serve() }()
	return listener.Addr().String()
}

// startContextOutboundServer starts a real Hysteria server with the probe
// outbound. It uses the package's fixed UDP test port, so this test must not
// run in parallel with the rest of the integration tests.
func startContextOutboundServer(t *testing.T, outbound server.Outbound, requestTimeout time.Duration) (server.Server, net.Addr) {
	t.Helper()

	udpConn, udpAddr, err := serverConn()
	require.NoError(t, err)
	s, err := server.NewServer(&server.Config{
		TLSConfig:      serverTLSConfig(),
		Conn:           udpConn,
		Outbound:       outbound,
		Authenticator:  contextTestAuthenticator{},
		RequestTimeout: requestTimeout,
	})
	require.NoError(t, err)
	t.Cleanup(func() { _ = s.Close() })
	go func() { _ = s.Serve() }()
	return s, udpAddr
}

func startContextOutboundClient(t *testing.T, serverAddr net.Addr) client.ContextClient {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	c, _, err := client.NewClientContext(ctx, &client.Config{
		ServerAddr: serverAddr,
		Auth:       "test",
		TLSConfig:  client.TLSConfig{InsecureSkipVerify: true},
	})
	require.NoError(t, err)
	t.Cleanup(func() { _ = c.Close() })
	return c
}

// requireContextEcho opens a fresh stream through the client and verifies that
// it still reaches the echo target, proving the underlying QUIC connection
// survived the earlier failed request.
func requireContextEcho(t *testing.T, c client.ContextClient, addr string) {
	t.Helper()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	conn, err := c.TCPContext(ctx, addr)
	require.NoError(t, err)
	defer conn.Close()

	payload := []byte("context outbound echo")
	require.NoError(t, conn.SetDeadline(time.Now().Add(2*time.Second)))
	_, err = conn.Write(payload)
	require.NoError(t, err)

	got := make([]byte, len(payload))
	_, err = io.ReadFull(conn, got)
	require.NoError(t, err)
	require.Equal(t, payload, got)
}

// TestContextOutboundClientCancelKeepsQUICConn cancels a client dial well
// before the server's request timeout and checks that only that stream is
// affected: the server sees the dial context canceled with the expected
// stream metadata, and the same QUIC connection can still carry a new stream.
func TestContextOutboundClientCancelKeepsQUICConn(t *testing.T) {
	outbound := newContextProbeOutbound()
	echoAddr := startContextEchoServer(t)
	_, serverAddr := startContextOutboundServer(t, outbound, 200*time.Millisecond)
	c := startContextOutboundClient(t, serverAddr)

	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()

	start := time.Now()
	conn, err := c.TCPContext(ctx, echoAddr)
	elapsed := time.Since(start)

	require.Nil(t, conn)
	require.ErrorIs(t, err, context.DeadlineExceeded)
	require.Less(t, elapsed, time.Second, "client TCPContext should return when its context expires")

	probe := outbound.waitFirst(t, time.Second)
	require.ErrorIs(t, probe.err, context.Canceled, "stream cancellation should cancel the server dial before its own deadline")
	require.Less(t, probe.doneElapsed, time.Second, "server should observe cancellation promptly")
	require.NotZero(t, probe.meta.ConnectionID, "ConnectionID should be the server observation ID")
	require.GreaterOrEqual(t, probe.meta.StreamID, int64(0), "StreamID should be a valid QUIC stream ID")
	require.NotEmpty(t, probe.meta.RemoteAddr, "RemoteAddr should identify the client connection")

	// The failed request must not have closed the whole QUIC connection.
	requireContextEcho(t, c, echoAddr)
	require.Equal(t, int64(2), outbound.calls.Load(), "second request should use the outbound again")
}

// TestContextOutboundServerRequestTimeoutKeepsQUICConn uses a server request
// timeout much shorter than the client deadline. The outbound context must be
// bounded by the server timeout, the request must fail, and the QUIC connection
// must remain usable for a following request.
func TestContextOutboundServerRequestTimeoutKeepsQUICConn(t *testing.T) {
	const serverRequestTimeout = 100 * time.Millisecond

	outbound := newContextProbeOutbound()
	echoAddr := startContextEchoServer(t)
	_, serverAddr := startContextOutboundServer(t, outbound, serverRequestTimeout)
	c := startContextOutboundClient(t, serverAddr)

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()

	conn, err := c.TCPContext(ctx, echoAddr)
	require.Nil(t, conn)
	require.Error(t, err)

	probe := outbound.waitFirst(t, time.Second)
	require.ErrorIs(t, probe.err, context.DeadlineExceeded,
		"server request timeout should cancel the dial context")
	require.True(t, probe.hasDeadline, "server dial context should carry a deadline")
	remaining := probe.deadline.Sub(probe.start)
	require.LessOrEqual(t, remaining, serverRequestTimeout+50*time.Millisecond,
		"server request timeout, not the longer client deadline, should bound the dial context")
	require.Less(t, remaining, time.Second,
		"server dial context must not inherit the client's longer deadline")

	// The timed-out request must not have closed the whole QUIC connection.
	requireContextEcho(t, c, echoAddr)
	require.Equal(t, int64(2), outbound.calls.Load(), "second request should use the outbound again")
}
