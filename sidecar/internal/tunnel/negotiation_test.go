package tunnel

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"testing"
	"time"

	hyclient "github.com/apernet/hysteria/core/v2/client"
	"github.com/apernet/hysteria/core/v2/observe"
	"github.com/apernet/quic-go"

	"github.com/llll415/HyperConduit/sidecar/internal/config"
)

// bytesPerMbps is the exact conversion performed by config.Config: one
// megabit per second is 1_000_000 bits, i.e. 125_000 bytes per second.
const bytesPerMbps = 125_000

func brutalCC(rate uint64) observe.Congestion {
	return observe.Congestion{Type: "brutal", TxBytesPerSecond: rate}
}

func configuredCC(cc string) observe.Congestion {
	return observe.Congestion{Type: cc}
}

// negotiationCC is the bandwidth/controller half of a sidecar config that is
// relevant to the native client/server negotiation.
type negotiationCC struct {
	congestion string
	upMbps     uint64
	downMbps   uint64
}

func startNegotiationServer(t *testing.T, ctx context.Context, target string, id *testIdentity, token string, cc negotiationCC, disableBrutal bool) *Endpoint {
	t.Helper()
	e, err := StartServer(ctx, config.Config{
		Listen:        "127.0.0.1:0",
		Target:        target,
		CertFile:      id.certFile,
		KeyFile:       id.keyFile,
		Token:         token,
		Congestion:    cc.congestion,
		UpMbps:        cc.upMbps,
		DownMbps:      cc.downMbps,
		DisableBrutal: disableBrutal,
	})
	if err != nil {
		t.Fatalf("StartServer: %v", err)
	}
	t.Cleanup(func() { _ = e.Close() })
	return e
}

func startNegotiationClient(t *testing.T, ctx context.Context, serverAddr string, id *testIdentity, token string, cc negotiationCC) *Endpoint {
	t.Helper()
	e, err := StartClient(ctx, config.Config{
		Listen:     "127.0.0.1:0",
		Server:     serverAddr,
		CAFile:     id.certFile,
		Token:      token,
		Congestion: cc.congestion,
		UpMbps:     cc.upMbps,
		DownMbps:   cc.downMbps,
	})
	if err != nil {
		t.Fatalf("StartClient: %v", err)
	}
	t.Cleanup(func() { _ = e.Close() })
	return e
}

// waitForCondition is the only waiting primitive used by these tests. It uses
// a bounded retry ticker instead of sleeping, and always reports failures from
// the calling goroutine.
func waitForCondition(t *testing.T, ctx context.Context, timeout time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	ticker := time.NewTicker(time.Millisecond)
	defer ticker.Stop()
	for {
		if cond() {
			return
		}
		if !time.Now().Before(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		select {
		case <-ctx.Done():
			t.Fatalf("test context ended while waiting for %s: %v", what, ctx.Err())
		case <-ticker.C:
		}
	}
}

func waitConnection(t *testing.T, ctx context.Context, e *Endpoint) {
	t.Helper()
	waitForCondition(t, ctx, 10*time.Second, "an observed QUIC connection", func() bool {
		return len(e.Status().Connections) >= 1
	})
}

func onlySnapshot(t *testing.T, e *Endpoint, role string) observe.Snapshot {
	t.Helper()
	status := e.Status()
	if len(status.Connections) != 1 {
		t.Fatalf("%s endpoint has %d connections, want 1: %+v", role, len(status.Connections), status.Connections)
	}
	snap := status.Connections[0]
	if snap.Role != role {
		t.Fatalf("%s endpoint observed role %q", role, snap.Role)
	}
	return snap
}

func assertCC(t *testing.T, role string, got, want observe.Congestion) {
	t.Helper()
	if got != want {
		t.Fatalf("%s congestion = %+v, want %+v", role, got, want)
	}
}

func waitEstablishedBindings(t *testing.T, ctx context.Context, e *Endpoint, want int) []Binding {
	t.Helper()
	waitForCondition(t, ctx, 10*time.Second, fmt.Sprintf("%d established bindings", want), func() bool {
		bindings := e.Status().Bindings
		if len(bindings) != want {
			return false
		}
		for _, b := range bindings {
			if b.State != bindingStateEstablished || b.StreamID == nil {
				return false
			}
		}
		return true
	})
	return e.Status().Bindings
}

func assertLoopbackEphemeralString(t *testing.T, what, addr string) {
	t.Helper()
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		t.Fatalf("%s %q is not host:port: %v", what, addr, err)
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		t.Fatalf("%s %q is not loopback", what, addr)
	}
	p, err := strconv.Atoi(port)
	if err != nil || p == 0 {
		t.Fatalf("%s %q does not use an ephemeral port", what, addr)
	}
}

