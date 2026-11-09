package server

import (
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/apernet/hysteria/core/v2/internal/protocol"
	"github.com/apernet/quic-go"
	"github.com/apernet/quic-go/http3"
)

const capabilityTestTimeout = 10 * time.Second

type capabilityBackend struct {
	mu  sync.Mutex
	tcp int
	udp int
}

func (b *capabilityBackend) TCP(string) (net.Conn, error) {
	b.mu.Lock()
	b.tcp++
	b.mu.Unlock()
	return nil, context.DeadlineExceeded
}

func (b *capabilityBackend) UDP(string) (UDPConn, error) {
	b.mu.Lock()
	b.udp++
	b.mu.Unlock()
	return nil, context.DeadlineExceeded
}

func (b *capabilityBackend) CheckUDP(string) error { return nil }

func (b *capabilityBackend) counts() (tcp, udp int) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.tcp, b.udp
}

type capabilityAuth struct {
	mu     sync.Mutex
	calls  int
	accept bool
}

func (a *capabilityAuth) Authenticate(net.Addr, string, uint64) (bool, string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.calls++
	return a.accept, "capability-test"
}

func (a *capabilityAuth) count() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.calls
}

func startCapabilityServer(t *testing.T, response []byte, auth *capabilityAuth) (string, *capabilityBackend) {
	t.Helper()
	fixtures := filepath.Join("..", "internal", "integration_tests")
	cert, err := tls.LoadX509KeyPair(filepath.Join(fixtures, "test.crt"), filepath.Join(fixtures, "test.key"))
	require.NoError(t, err)
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	require.NoError(t, err)
	if auth == nil {
		auth = &capabilityAuth{}
	}
	backend := &capabilityBackend{}
	s, err := NewServer(&Config{
		TLSConfig:          TLSConfig{Certificates: []tls.Certificate{cert}},
		Conn:               pc,
		Outbound:           backend,
		Authenticator:      auth,
		DisableUDP:         true,
		CapabilityResponse: response,
	})
	if err != nil {
		_ = pc.Close()
		t.Fatalf("start capability server: %v", err)
	}
	go func() { _ = s.Serve() }()
	t.Cleanup(func() { _ = s.Close() })
	return pc.LocalAddr().String(), backend
}

type capabilityClient struct {
	tr   *http3.Transport
	conn *quic.Conn
}

func newCapabilityClient(t *testing.T, address string) *capabilityClient {
	t.Helper()
	c := &capabilityClient{}
	c.tr = &http3.Transport{
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
		Dial: func(ctx context.Context, _ string, tlsCfg *tls.Config, cfg *quic.Config) (*quic.Conn, error) {
			conn, err := quic.DialAddrEarly(ctx, address, tlsCfg, cfg)
			if err != nil {
				return nil, err
			}
			c.conn = conn
			return conn, nil
		},
	}
	t.Cleanup(func() { _ = c.tr.Close() })
	return c
}

func (c *capabilityClient) do(t *testing.T, method, host, path string, header http.Header) *http.Response {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), capabilityTestTimeout)
	t.Cleanup(cancel)
	req, err := http.NewRequestWithContext(ctx, method, "https://"+host+path, nil)
	require.NoError(t, err)
	if header != nil {
		req.Header = header
	}
	resp, err := c.tr.RoundTrip(req)
	require.NoError(t, err)
	t.Cleanup(func() { _ = resp.Body.Close() })
	return resp
}

func TestCapabilityExactRequestReturnsFixedJSON(t *testing.T) {
	body := []byte(`{"protocol":1,"enabled":true}`)
	address, backend := startCapabilityServer(t, body, nil)
	c := newCapabilityClient(t, address)

	header := make(http.Header)
	protocol.AuthRequestToHeader(header, protocol.AuthRequest{Auth: "wrong-token"})
	resp := c.do(t, http.MethodGet, capabilityHost, capabilityPath, header)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	assert.Equal(t, "application/json", resp.Header.Get("Content-Type"))

	got, err := io.ReadAll(resp.Body)
	require.NoError(t, err)
	assert.Equal(t, body, got)
	if tcp, udp := backend.counts(); tcp != 0 || udp != 0 {
		t.Fatalf("capability request dialed backend: tcp=%d udp=%d", tcp, udp)
	}
}

