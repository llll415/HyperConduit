package tunnel

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"sync"
	"testing"
	"time"

	hyclient "github.com/apernet/hysteria/core/v2/client"

	"github.com/llll415/HyperConduit/sidecar/internal/config"
)

const (
	testTimeout  = 30 * time.Second
	connDeadline = 10 * time.Second
	ioDeadline   = 15 * time.Second
)

// newTestContext bounds every test, including its network handshakes.
func newTestContext(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), testTimeout)
	t.Cleanup(cancel)
	return ctx
}

type testIdentity struct {
	certFile string
	keyFile  string
	pool     *x509.CertPool
}

// newTestIdentity creates a self-signed ECDSA certificate valid for localhost
// plus the loopback IP SANs used by the tests. The private key and certificate
// are written with 0600 permissions and the certificate is also used as CA.
func newTestIdentity(t *testing.T, commonName string) *testIdentity {
	t.Helper()

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate ECDSA key: %v", err)
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		t.Fatalf("generate serial number: %v", err)
	}
	template := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: commonName},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(24 * time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IsCA:                  true,
		DNSNames:              []string{"localhost"},
		IPAddresses:           []net.IP{net.IPv4(127, 0, 0, 1), net.IPv6loopback},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatalf("create certificate: %v", err)
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		t.Fatalf("marshal private key: %v", err)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})

	dir := t.TempDir()
	certFile := filepath.Join(dir, "cert.pem")
	keyFile := filepath.Join(dir, "key.pem")
	if err := os.WriteFile(certFile, certPEM, 0o600); err != nil {
		t.Fatalf("write certificate: %v", err)
	}
	if err := os.WriteFile(keyFile, keyPEM, 0o600); err != nil {
		t.Fatalf("write private key: %v", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(certPEM) {
		t.Fatal("generated certificate was not added to the root pool")
	}
	return &testIdentity{certFile: certFile, keyFile: keyFile, pool: pool}
}

type echoServer struct {
	ln     net.Listener
	mu     sync.Mutex
	conns  map[net.Conn]struct{}
	closed bool
	wg     sync.WaitGroup
}

func newEchoServer(t *testing.T) *echoServer {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen echo backend: %v", err)
	}
	s := &echoServer{ln: ln, conns: make(map[net.Conn]struct{})}
	s.wg.Add(1)
	go s.serve()
	t.Cleanup(func() { _ = s.Close() })
	return s
}

func (s *echoServer) Addr() string { return s.ln.Addr().String() }

func (s *echoServer) Active() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.conns)
}

func (s *echoServer) serve() {
	defer s.wg.Done()
	for {
		c, err := s.ln.Accept()
		if err != nil {
			return
		}
		if !s.track(c) {
			_ = c.Close()
			return
		}
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			defer s.untrack(c)
			defer c.Close()
			_ = c.SetDeadline(time.Now().Add(ioDeadline))
			buf := make([]byte, 32*1024)
			for {
				n, err := c.Read(buf)
				if n > 0 {
					if _, werr := c.Write(buf[:n]); werr != nil {
						return
					}
				}
				if err != nil {
					return
				}
			}
		}()
	}
}

func (s *echoServer) track(c net.Conn) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return false
	}
	s.conns[c] = struct{}{}
	return true
}

func (s *echoServer) untrack(c net.Conn) {
	s.mu.Lock()
	delete(s.conns, c)
	s.mu.Unlock()
}

func (s *echoServer) Close() error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil
	}
	s.closed = true
	err := s.ln.Close()
	for c := range s.conns {
		_ = c.Close()
	}
	s.mu.Unlock()
	s.wg.Wait()
	return err
}

func waitEchoDrained(t *testing.T, s *echoServer, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for s.Active() != 0 {
		if !time.Now().Before(deadline) {
			t.Fatalf("echo backend still has %d active connection(s)", s.Active())
		}
		runtime.Gosched()
	}
}

type silentUDP struct {
	conn *net.UDPConn
}

func newSilentUDP(t *testing.T) *silentUDP {
	t.Helper()
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("listen silent UDP endpoint: %v", err)
	}
	s := &silentUDP{conn: conn}
	go func() {
		buf := make([]byte, 64*1024)
		_ = conn.SetReadDeadline(time.Now().Add(ioDeadline))
		for {
			if _, _, err := conn.ReadFromUDP(buf); err != nil {
				return
			}
		}
	}()
	t.Cleanup(func() { _ = conn.Close() })
	return s
}