// portOf returns only the port of a host:port address. The client's UDP socket
// may be bound to an unspecified address ([::]) while the server observes the
// same packet on a concrete loopback address, so full-string comparison would
// be wrong even though both describe the same QUIC path.
func portOf(t *testing.T, addr string) string {
	t.Helper()
	_, port, err := net.SplitHostPort(addr)
	if err != nil {
		t.Fatalf("address %q is not host:port: %v", addr, err)
	}
	return port
}

func assertSharedConnection(t *testing.T, role string, bindings []Binding, wantConn uint64) {
	t.Helper()
	if len(bindings) != 2 {
		t.Fatalf("%s has %d bindings, want 2: %+v", role, len(bindings), bindings)
	}
	seen := make(map[int64]struct{}, len(bindings))
	for _, b := range bindings {
		if b.State != bindingStateEstablished || b.StreamID == nil {
			t.Fatalf("%s binding is not established: %+v", role, b)
		}
		if b.ConnectionID != wantConn {
			t.Fatalf("%s binding %d connection = %d, want %d", role, b.ID, b.ConnectionID, wantConn)
		}
		if _, dup := seen[*b.StreamID]; dup {
			t.Fatalf("%s bindings share stream ID %d", role, *b.StreamID)
		}
		seen[*b.StreamID] = struct{}{}
	}
}

func sameStreamIDs(a, b []Binding) bool {
	if len(a) != len(b) {
		return false
	}
	set := make(map[int64]int, len(a))
	for _, x := range a {
		if x.StreamID != nil {
			set[*x.StreamID]++
		}
	}
	for _, y := range b {
		if y.StreamID == nil {
			return false
		}
		set[*y.StreamID]--
	}
	for _, n := range set {
		if n != 0 {
			return false
		}
	}
	return true
}