func TestCapabilityDoesNotAuthenticate(t *testing.T) {
	auth := &capabilityAuth{accept: true}
	address, backend := startCapabilityServer(t, []byte(`{"protocol":1,"enabled":true}`), auth)
	c := newCapabilityClient(t, address)

	header := make(http.Header)
	protocol.AuthRequestToHeader(header, protocol.AuthRequest{Auth: "wrong-token"})
	resp := c.do(t, http.MethodGet, capabilityHost, capabilityPath, header)
	require.Equal(t, http.StatusOK, resp.StatusCode)
	assert.Zero(t, auth.count(), "capability request must not call the authenticator")

	// The original auth endpoint must still work and must be the only path that
	// authenticates the connection.
	authHeader := make(http.Header)
	protocol.AuthRequestToHeader(authHeader, protocol.AuthRequest{Auth: "right-token"})
	authResp := c.do(t, http.MethodPost, protocol.URLHost, protocol.URLPath, authHeader)
	require.Equal(t, protocol.StatusAuthOK, authResp.StatusCode)
	assert.Equal(t, 1, auth.count())
	if tcp, udp := backend.counts(); tcp != 0 || udp != 0 {
		t.Fatalf("capability/auth requests dialed backend: tcp=%d udp=%d", tcp, udp)
	}
}

func TestCapabilityEndpointMismatchesReturnNotFound(t *testing.T) {
	address, _ := startCapabilityServer(t, []byte(`{"protocol":1,"enabled":true}`), nil)
	c := newCapabilityClient(t, address)

	tests := []struct {
		name   string
		method string
		host   string
		path   string
	}{
		{name: "wrong host", method: http.MethodGet, host: "other", path: capabilityPath},
		{name: "wrong path", method: http.MethodGet, host: capabilityHost, path: "/other"},
		{name: "query", method: http.MethodGet, host: capabilityHost, path: capabilityPath + "?x=1"},
		{name: "post", method: http.MethodPost, host: capabilityHost, path: capabilityPath},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			resp := c.do(t, tt.method, tt.host, tt.path, nil)
			assert.Equal(t, http.StatusNotFound, resp.StatusCode)
		})
	}
}

func TestCapabilityServedOncePerConnection(t *testing.T) {
	body := []byte(`{"protocol":1,"enabled":true}`)
	address, _ := startCapabilityServer(t, body, nil)
	c := newCapabilityClient(t, address)

	first := c.do(t, http.MethodGet, capabilityHost, capabilityPath, nil)
	require.Equal(t, http.StatusOK, first.StatusCode)
	got, err := io.ReadAll(first.Body)
	require.NoError(t, err)
	assert.Equal(t, body, got)

	second := c.do(t, http.MethodGet, capabilityHost, capabilityPath, nil)
	assert.Equal(t, http.StatusNotFound, second.StatusCode,
		"a second capability request on the same connection must be bounded")
}

func TestCapabilityUndeclaredWhenResponseEmpty(t *testing.T) {
	address, _ := startCapabilityServer(t, nil, nil)
	c := newCapabilityClient(t, address)
	resp := c.do(t, http.MethodGet, capabilityHost, capabilityPath, nil)
	assert.Equal(t, http.StatusNotFound, resp.StatusCode)
}

func TestCapabilityResponseValidation(t *testing.T) {
	c := newValidTestConfig(t)
	c.CapabilityResponse = make([]byte, maxCapabilityResponseBytes+1)
	assertConfigError(t, c.fill(), "CapabilityResponse")

	c = newValidTestConfig(t)
	c.CapabilityResponse = make([]byte, maxCapabilityResponseBytes)
	require.NoError(t, c.fill())

	c = newValidTestConfig(t)
	c.CapabilityResponse = nil
	require.NoError(t, c.fill())
}