func (s *silentUDP) Addr() net.Addr { return s.conn.LocalAddr() }

func startServerWith(t *testing.T, ctx context.Context, target string, id *testIdentity, token string, maxConnections int) *Endpoint {
	t.Helper()
	cfg := config.Config{
		Listen:   "127.0.0.1:0",
		Target:   target,
		CertFile: id.certFile,
		KeyFile:  id.keyFile,
		Token:    token,
	}
	if maxConnections > 0 {
		cfg.MaxConnections = maxConnections
	}
	e, err := StartServer(ctx, cfg)
	if err != nil {
		t.Fatalf("StartServer: %v", err)
	}
	t.Cleanup(func() { _ = e.Close() })
	return e
}

func startServer(t *testing.T, ctx context.Context, target string, id *testIdentity, token string) *Endpoint {
	t.Helper()
	return startServerWith(t, ctx, target, id, token, 0)
}

func startClient(t *testing.T, ctx context.Context, serverAddr, caFile, token, serverName string) *Endpoint {
	t.Helper()
	e, err := StartClient(ctx, config.Config{
		Listen:     "127.0.0.1:0",
		Server:     serverAddr,
		CAFile:     caFile,
		Token:      token,
		ServerName: serverName,
	})
	if err != nil {
		t.Fatalf("StartClient: %v", err)
	}
	t.Cleanup(func() { _ = e.Close() })
	return e
}

func assertLoopbackRandomAddr(t *testing.T, a net.Addr) {
	t.Helper()
	if a == nil {
		t.Fatal("endpoint address is nil")
	}
	host, port, err := net.SplitHostPort(a.String())
	if err != nil {
		t.Fatalf("endpoint address %q is not host:port: %v", a, err)
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		t.Fatalf("endpoint address %q is not loopback", a)
	}
	p, err := strconv.Atoi(port)
	if err != nil || p == 0 {
		t.Fatalf("endpoint address %q does not use an ephemeral port", a)
	}
}