// TestNegotiationBidirectionalCongestion exercises the native Hysteria
// bandwidth negotiation in both directions. Each side reports only the
// congestion controller of its own sender, so a client never claims to know
// the server's controller and vice versa.
func TestNegotiationBidirectionalCongestion(t *testing.T) {
	cases := []struct {
		name          string
		clientCC      string
		serverCC      string
		clientUp      uint64
		clientDown    uint64
		serverUp      uint64
		serverDown    uint64
		disableBrutal bool
		wantClient    observe.Congestion
		wantServer    observe.Congestion
	}{
		{
			name:       "client none server none",
			clientCC:   "bbr",
			serverCC:   "bbr",
			wantClient: configuredCC("bbr"),
			wantServer: configuredCC("bbr"),
		},
		{
			name:       "client upload only",
			clientCC:   "bbr",
			serverCC:   "bbr",
			clientUp:   4,
			wantClient: brutalCC(4 * bytesPerMbps),
			wantServer: configuredCC("bbr"),
		},
		{
			name:       "client download only",
			clientCC:   "bbr",
			serverCC:   "bbr",
			clientDown: 4,
			wantClient: configuredCC("bbr"),
			wantServer: brutalCC(4 * bytesPerMbps),
		},
		{
			name:       "client both server zero",
			clientCC:   "bbr",
			serverCC:   "bbr",
			clientUp:   4,
			clientDown: 2,
			wantClient: brutalCC(4 * bytesPerMbps),
			wantServer: brutalCC(2 * bytesPerMbps),
		},
		{
			name:       "server small caps both",
			clientCC:   "bbr",
			serverCC:   "bbr",
			clientUp:   4,
			clientDown: 2,
			serverUp:   1,
			serverDown: 1,
			wantClient: brutalCC(1 * bytesPerMbps),
			wantServer: brutalCC(1 * bytesPerMbps),
		},
		{
			name:       "server large leaves client limits",
			clientCC:   "bbr",
			serverCC:   "bbr",
			clientUp:   4,
			clientDown: 2,
			serverUp:   100,
			serverDown: 100,
			wantClient: brutalCC(4 * bytesPerMbps),
			wantServer: brutalCC(2 * bytesPerMbps),
		},
		{
			name:       "server only bounds do not trigger brutal",
			clientCC:   "bbr",
			serverCC:   "bbr",
			serverUp:   1,
			serverDown: 8,
			wantClient: configuredCC("bbr"),
			wantServer: configuredCC("bbr"),
		},
		{
			name:       "server only upload bound does not trigger brutal",
			clientCC:   "bbr",
			serverCC:   "bbr",
			serverUp:   1,
			wantClient: configuredCC("bbr"),
			wantServer: configuredCC("bbr"),
		},
		{
			name:       "server only download bound does not trigger brutal",
			clientCC:   "bbr",
			serverCC:   "bbr",
			serverDown: 1,
			wantClient: configuredCC("bbr"),
			wantServer: configuredCC("bbr"),
		},
		{
			name:          "ignore client bandwidth bbr reno",
			clientCC:      "bbr",
			serverCC:      "reno",
			clientUp:      4,
			clientDown:    2,
			serverUp:      8,
			serverDown:    8,
			disableBrutal: true,
			wantClient:    configuredCC("bbr"),
			wantServer:    configuredCC("reno"),
		},
		{
			name:          "ignore client bandwidth reno bbr",
			clientCC:      "reno",
			serverCC:      "bbr",
			clientUp:      4,
			clientDown:    2,
			serverUp:      8,
			serverDown:    8,
			disableBrutal: true,
			wantClient:    configuredCC("reno"),
			wantServer:    configuredCC("bbr"),
		},
		{
			name:       "configured types independent bbr reno",
			clientCC:   "bbr",
			serverCC:   "reno",
			wantClient: configuredCC("bbr"),
			wantServer: configuredCC("reno"),
		},
		{
			name:       "configured types independent reno bbr",
			clientCC:   "reno",
			serverCC:   "bbr",
			wantClient: configuredCC("reno"),
			wantServer: configuredCC("bbr"),
		},
		{
			name:       "server zero bound with client both",
			clientCC:   "bbr",
			serverCC:   "bbr",
			clientUp:   4,
			clientDown: 2,
			wantClient: brutalCC(4 * bytesPerMbps),
			wantServer: brutalCC(2 * bytesPerMbps),
		},
	}

	for _, tc := range cases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			ctx := newTestContext(t)
			id := newTestIdentity(t, "localhost")
			echo := newEchoServer(t)
			s := startNegotiationServer(t, ctx, echo.Addr(), id, "", negotiationCC{
				congestion: tc.serverCC,
				upMbps:     tc.serverUp,
				downMbps:   tc.serverDown,
			}, tc.disableBrutal)
			c := startNegotiationClient(t, ctx, s.Addr().String(), id, "", negotiationCC{
				congestion: tc.clientCC,
				upMbps:     tc.clientUp,
				downMbps:   tc.clientDown,
			})

			waitConnection(t, ctx, c)
			waitConnection(t, ctx, s)

			assertCC(t, "client", onlySnapshot(t, c, "client").Congestion, tc.wantClient)
			assertCC(t, "server", onlySnapshot(t, s, "server").Congestion, tc.wantServer)
		})
	}
}

