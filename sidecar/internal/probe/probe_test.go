package probe

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/apernet/hysteria/core/v2/server"
)

const probeTestTimeout = 10 * time.Second

func probeTestContext(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), probeTestTimeout)
	t.Cleanup(cancel)
	return ctx
}

type probeTestBackend struct {
	mu  sync.Mutex
	tcp int
}

func (b *probeTestBackend) TCP(string) (net.Conn, error) {
	b.mu.Lock()
	b.tcp++
	b.mu.Unlock()
	return nil, errors.New("probe test must not dial TCP")
}

func (b *probeTestBackend) count() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.tcp
}

type probeTestAuth struct {
	mu     sync.Mutex
	calls  int
	accept bool
}

func (a *probeTestAuth) Authenticate(net.Addr, string, uint64) (bool, string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.calls++
	return a.accept, "test"
}

func (a *probeTestAuth) count() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.calls
}

func startProbeServer(t *testing.T, response []byte) (string, *probeTestBackend, *probeTestAuth) {
	t.Helper()
	fixtures := filepath.Join("..", "..", "hy2-core", "internal", "integration_tests")
	cert, err := tls.LoadX509KeyPair(filepath.Join(fixtures, "test.crt"), filepath.Join(fixtures, "test.key"))
	if err != nil {
		t.Fatalf("load test certificate: %v", err)
	}
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen UDP: %v", err)
	}
	backend := &probeTestBackend{}
	auth := &probeTestAuth{}
	s, err := server.NewServer(&server.Config{
		TLSConfig:          server.TLSConfig{Certificates: []tls.Certificate{cert}},
		Conn:               pc,
		Outbound:           backend,
		Authenticator:      auth,
		CapabilityResponse: response,
	})
	if err != nil {
		_ = pc.Close()
		t.Fatalf("start server: %v", err)
	}
	go func() { _ = s.Serve() }()
	t.Cleanup(func() { _ = s.Close() })
	return pc.LocalAddr().String(), backend, auth
}

func TestCheckEnabled(t *testing.T) {
	addr, backend, auth := startProbeServer(t, []byte(`{"protocol":1,"enabled":true}`))
	result := Check(probeTestContext(t), addr)
	if result.State != StateEnabled {
		t.Fatalf("state = %q, want %q", result.State, StateEnabled)
	}
	if auth.count() != 0 {
		t.Fatalf("capability probe authenticated: %d calls", auth.count())
	}
	if tcp := backend.count(); tcp != 0 {
		t.Fatalf("capability probe dialed backend: tcp=%d", tcp)
	}
}

func TestCheckHasNoFilesystemOrTrustSideEffects(t *testing.T) {
	// The probe has no config, token, or trust-store path. Point every temp
	// directory at an empty directory and prove a successful check writes
	// nothing there (in particular, no TOFU/known-servers file).
	dir := t.TempDir()
	t.Setenv("TMPDIR", dir)
	t.Setenv("TMP", dir)
	t.Setenv("TEMP", dir)

	address, backend, auth := startProbeServer(t, []byte(`{"protocol":1,"enabled":true}`))
	if result := Check(probeTestContext(t), address); result.State != StateEnabled {
		t.Fatalf("state = %q, want %q", result.State, StateEnabled)
	}
	if auth.count() != 0 {
		t.Fatalf("capability probe authenticated: %d calls", auth.count())
	}
	if tcp := backend.count(); tcp != 0 {
		t.Fatalf("capability probe dialed backend: tcp=%d", tcp)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("read temp directory: %v", err)
	}
	if len(entries) != 0 {
		t.Fatalf("probe created files: %v", entries)
	}
}

func TestCheckDisabled(t *testing.T) {
	addr, _, _ := startProbeServer(t, []byte(`{"protocol":1,"enabled":false}`))
	if result := Check(probeTestContext(t), addr); result.State != StateDisabled {
		t.Fatalf("state = %q, want %q", result.State, StateDisabled)
	}
}

func TestCheckIncompatible(t *testing.T) {
	addr, _, _ := startProbeServer(t, []byte(`{"protocol":2,"enabled":true}`))
	if result := Check(probeTestContext(t), addr); result.State != StateIncompatible {
		t.Fatalf("state = %q, want %q", result.State, StateIncompatible)
	}
}

func TestCheckUndeclared(t *testing.T) {
	addr, backend, auth := startProbeServer(t, nil)
	result := Check(probeTestContext(t), addr)
	if result.State != StateUndeclared {
		t.Fatalf("state = %q, want %q", result.State, StateUndeclared)
	}
	if auth.count() != 0 {
		t.Fatalf("capability probe authenticated: %d calls", auth.count())
	}
	if tcp := backend.count(); tcp != 0 {
		t.Fatalf("capability probe dialed backend: tcp=%d", tcp)
	}
}

func TestCheckUnknownForInvalidDeclaration(t *testing.T) {
	addr, _, _ := startProbeServer(t, []byte(`{"protocol":1,"enabled":true,"extra":1}`))
	if result := Check(probeTestContext(t), addr); result.State != StateUnknown {
		t.Fatalf("state = %q, want %q", result.State, StateUnknown)
	}
}