func dialLocal(t *testing.T, e *Endpoint) net.Conn {
	t.Helper()
	c, err := net.DialTimeout("tcp", e.Addr().String(), connDeadline)
	if err != nil {
		t.Fatalf("dial local listener: %v", err)
	}
	if err := c.SetDeadline(time.Now().Add(ioDeadline)); err != nil {
		_ = c.Close()
		t.Fatalf("set connection deadline: %v", err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func writeFull(w io.Writer, b []byte) error {
	for len(b) > 0 {
		n, err := w.Write(b)
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
		b = b[n:]
	}
	return nil
}

func assertEcho(t *testing.T, c net.Conn, payload []byte) {
	t.Helper()
	if err := writeFull(c, payload); err != nil {
		t.Fatalf("write echo payload: %v", err)
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(c, got); err != nil {
		t.Fatalf("read echo payload: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("echo mismatch: got %x, want %x", got, payload)
	}
}

func fillPattern(buf []byte, offset int64, seed byte) {
	for i := range buf {
		buf[i] = byte((offset+int64(i))*131 + int64(seed)*7 + 17)
	}
}

func writePattern(w io.Writer, total int64, seed byte) error {
	buf := make([]byte, 32*1024)
	var off int64
	for off < total {
		n := int64(len(buf))
		if remaining := total - off; remaining < n {
			n = remaining
		}
		fillPattern(buf[:n], off, seed)
		if err := writeFull(w, buf[:n]); err != nil {
			return err
		}
		off += n
	}
	return nil
}

func readPattern(r io.Reader, total int64, seed byte) error {
	buf := make([]byte, 32*1024)
	want := make([]byte, 32*1024)
	var off int64
	for off < total {
		n := int64(len(buf))
		if remaining := total - off; remaining < n {
			n = remaining
		}
		if _, err := io.ReadFull(r, buf[:n]); err != nil {
			return err
		}
		fillPattern(want[:n], off, seed)
		if !bytes.Equal(buf[:n], want[:n]) {
			return fmt.Errorf("payload mismatch at offset %d", off)
		}
		off += n
	}
	return nil
}

func appendVarInt(dst []byte, v int) []byte {
	u := uint32(v)
	for {
		b := byte(u & 0x7f)
		u >>= 7
		if u != 0 {
			dst = append(dst, b|0x80)
			continue
		}
		return append(dst, b)
	}
}

// mcHandshake builds a Minecraft-like handshake packet. The tunnel must copy it
// byte for byte without interpreting the application protocol.
func mcHandshake(addr string, port uint16, nextState int) []byte {
	var body []byte
	body = appendVarInt(body, 0x00)
	body = appendVarInt(body, 765)
	body = appendVarInt(body, len(addr))
	body = append(body, addr...)
	body = append(body, byte(port>>8), byte(port))
	body = appendVarInt(body, nextState)
	var packet []byte
	packet = appendVarInt(packet, len(body))
	return append(packet, body...)
}

func waitEndpoint(t *testing.T, e *Endpoint, timeout time.Duration) error {
	t.Helper()
	done := make(chan error, 1)
	go func() { done <- e.Wait() }()
	select {
	case err := <-done:
		return err
	case <-time.After(timeout):
		t.Fatalf("endpoint did not stop within %s", timeout)
		return nil
	}
}

func directClientConfig(addr net.Addr, roots *x509.CertPool, auth string) *hyclient.Config {
	return &hyclient.Config{
		ServerAddr: addr,
		Auth:       auth,
		TLSConfig: hyclient.TLSConfig{
			ServerName: "localhost",
			RootCAs:    roots,
		},
	}
}

func mustUDPAddr(t *testing.T, a net.Addr) *net.UDPAddr {
	t.Helper()
	u, err := net.ResolveUDPAddr("udp", a.String())
	if err != nil {
		t.Fatalf("resolve server address %q: %v", a, err)
	}
	return u
}

// reconnectDirectClient retries until the server releases its admission slot.
// It uses a deadline instead of sleeping between attempts.
func reconnectDirectClient(t *testing.T, ctx context.Context, addr net.Addr, roots *x509.CertPool, auth string) hyclient.ContextClient {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	var lastErr error
	for {
		if ctx.Err() != nil {
			t.Fatalf("test context ended while waiting for reconnection: %v", ctx.Err())
		}
		if !time.Now().Before(deadline) {
			t.Fatalf("server did not accept a new client within 5s: %v", lastErr)
		}
		attemptCtx, cancel := context.WithTimeout(ctx, 500*time.Millisecond)
		c, _, err := hyclient.NewClientContext(attemptCtx, directClientConfig(addr, roots, auth))
		cancel()
		if err == nil {
			return c
		}
		lastErr = err
		runtime.Gosched()
	}
}

func TestEndpointLifecycle(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	s := startServer(t, ctx, echo.Addr(), id, "")
	c := startClient(t, ctx, s.Addr().String(), id.certFile, "", "")

	assertLoopbackRandomAddr(t, s.Addr())
	assertLoopbackRandomAddr(t, c.Addr())

	select {
	case <-c.done:
		t.Fatal("client endpoint stopped before Close")
	default:
	}
	select {
	case <-s.done:
		t.Fatal("server endpoint stopped before Close")
	default:
	}

	start := time.Now()
	if err := c.Close(); err != nil {
		t.Fatalf("client Close: %v", err)
	}
	if elapsed := time.Since(start); elapsed > 5*time.Second {
		t.Fatalf("client Close took %s, want at most 5s", elapsed)
	}
	if err := c.Wait(); err != nil {
		t.Fatalf("client Wait after Close: %v", err)
	}
	if err := c.Close(); err != nil {
		t.Fatalf("second client Close: %v", err)
	}

	start = time.Now()
	if err := s.Close(); err != nil {
		t.Fatalf("server Close: %v", err)
	}
	if elapsed := time.Since(start); elapsed > 5*time.Second {
		t.Fatalf("server Close took %s, want at most 5s", elapsed)
	}
	if err := s.Wait(); err != nil {
		t.Fatalf("server Wait after Close: %v", err)
	}
}

func TestTokenAuthentication(t *testing.T) {
	tests := []struct {
		name        string
		serverToken string
		clientToken string
		wantErr     bool
	}{
		{name: "no token", serverToken: "", clientToken: ""},
		{name: "matching token", serverToken: "correct-horse", clientToken: "correct-horse"},
		{name: "wrong token", serverToken: "correct-horse", clientToken: "wrong-battery", wantErr: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			ctx := newTestContext(t)
			id := newTestIdentity(t, "localhost")
			echo := newEchoServer(t)
			s := startServer(t, ctx, echo.Addr(), id, tt.serverToken)

			c, err := StartClient(ctx, config.Config{
				Listen: "127.0.0.1:0",
				Server: s.Addr().String(),
				CAFile: id.certFile,
				Token:  tt.clientToken,
			})
			if tt.wantErr {
				if err == nil {
					_ = c.Close()
					t.Fatal("StartClient succeeded with a mismatched token")
				}
				return
			}
			if err != nil {
				t.Fatalf("StartClient: %v", err)
			}
			t.Cleanup(func() { _ = c.Close() })

			conn := dialLocal(t, c)
			assertEcho(t, conn, []byte("token authentication round trip"))
		})
	}
}

func TestLargeBidirectionalTransfer(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	s := startServer(t, ctx, echo.Addr(), id, "")
	c := startClient(t, ctx, s.Addr().String(), id.certFile, "", "")

	conn := dialLocal(t, c)
	const total = int64(6 << 20) // Larger than both 4 MiB stream windows.

	writeErr := make(chan error, 1)
	go func() { writeErr <- writePattern(conn, total, 0) }()

	if err := readPattern(conn, total, 0); err != nil {
		_ = conn.Close()
		t.Fatalf("read %d bytes: %v", total, err)
	}
	if err := <-writeErr; err != nil {
		t.Fatalf("write %d bytes: %v", total, err)
	}
}

func TestMultipleStreamsIsolation(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	s := startServer(t, ctx, echo.Addr(), id, "")
	c := startClient(t, ctx, s.Addr().String(), id.certFile, "", "")

	const (
		streams = 8
		size    = int64(128 << 10)
	)
	var wg sync.WaitGroup
	errCh := make(chan error, streams)
	for i := 0; i < streams; i++ {
		i := i
		wg.Add(1)
		go func() {
			defer wg.Done()
			conn, err := net.DialTimeout("tcp", c.Addr().String(), connDeadline)
			if err != nil {
				errCh <- fmt.Errorf("stream %d dial: %w", i, err)
				return
			}
			defer conn.Close()
			if err := conn.SetDeadline(time.Now().Add(ioDeadline)); err != nil {
				errCh <- fmt.Errorf("stream %d deadline: %w", i, err)
				return
			}
			seed := byte(i + 1)
			if err := writePattern(conn, size, seed); err != nil {
				errCh <- fmt.Errorf("stream %d write: %w", i, err)
				return
			}
			if err := readPattern(conn, size, seed); err != nil {
				errCh <- fmt.Errorf("stream %d read: %w", i, err)
				return
			}
		}()
	}
	wg.Wait()
	close(errCh)
	for err := range errCh {
		t.Error(err)
	}
}

func TestTLSValidationFailures(t *testing.T) {
	t.Run("untrusted CA", func(t *testing.T) {
		ctx := newTestContext(t)
		id := newTestIdentity(t, "localhost")
		untrusted := newTestIdentity(t, "untrusted")
		echo := newEchoServer(t)
		s := startServer(t, ctx, echo.Addr(), id, "")

		c, err := StartClient(ctx, config.Config{
			Listen: "127.0.0.1:0",
			Server: s.Addr().String(),
			CAFile: untrusted.certFile,
		})
		if err == nil {
			_ = c.Close()
			t.Fatal("StartClient trusted a certificate from a different CA")
		}
	})

	t.Run("wrong server name", func(t *testing.T) {
		ctx := newTestContext(t)
		id := newTestIdentity(t, "localhost")
		echo := newEchoServer(t)
		s := startServer(t, ctx, echo.Addr(), id, "")

		c, err := StartClient(ctx, config.Config{
			Listen:     "127.0.0.1:0",
			Server:     s.Addr().String(),
			CAFile:     id.certFile,
			ServerName: "wrong.example",
		})
		if err == nil {
			_ = c.Close()
			t.Fatal("StartClient accepted a certificate for a different server name")
		}
	})
}

func TestRawMinecraftHandshakePassthrough(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	s := startServer(t, ctx, echo.Addr(), id, "")
	c := startClient(t, ctx, s.Addr().String(), id.certFile, "", "")

	conn := dialLocal(t, c)
	payload := mcHandshake("localhost", 25565, 2)
	if err := writeFull(conn, payload); err != nil {
		t.Fatalf("write handshake: %v", err)
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatalf("read handshake echo: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("handshake was not copied transparently: got %x, want %x", got, payload)
	}
}

func TestDirectHysteriaClientPolicy(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	s := startServer(t, ctx, echo.Addr(), id, "")
	addr := mustUDPAddr(t, s.Addr())

	hy, info, err := hyclient.NewClientContext(ctx, directClientConfig(addr, id.pool, ""))
	if err != nil {
		t.Fatalf("NewClientContext: %v", err)
	}
	defer hy.Close()
	if info.UDPEnabled {
		t.Fatal("TCP-only server advertised application UDP support")
	}

	// The only accepted service identifier is minecraft:0.
	requestCtx, requestCancel := context.WithTimeout(ctx, connDeadline)
	conn, err := hy.TCPContext(requestCtx, "minecraft:0")
	requestCancel()
	if err != nil {
		t.Fatalf("TCPContext(minecraft:0): %v", err)
	}
	defer conn.Close()
	if err := conn.SetDeadline(time.Now().Add(ioDeadline)); err != nil {
		t.Fatalf("set direct connection deadline: %v", err)
	}
	assertEcho(t, conn, []byte("direct hysteria client"))

	// A different loopback target must be rejected before any dial happens.
	trap, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen trap: %v", err)
	}
	defer trap.Close()
	denyCtx, denyCancel := context.WithTimeout(ctx, connDeadline)
	denied, err := hy.TCPContext(denyCtx, trap.Addr().String())
	denyCancel()
	if err == nil {
		_ = denied.Close()
		t.Fatal("TCPContext allowed a target other than minecraft:0")
	}
	// Only start the accept probe after the rejected request has completed, so
	// any connection queued during the attempt is still observable.
	if err := trap.(*net.TCPListener).SetDeadline(time.Now().Add(500 * time.Millisecond)); err != nil {
		t.Fatalf("set trap deadline: %v", err)
	}
	if accepted, acceptErr := trap.Accept(); acceptErr == nil {
		_ = accepted.Close()
		t.Fatal("server dialed a target other than the configured backend")
	} else {
		var netErr net.Error
		if !errors.As(acceptErr, &netErr) || !netErr.Timeout() {
			t.Fatalf("trap accept returned %v, want a deadline timeout", acceptErr)
		}
	}

	// Even the real backend address is not directly addressable.
	denyCtx, denyCancel = context.WithTimeout(ctx, connDeadline)
	denied, err = hy.TCPContext(denyCtx, echo.Addr())
	denyCancel()
	if err == nil {
		_ = denied.Close()
		t.Fatal("TCPContext allowed the backend address instead of the service identifier")
	}

}

func TestServerMaxConnectionsGate(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	s := startServerWith(t, ctx, echo.Addr(), id, "", 1)
	addr := mustUDPAddr(t, s.Addr())

	first, _, err := hyclient.NewClientContext(ctx, directClientConfig(addr, id.pool, ""))
	if err != nil {
		t.Fatalf("first NewClientContext: %v", err)
	}
	defer first.Close()

	secondCtx, secondCancel := context.WithTimeout(ctx, 500*time.Millisecond)
	start := time.Now()
	second, _, err := hyclient.NewClientContext(secondCtx, directClientConfig(addr, id.pool, ""))
	secondCancel()
	elapsed := time.Since(start)
	if err == nil {
		_ = second.Close()
		t.Fatal("second client connected although MaxConnections is 1")
	}
	if elapsed > 2*time.Second {
		t.Fatalf("second client rejection took %s, want at most 2s", elapsed)
	}

	if err := first.Close(); err != nil {
		t.Fatalf("close first client: %v", err)
	}
	reconnected := reconnectDirectClient(t, ctx, addr, id.pool, "")
	defer reconnected.Close()
}

func TestClientConnectTimeoutAgainstSilentUDP(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	silent := newSilentUDP(t)

	start := time.Now()
	c, err := StartClient(ctx, config.Config{
		Listen:           "127.0.0.1:0",
		Server:           silent.Addr().String(),
		CAFile:           id.certFile,
		ConnectTimeoutMS: 100,
	})
	elapsed := time.Since(start)
	if err == nil {
		_ = c.Close()
		t.Fatal("StartClient connected to a silent UDP endpoint")
	}
	if elapsed > 2*time.Second {
		t.Fatalf("StartClient took %s against a silent endpoint, want at most 2s", elapsed)
	}
}

func TestCloseCleansActiveConnection(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	s := startServer(t, ctx, echo.Addr(), id, "")
	c := startClient(t, ctx, s.Addr().String(), id.certFile, "", "")

	conn := dialLocal(t, c)
	assertEcho(t, conn, []byte("connection stays active until Close"))

	start := time.Now()
	if err := c.Close(); err != nil {
		t.Fatalf("client Close with active connection: %v", err)
	}
	if elapsed := time.Since(start); elapsed > 5*time.Second {
		t.Fatalf("client Close took %s, want at most 5s", elapsed)
	}
	if err := conn.SetReadDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatalf("set local read deadline: %v", err)
	}
	var buf [1]byte
	if _, err := conn.Read(buf[:]); err == nil {
		t.Fatal("active local connection remained open after client Close")
	}

	start = time.Now()
	if err := s.Close(); err != nil {
		t.Fatalf("server Close: %v", err)
	}
	if elapsed := time.Since(start); elapsed > 5*time.Second {
		t.Fatalf("server Close took %s, want at most 5s", elapsed)
	}
	waitEchoDrained(t, echo, 2*time.Second)
}

func TestParentContextCancellation(t *testing.T) {
	base := newTestContext(t)
	ctx, cancel := context.WithCancel(base)
	defer cancel()

	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	s := startServer(t, ctx, echo.Addr(), id, "")
	c := startClient(t, ctx, s.Addr().String(), id.certFile, "", "")

	conn := dialLocal(t, c)
	assertEcho(t, conn, []byte("connection is active before cancellation"))

	cancel()
	if err := waitEndpoint(t, c, 2*time.Second); err != nil {
		t.Fatalf("client Wait after parent cancellation: %v", err)
	}
	if err := waitEndpoint(t, s, 2*time.Second); err != nil {
		t.Fatalf("server Wait after parent cancellation: %v", err)
	}
	if err := conn.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatalf("set local read deadline: %v", err)
	}
	var buf [1]byte
	if _, err := conn.Read(buf[:]); err == nil {
		t.Fatal("active local connection remained open after parent cancellation")
	}
	waitEchoDrained(t, echo, 2*time.Second)
}

func TestConnSetLimitAndClose(t *testing.T) {
	set := newConnSet(1)
	if !set.reserve() {
		t.Fatal("first reserve was rejected")
	}
	a, b := net.Pipe()
	defer b.Close()
	owned, ok := set.add(a)
	if !ok {
		t.Fatal("first connection was not added")
	}
	if set.reserve() {
		t.Fatal("reserve succeeded beyond the configured limit")
	}
	if err := owned.Close(); err != nil {
		t.Fatalf("close owned connection: %v", err)
	}
	if err := owned.Close(); err != nil {
		t.Fatalf("second close of owned connection: %v", err)
	}
	if !set.reserve() {
		t.Fatal("reserve failed after a connection released its slot")
	}
	a2, b2 := net.Pipe()
	defer b2.Close()
	owned2, ok := set.add(a2)
	if !ok {
		t.Fatal("second connection was not added")
	}
	set.close()
	if set.reserve() {
		t.Fatal("reserve succeeded after the set was closed")
	}
	if err := owned2.Close(); err != nil {
		t.Fatalf("close connection after set close: %v", err)
	}
}

func TestFixedOutboundRejectsNonServiceTarget(t *testing.T) {
	set := newConnSet(1)
	defer set.close()
	o := &fixedOutbound{
		ctx:     context.Background(),
		target:  "127.0.0.1:1",
		timeout: time.Second,
		conns:   set,
	}

	if c, err := o.TCP("127.0.0.1:2"); err == nil {
		_ = c.Close()
		t.Fatal("TCP accepted a non-service target")
	} else if !errors.Is(err, errTargetDenied) {
		t.Fatalf("TCP error = %v, want errTargetDenied", err)
	}
}

func TestFixedOutboundConnectionLimit(t *testing.T) {
	ctx := newTestContext(t)
	echo := newEchoServer(t)
	set := newConnSet(1)
	defer set.close()
	o := &fixedOutbound{
		ctx:     ctx,
		target:  echo.Addr(),
		timeout: connDeadline,
		conns:   set,
	}

	first, err := o.TCP(serviceAddress)
	if err != nil {
		t.Fatalf("first outbound TCP: %v", err)
	}
	if err := first.SetDeadline(time.Now().Add(ioDeadline)); err != nil {
		_ = first.Close()
		t.Fatalf("set first outbound deadline: %v", err)
	}
	if second, err := o.TCP(serviceAddress); err == nil {
		_ = second.Close()
		t.Fatal("second outbound TCP succeeded beyond the connection limit")
	}
	if err := first.Close(); err != nil {
		t.Fatalf("close first outbound TCP: %v", err)
	}
	third, err := o.TCP(serviceAddress)
	if err != nil {
		t.Fatalf("outbound TCP after releasing a slot: %v", err)
	}
	if err := third.Close(); err != nil {
		t.Fatalf("close third outbound TCP: %v", err)
	}
}