// TestNegotiationTwoClientsIndependent verifies that each QUIC connection gets
// its own sender controller. Two clients with different Up/Down values on the
// same server must not share or divide a rate.
func TestNegotiationTwoClientsIndependent(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	const token = "shared-negotiation-token"

	s := startNegotiationServer(t, ctx, echo.Addr(), id, token, negotiationCC{congestion: "bbr"}, false)
	a := startNegotiationClient(t, ctx, s.Addr().String(), id, token, negotiationCC{congestion: "bbr", upMbps: 4, downMbps: 2})
	b := startNegotiationClient(t, ctx, s.Addr().String(), id, token, negotiationCC{congestion: "bbr", upMbps: 2, downMbps: 4})

	waitForCondition(t, ctx, 10*time.Second, "server observes two clients", func() bool {
		return len(s.Status().Connections) == 2
	})

	clientA := onlySnapshot(t, a, "client")
	clientB := onlySnapshot(t, b, "client")
	assertCC(t, "client A", clientA.Congestion, brutalCC(4*bytesPerMbps))
	assertCC(t, "client B", clientB.Congestion, brutalCC(2*bytesPerMbps))

	serverConns := s.Status().Connections
	if len(serverConns) != 2 {
		t.Fatalf("server has %d connections, want 2", len(serverConns))
	}
	if serverConns[0].ID == serverConns[1].ID {
		t.Fatalf("server connections share ID %d", serverConns[0].ID)
	}

	// Match each client's QUIC local port to the server's observed remote port.
	// This proves the rates belong to distinct server connections.
	byRemotePort := make(map[string]observe.Snapshot, len(serverConns))
	for _, snap := range serverConns {
		assertLoopbackEphemeralString(t, "server connection remote", snap.RemoteAddr)
		byRemotePort[portOf(t, snap.RemoteAddr)] = snap
	}
	serverForA, ok := byRemotePort[portOf(t, clientA.LocalAddr)]
	if !ok {
		t.Fatalf("no server connection for client A at %q: %+v", clientA.LocalAddr, serverConns)
	}
	serverForB, ok := byRemotePort[portOf(t, clientB.LocalAddr)]
	if !ok {
		t.Fatalf("no server connection for client B at %q: %+v", clientB.LocalAddr, serverConns)
	}
	if serverForA.ID == serverForB.ID {
		t.Fatalf("both clients matched server connection %d", serverForA.ID)
	}
	assertCC(t, "server for client A", serverForA.Congestion, brutalCC(2*bytesPerMbps))
	assertCC(t, "server for client B", serverForB.Congestion, brutalCC(4*bytesPerMbps))
}