func TestValidateAddress(t *testing.T) {
	valid := []string{
		"127.0.0.1:443",
		"[::1]:443",
		"[fe80::1%eth0]:443",
	}
	for _, address := range valid {
		if _, err := validateAddress(address); err != nil {
			t.Fatalf("validateAddress(%q): %v", address, err)
		}
	}
	invalid := []string{
		"localhost:443",
		"example.invalid:443",
		"0.0.0.0:443",
		"[::]:443",
		"127.0.0.1:0",
		"127.0.0.1:65536",
		"127.0.0.1:notaport",
		"127.0.0.1",
	}
	for _, address := range invalid {
		if _, err := validateAddress(address); err == nil {
			t.Fatalf("validateAddress(%q) succeeded, want error", address)
		}
	}
}

func TestCheckRejectsDNSAndBadAddress(t *testing.T) {
	for _, address := range []string{
		"localhost:443", "example.invalid:443", "0.0.0.0:443", "[::]:443",
		"127.0.0.1:0", "127.0.0.1:notaport",
	} {
		start := time.Now()
		result := Check(probeTestContext(t), address)
		if result.State != StateUnknown {
			t.Fatalf("%q: state = %q, want %q", address, result.State, StateUnknown)
		}
		if elapsed := time.Since(start); elapsed > time.Second {
			t.Fatalf("%q: address validation took %v", address, elapsed)
		}
	}
}

func TestCheckCanceledContext(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	start := time.Now()
	result := Check(ctx, "127.0.0.1:1")
	if result.State != StateUnknown {
		t.Fatalf("state = %q, want %q", result.State, StateUnknown)
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("canceled check took %v", elapsed)
	}
}

func TestCheckTimeoutAgainstSilentServer(t *testing.T) {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen silent UDP: %v", err)
	}
	t.Cleanup(func() { _ = pc.Close() })
	go func() {
		buf := make([]byte, 64*1024)
		for {
			if _, _, err := pc.ReadFrom(buf); err != nil {
				return
			}
		}
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	start := time.Now()
	result := Check(ctx, pc.LocalAddr().String())
	if result.State != StateUnknown {
		t.Fatalf("state = %q, want %q", result.State, StateUnknown)
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Fatalf("check ignored context deadline: %v", elapsed)
	}
}

func TestParseCapabilityStrict(t *testing.T) {
	tests := []struct {
		name     string
		input    string
		protocol int
		enabled  bool
		wantErr  bool
	}{
		{name: "enabled", input: `{"protocol":1,"enabled":true}`, protocol: 1, enabled: true},
		{name: "disabled", input: `{"protocol":1,"enabled":false}`, protocol: 1, enabled: false},
		{name: "future protocol", input: `{"protocol":2,"enabled":true}`, protocol: 2, enabled: true},
		{name: "whitespace", input: " \n{\"protocol\":1,\"enabled\":true}\t", protocol: 1, enabled: true},
		{name: "missing enabled", input: `{"protocol":1}`, wantErr: true},
		{name: "missing protocol", input: `{"enabled":true}`, wantErr: true},
		{name: "unknown field", input: `{"protocol":1,"enabled":true,"extra":1}`, wantErr: true},
		{name: "duplicate protocol", input: `{"protocol":1,"enabled":true,"protocol":2}`, wantErr: true},
		{name: "duplicate enabled", input: `{"protocol":1,"enabled":true,"enabled":false}`, wantErr: true},
		{name: "null protocol", input: `{"protocol":null,"enabled":true}`, wantErr: true},
		{name: "null enabled", input: `{"protocol":1,"enabled":null}`, wantErr: true},
		{name: "fractional protocol", input: `{"protocol":1.5,"enabled":true}`, wantErr: true},
		{name: "string protocol", input: `{"protocol":"1","enabled":true}`, wantErr: true},
		{name: "zero protocol", input: `{"protocol":0,"enabled":true}`, wantErr: true},
		{name: "negative protocol", input: `{"protocol":-1,"enabled":true}`, wantErr: true},
		{name: "protocol overflow", input: `{"protocol":1000000000,"enabled":true}`, wantErr: true},
		{name: "numeric enabled", input: `{"protocol":1,"enabled":1}`, wantErr: true},
		{name: "not object", input: `[]`, wantErr: true},
		{name: "null", input: `null`, wantErr: true},
		{name: "trailing data", input: `{"protocol":1,"enabled":true} {}`, wantErr: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			protocol, enabled, err := parseCapability([]byte(tt.input))
			if tt.wantErr {
				if err == nil {
					t.Fatalf("parseCapability(%q) succeeded, want error", tt.input)
				}
				return
			}
			if err != nil {
				t.Fatalf("parseCapability(%q): %v", tt.input, err)
			}
			if protocol != tt.protocol || enabled != tt.enabled {
				t.Fatalf("parseCapability(%q) = (%d,%v), want (%d,%v)",
					tt.input, protocol, enabled, tt.protocol, tt.enabled)
			}
		})
	}
}