// TestNegotiationStreamsShareConnection checks that multiple local streams
// from one client share a single observed QUIC connection, while each stream
// keeps its own StreamID and server-side backend binding.
func TestNegotiationStreamsShareConnection(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	s := startNegotiationServer(t, ctx, echo.Addr(), id, "", negotiationCC{congestion: "bbr"}, false)
	c := startNegotiationClient(t, ctx, s.Addr().String(), id, "", negotiationCC{congestion: "bbr"})

	first := dialLocal(t, c)
	assertEcho(t, first, []byte("first stream"))
	second := dialLocal(t, c)
	assertEcho(t, second, []byte("second stream"))

	clientBindings := waitEstablishedBindings(t, ctx, c, 2)
	serverBindings := waitEstablishedBindings(t, ctx, s, 2)
	clientSnap := onlySnapshot(t, c, "client")
	serverSnap := onlySnapshot(t, s, "server")

	assertSharedConnection(t, "client", clientBindings, clientSnap.ID)
	assertSharedConnection(t, "server", serverBindings, serverSnap.ID)

	// The observed QUIC path is the same from both ends; the client reports its
	// local UDP port and the server reports the same port as remote. The client
	// socket may be bound to [::], so compare the ports rather than the host.
	if portOf(t, clientSnap.LocalAddr) != portOf(t, serverSnap.RemoteAddr) {
		t.Fatalf("client local address %q and server remote address %q describe different paths", clientSnap.LocalAddr, serverSnap.RemoteAddr)
	}
	assertLoopbackEphemeralString(t, "server connection remote", serverSnap.RemoteAddr)
	if serverSnap.LocalAddr == "" {
		t.Fatal("server connection has no local address")
	}

	// PeerAddr is the QUIC peer from the trusted stream context. It must not
	// replace the local TCP socket addresses recorded in LocalAddr/RemoteAddr.
	for _, b := range clientBindings {
		if b.PeerAddr != clientSnap.RemoteAddr {
			t.Fatalf("client binding %d peer = %q, want observed peer %q", b.ID, b.PeerAddr, clientSnap.RemoteAddr)
		}
	}
	for _, b := range serverBindings {
		if b.PeerAddr != serverSnap.RemoteAddr {
			t.Fatalf("server binding %d peer = %q, want observed peer %q", b.ID, b.PeerAddr, serverSnap.RemoteAddr)
		}
		if b.PeerAddr == b.RemoteAddr {
			t.Fatalf("server binding %d conflated QUIC peer and backend remote: %+v", b.ID, b)
		}
	}

	// The two sides describe the same two QUIC streams.
	if !sameStreamIDs(clientBindings, serverBindings) {
		t.Fatalf("client stream IDs %+v != server stream IDs %+v", clientBindings, serverBindings)
	}

	// Server bindings are backend TCP connections. Their remote address is the
	// actual configured backend, never the client-supplied service identifier,
	// and their local address is the backend-side loopback ephemeral port.
	for _, b := range serverBindings {
		if b.RemoteAddr != echo.Addr() {
			t.Fatalf("server binding %d remote = %q, want backend %q", b.ID, b.RemoteAddr, echo.Addr())
		}
		if b.RemoteAddr == serviceAddress {
			t.Fatalf("server binding %d used the client-supplied service address", b.ID)
		}
		assertLoopbackEphemeralString(t, "server binding local", b.LocalAddr)
		if b.LocalAddr == b.RemoteAddr {
			t.Fatalf("server binding %d local and remote addresses are identical: %+v", b.ID, b)
		}
	}
	if serverBindings[0].LocalAddr == serverBindings[1].LocalAddr {
		t.Fatalf("server bindings share backend local address %q", serverBindings[0].LocalAddr)
	}
}

// TestNegotiationStreamCloseAndStatusLifecycle verifies that closed streams
// disappear from Status instead of accumulating as history, and that closing
// the endpoints leaves both active views empty.
func TestNegotiationStreamCloseAndStatusLifecycle(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	s := startNegotiationServer(t, ctx, echo.Addr(), id, "", negotiationCC{congestion: "bbr"}, false)
	c := startNegotiationClient(t, ctx, s.Addr().String(), id, "", negotiationCC{congestion: "bbr"})

	conn := dialLocal(t, c)
	assertEcho(t, conn, []byte("stream lifecycle"))
	waitEstablishedBindings(t, ctx, c, 1)
	waitEstablishedBindings(t, ctx, s, 1)

	if err := conn.Close(); err != nil {
		t.Fatalf("close local stream: %v", err)
	}
	waitForCondition(t, ctx, 10*time.Second, "client binding removed after stream close", func() bool {
		return len(c.Status().Bindings) == 0
	})
	waitForCondition(t, ctx, 10*time.Second, "server binding removed after stream close", func() bool {
		return len(s.Status().Bindings) == 0
	})

	// Closing one stream must not remove the still-active QUIC connection.
	if got := len(c.Status().Connections); got != 1 {
		t.Fatalf("client retained %d connections, want 1", got)
	}
	if got := len(s.Status().Connections); got != 1 {
		t.Fatalf("server retained %d connections, want 1", got)
	}

	if err := c.Close(); err != nil {
		t.Fatalf("client Close: %v", err)
	}
	if err := c.Wait(); err != nil {
		t.Fatalf("client Wait: %v", err)
	}
	if status := c.Status(); len(status.Connections) != 0 || len(status.Bindings) != 0 {
		t.Fatalf("client status not empty after Close: %+v", status)
	} else if status.Connections == nil || status.Bindings == nil || status.Errors == nil {
		t.Fatalf("client status has nil containers after Close: %+v", status)
	}

	if err := s.Close(); err != nil {
		t.Fatalf("server Close: %v", err)
	}
	if err := s.Wait(); err != nil {
		t.Fatalf("server Wait: %v", err)
	}
	if status := s.Status(); len(status.Connections) != 0 || len(status.Bindings) != 0 {
		t.Fatalf("server status not empty after Close: %+v", status)
	} else if status.Connections == nil || status.Bindings == nil || status.Errors == nil {
		t.Fatalf("server status has nil containers after Close: %+v", status)
	}
}

// TestNegotiationStatusStatsUnderTraffic confirms that QUIC byte counters are
// directional and that reading Status concurrently with traffic is safe.
func TestNegotiationStatusStatsUnderTraffic(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	s := startNegotiationServer(t, ctx, echo.Addr(), id, "", negotiationCC{congestion: "bbr"}, false)
	c := startNegotiationClient(t, ctx, s.Addr().String(), id, "", negotiationCC{congestion: "bbr"})

	conn := dialLocal(t, c)

	stop := make(chan struct{})
	var readers sync.WaitGroup
	readers.Add(1)
	go func() {
		defer readers.Done()
		ticker := time.NewTicker(time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				_ = c.Status()
				_ = s.Status()
			}
		}
	}()

	const total = int64(128 << 10)
	writeErr := make(chan error, 1)
	go func() { writeErr <- writePattern(conn, total, 7) }()
	readErr := readPattern(conn, total, 7)
	werr := <-writeErr
	close(stop)
	readers.Wait()

	if readErr != nil {
		t.Fatalf("read traffic: %v", readErr)
	}
	if werr != nil {
		t.Fatalf("write traffic: %v", werr)
	}

	waitForCondition(t, ctx, 10*time.Second, "QUIC byte counters reflect echo traffic", func() bool {
		clientStatus := c.Status()
		serverStatus := s.Status()
		if len(clientStatus.Connections) != 1 || len(serverStatus.Connections) != 1 {
			return false
		}
		cc := clientStatus.Connections[0]
		sc := serverStatus.Connections[0]
		return cc.BytesSent >= uint64(total) &&
			cc.BytesReceived >= uint64(total) &&
			sc.BytesSent >= uint64(total) &&
			sc.BytesReceived >= uint64(total)
	})

	cc := onlySnapshot(t, c, "client")
	sc := onlySnapshot(t, s, "server")
	// Both directions carry the echoed payload on each endpoint.
	if cc.BytesSent == 0 || cc.BytesReceived == 0 || sc.BytesSent == 0 || sc.BytesReceived == 0 {
		t.Fatalf("zero QUIC counters after traffic: client=%+v server=%+v", cc, sc)
	}
}

// TestNegotiationStreamContextUsesCoreIdentity exercises the client
// StreamConnection API directly and verifies that the server binding identity
// comes from the trusted stream context, not from client-supplied request data.
func TestNegotiationStreamContextUsesCoreIdentity(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	s := startServer(t, ctx, echo.Addr(), id, "")
	addr := mustUDPAddr(t, s.Addr())

	hy, _, err := hyclient.NewClientContext(ctx, directClientConfig(addr, id.pool, ""))
	if err != nil {
		t.Fatalf("NewClientContext: %v", err)
	}
	t.Cleanup(func() { _ = hy.Close() })

	requestCtx, requestCancel := context.WithTimeout(ctx, connDeadline)
	conn, err := hy.TCPContext(requestCtx, serviceAddress)
	requestCancel()
	if err != nil {
		t.Fatalf("TCPContext: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })

	streamConn, ok := conn.(hyclient.StreamConnection)
	if !ok {
		t.Fatalf("connection type %T does not implement StreamConnection", conn)
	}
	info := streamConn.StreamContext()
	snap := hy.ConnectionStats()
	if info.ConnectionID != snap.ID {
		t.Fatalf("stream context connection ID = %d, want %d", info.ConnectionID, snap.ID)
	}
	if info.RemoteAddr != snap.RemoteAddr {
		t.Fatalf("stream context remote = %q, want observed remote %q", info.RemoteAddr, snap.RemoteAddr)
	}
	if info.RemoteAddr == serviceAddress {
		t.Fatalf("stream context remote used the client-supplied service address %q", serviceAddress)
	}

	waitForCondition(t, ctx, 10*time.Second, "server binding established", func() bool {
		bindings := s.Status().Bindings
		return len(bindings) == 1 && bindings[0].State == bindingStateEstablished && bindings[0].StreamID != nil
	})
	serverBinding := s.Status().Bindings[0]
	serverSnap := onlySnapshot(t, s, "server")
	if serverBinding.ConnectionID != serverSnap.ID {
		t.Fatalf("server binding connection = %d, want observed %d", serverBinding.ConnectionID, serverSnap.ID)
	}
	if serverBinding.PeerAddr != serverSnap.RemoteAddr {
		t.Fatalf("server binding peer = %q, want observed peer %q", serverBinding.PeerAddr, serverSnap.RemoteAddr)
	}
	if *serverBinding.StreamID != info.StreamID {
		t.Fatalf("server binding stream = %d, want client stream %d", *serverBinding.StreamID, info.StreamID)
	}
	// The binding's remote address is the actual backend dial target; the
	// client-supplied service identifier never becomes a network address.
	if serverBinding.RemoteAddr != echo.Addr() {
		t.Fatalf("server binding remote = %q, want backend %q", serverBinding.RemoteAddr, echo.Addr())
	}
	assertLoopbackEphemeralString(t, "server binding local", serverBinding.LocalAddr)
}

// TestNegotiationStatusErrorCounts covers the realistic failure categories
// that survive after their connection or stream has been removed.
func TestNegotiationStatusErrorCounts(t *testing.T) {
	t.Run("wrong token", func(t *testing.T) {
		ctx := newTestContext(t)
		id := newTestIdentity(t, "localhost")
		echo := newEchoServer(t)
		s := startNegotiationServer(t, ctx, echo.Addr(), id, "correct-token", negotiationCC{congestion: "bbr"}, false)

		c, err := StartClient(ctx, config.Config{
			Listen: "127.0.0.1:0",
			Server: s.Addr().String(),
			CAFile: id.certFile,
			Token:  "wrong-token",
		})
		if err == nil {
			_ = c.Close()
			t.Fatal("StartClient accepted a wrong token")
		}
		waitForCondition(t, ctx, 10*time.Second, "authentication failure counted", func() bool {
			return s.Status().Errors[CodeAuthentication] >= 1
		})

		// Error counters are cumulative: closing the endpoint removes the
		// connection observation but must not erase the count.
		count := s.Status().Errors[CodeAuthentication]
		if err := s.Close(); err != nil {
			t.Fatalf("server Close: %v", err)
		}
		if err := s.Wait(); err != nil {
			t.Fatalf("server Wait: %v", err)
		}
		status := s.Status()
		if status.Errors[CodeAuthentication] != count {
			t.Fatalf("authentication count after Close = %d, want %d", status.Errors[CodeAuthentication], count)
		}
		if len(status.Connections) != 0 {
			t.Fatalf("connections retained after Close: %+v", status.Connections)
		}
	})

	t.Run("backend unreachable", func(t *testing.T) {
		ctx := newTestContext(t)
		id := newTestIdentity(t, "localhost")
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatalf("listen closed backend: %v", err)
		}
		target := ln.Addr().String()
		_ = ln.Close()

		s := startNegotiationServer(t, ctx, target, id, "", negotiationCC{congestion: "bbr"}, false)
		c := startNegotiationClient(t, ctx, s.Addr().String(), id, "", negotiationCC{congestion: "bbr"})
		conn := dialLocal(t, c)
		if err := writeFull(conn, []byte("backend is unreachable")); err != nil {
			t.Fatalf("write to unreachable backend: %v", err)
		}
		if _, err := io.ReadFull(conn, make([]byte, 1)); err == nil {
			t.Fatal("unreachable backend returned data")
		}
		// A failed backend dial is reported as a target failure; timeouts and
		// cancellations keep their own codes.
		waitForCondition(t, ctx, 10*time.Second, "backend target failure counted", func() bool {
			return s.Status().Errors[CodeTarget] >= 1
		})
	})

	t.Run("target denied", func(t *testing.T) {
		ctx := newTestContext(t)
		id := newTestIdentity(t, "localhost")
		echo := newEchoServer(t)
		s := startNegotiationServer(t, ctx, echo.Addr(), id, "", negotiationCC{congestion: "bbr"}, false)
		addr := mustUDPAddr(t, s.Addr())

		hy, _, err := hyclient.NewClientContext(ctx, directClientConfig(addr, id.pool, ""))
		if err != nil {
			t.Fatalf("NewClientContext: %v", err)
		}
		t.Cleanup(func() { _ = hy.Close() })

		requestCtx, requestCancel := context.WithTimeout(ctx, connDeadline)
		conn, err := hy.TCPContext(requestCtx, "127.0.0.1:1")
		requestCancel()
		if err == nil {
			_ = conn.Close()
			t.Fatal("non-service target was accepted")
		}
		waitForCondition(t, ctx, 10*time.Second, "target denial counted", func() bool {
			return s.Status().Errors[CodeTarget] >= 1
		})
	})

	t.Run("admission limit", func(t *testing.T) {
		ctx := newTestContext(t)
		id := newTestIdentity(t, "localhost")
		echo := newEchoServer(t)
		s := startServerWith(t, ctx, echo.Addr(), id, "", 1)
		addr := mustUDPAddr(t, s.Addr())

		first, _, err := hyclient.NewClientContext(ctx, directClientConfig(addr, id.pool, ""))
		if err != nil {
			t.Fatalf("first NewClientContext: %v", err)
		}
		t.Cleanup(func() { _ = first.Close() })

		secondCtx, secondCancel := context.WithTimeout(ctx, 2*time.Second)
		second, _, err := hyclient.NewClientContext(secondCtx, directClientConfig(addr, id.pool, ""))
		secondCancel()
		if err == nil {
			_ = second.Close()
			t.Fatal("second client connected beyond MaxConnections")
		}
		waitForCondition(t, ctx, 10*time.Second, "admission limit counted", func() bool {
			return s.Status().Errors[CodeLimit] >= 1
		})
	})

	t.Run("backend limit", func(t *testing.T) {
		ctx := newTestContext(t)
		id := newTestIdentity(t, "localhost")
		echo := newEchoServer(t)
		s := startServerWith(t, ctx, echo.Addr(), id, "", 1)
		c := startNegotiationClient(t, ctx, s.Addr().String(), id, "", negotiationCC{congestion: "bbr"})

		first := dialLocal(t, c)
		assertEcho(t, first, []byte("first backend slot"))
		waitEstablishedBindings(t, ctx, s, 1)

		second := dialLocal(t, c)
		if _, err := io.ReadFull(second, make([]byte, 1)); err == nil {
			t.Fatal("second backend connection was admitted beyond the limit")
		}
		waitForCondition(t, ctx, 10*time.Second, "backend limit counted", func() bool {
			return s.Status().Errors[CodeLimit] >= 1
		})
	})

	t.Run("silent auth timeout", func(t *testing.T) {
		ctx := newTestContext(t)
		id := newTestIdentity(t, "localhost")
		echo := newEchoServer(t)
		s, err := StartServer(ctx, config.Config{
			Listen:           "127.0.0.1:0",
			Target:           echo.Addr(),
			CertFile:         id.certFile,
			KeyFile:          id.keyFile,
			RequestTimeoutMS: 100,
		})
		if err != nil {
			t.Fatalf("StartServer: %v", err)
		}
		t.Cleanup(func() { _ = s.Close() })

		qc, err := quic.DialAddr(ctx, s.Addr().String(), &tls.Config{
			ServerName: "localhost",
			RootCAs:    id.pool,
			NextProtos: []string{"h3"},
		}, &quic.Config{})
		if err != nil {
			t.Fatalf("DialAddr: %v", err)
		}
		t.Cleanup(func() { _ = qc.CloseWithError(0, "") })

		waitForCondition(t, ctx, 10*time.Second, "silent authentication timeout counted", func() bool {
			return s.Status().Errors[CodeTimeout] >= 1
		})
	})
}
