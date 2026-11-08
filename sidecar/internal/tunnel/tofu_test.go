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
	"math/big"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/llll415/HyperConduit/sidecar/internal/config"
	"github.com/llll415/HyperConduit/sidecar/internal/identity"
	"github.com/llll415/HyperConduit/sidecar/internal/trust"
)

// knownServersPath mirrors the client default derived by config.Normalize.
func knownServersPath(stateDir string) string {
	return filepath.Join(stateDir, "known-servers.json")
}

func readKnownServers(t *testing.T, ctx context.Context, stateDir string) map[string]string {
	t.Helper()
	entries, err := trust.New(knownServersPath(stateDir)).Entries(ctx)
	if err != nil {
		t.Fatalf("read known servers: %v", err)
	}
	return entries
}

func requirePin(t *testing.T, entries map[string]string, address string) string {
	t.Helper()
	endpoint, err := trust.Endpoint(address)
	if err != nil {
		t.Fatalf("canonical endpoint %q: %v", address, err)
	}
	pin, ok := entries[endpoint]
	if !ok || pin == "" {
		t.Fatalf("no pin recorded for %s in %v", endpoint, entries)
	}
	return pin
}

func requireErrorCode(t *testing.T, err error, want ErrorCode) {
	t.Helper()
	if err == nil {
		t.Fatalf("expected a %q error, got nil", want)
	}
	var tunnelErr *Error
	if !errors.As(err, &tunnelErr) || tunnelErr == nil {
		t.Fatalf("error %T is not a classified tunnel error: %v", err, err)
	}
	if tunnelErr.Code != want {
		t.Fatalf("error code = %q, want %q", tunnelErr.Code, want)
	}
}

func requireMissingFile(t *testing.T, path string) {
	t.Helper()
	if _, err := os.Stat(path); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("%s exists or cannot be checked: %v", path, err)
	}
}

func serverListenAddress(t *testing.T, e *Endpoint) string {
	t.Helper()
	host, port, err := net.SplitHostPort(e.Addr().String())
	if err != nil {
		t.Fatalf("server address %q is not host:port: %v", e.Addr(), err)
	}
	return net.JoinHostPort(host, port)
}

// startAutomaticServer starts a server that owns an automatically managed
// identity. An empty identityFile selects <stateDir>/server-identity.json.
func startAutomaticServer(t *testing.T, ctx context.Context, target, stateDir, identityFile, listen, token string) *Endpoint {
	t.Helper()
	e, err := StartServer(ctx, config.Config{
		Listen:       listen,
		Target:       target,
		StateDir:     stateDir,
		IdentityFile: identityFile,
		Token:        token,
	})
	if err != nil {
		t.Fatalf("StartServer with automatic identity: %v", err)
	}
	t.Cleanup(func() { _ = e.Close() })
	return e
}

func startServerFiles(t *testing.T, ctx context.Context, target, certFile, keyFile, listen, token string) *Endpoint {
	t.Helper()
	e, err := StartServer(ctx, config.Config{
		Listen:   listen,
		Target:   target,
		CertFile: certFile,
		KeyFile:  keyFile,
		Token:    token,
	})
	if err != nil {
		t.Fatalf("StartServer with certificate files: %v", err)
	}
	t.Cleanup(func() { _ = e.Close() })
	return e
}

func startTOFUClient(t *testing.T, ctx context.Context, server, stateDir, token string, connectTimeoutMS int) (*Endpoint, error) {
	t.Helper()
	return StartClient(ctx, config.Config{
		Listen:           "127.0.0.1:0",
		Server:           server,
		StateDir:         stateDir,
		Token:            token,
		ConnectTimeoutMS: connectTimeoutMS,
	})
}

func mustStartTOFUClient(t *testing.T, ctx context.Context, server, stateDir, token string) *Endpoint {
	t.Helper()
	e, err := startTOFUClient(t, ctx, server, stateDir, token, 0)
	if err != nil {
		t.Fatalf("StartClient with TOFU: %v", err)
	}
	t.Cleanup(func() { _ = e.Close() })
	return e
}

func newTestKey(t *testing.T) *ecdsa.PrivateKey {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate ECDSA key: %v", err)
	}
	return key
}

func testCertificateTemplate(t *testing.T, commonName string, notBefore, notAfter time.Time) *x509.Certificate {
	t.Helper()
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		t.Fatalf("generate serial number: %v", err)
	}
	return &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: commonName},
		NotBefore:             notBefore,
		NotAfter:              notAfter,
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IsCA:                  true,
		DNSNames:              []string{"localhost"},
		IPAddresses:           []net.IP{net.IPv4(127, 0, 0, 1), net.IPv6loopback},
	}
}

// writeTestCertificate self-signs template with key and returns PEM file paths.
func writeTestCertificate(t *testing.T, key *ecdsa.PrivateKey, template *x509.Certificate) (certFile, keyFile string) {
	t.Helper()
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatalf("create certificate: %v", err)
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		t.Fatalf("marshal private key: %v", err)
	}

	dir := t.TempDir()
	certFile = filepath.Join(dir, "cert.pem")
	keyFile = filepath.Join(dir, "key.pem")
	if err := os.WriteFile(certFile, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o600); err != nil {
		t.Fatalf("write certificate: %v", err)
	}
	if err := os.WriteFile(keyFile, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}), 0o600); err != nil {
		t.Fatalf("write private key: %v", err)
	}
	return certFile, keyFile
}

func parseCertificateFile(t *testing.T, path string) *x509.Certificate {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read certificate %s: %v", path, err)
	}
	block, _ := pem.Decode(raw)
	if block == nil {
		t.Fatalf("certificate %s contains no PEM block", path)
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Fatalf("parse certificate %s: %v", path, err)
	}
	return cert
}

// TestTOFUFirstTrustAndServerRestart covers the default deployment: neither a
// certificate file nor a CA file is configured. The first client trusts the
// automatically generated server identity, and the pin still matches after the
// server is restarted on the same port from the same identity path.
func TestTOFUFirstTrustAndServerRestart(t *testing.T) {
	ctx := newTestContext(t)
	echo := newEchoServer(t)
	stateDir := t.TempDir()

	s := startAutomaticServer(t, ctx, echo.Addr(), stateDir, "", "127.0.0.1:0", "")
	serverAddr := s.Addr().String()
	listen := serverListenAddress(t, s)

	c := mustStartTOFUClient(t, ctx, serverAddr, stateDir, "")
	assertEcho(t, dialLocal(t, c), []byte("first automatic TOFU trust"))

	entries := readKnownServers(t, ctx, stateDir)
	pin := requirePin(t, entries, serverAddr)
	if len(entries) != 1 {
		t.Fatalf("known servers has %d entries, want 1: %v", len(entries), entries)
	}

	if err := c.Close(); err != nil {
		t.Fatalf("close first client: %v", err)
	}
	if err := s.Close(); err != nil {
		t.Fatalf("close first server: %v", err)
	}

	s2 := startAutomaticServer(t, ctx, echo.Addr(), stateDir, "", listen, "")
	if got := s2.Addr().String(); got != serverAddr {
		t.Fatalf("restarted server address = %q, want %q", got, serverAddr)
	}
	c2 := mustStartTOFUClient(t, ctx, serverAddr, stateDir, "")
	assertEcho(t, dialLocal(t, c2), []byte("same identity after restart"))

	if got := requirePin(t, readKnownServers(t, ctx, stateDir), serverAddr); got != pin {
		t.Fatalf("pin after restart = %q, want unchanged %q", got, pin)
	}
}

// TestTOFURejectsChangedServerIdentity uses a different automatic identity file
// on the same port. The client must report identity_changed and must leave the
// existing pin database byte-for-byte untouched.
func TestTOFURejectsChangedServerIdentity(t *testing.T) {
	ctx := newTestContext(t)
	echo := newEchoServer(t)
	stateDir := t.TempDir()

	s := startAutomaticServer(t, ctx, echo.Addr(), stateDir, "", "127.0.0.1:0", "")
	serverAddr := s.Addr().String()
	listen := serverListenAddress(t, s)

	c := mustStartTOFUClient(t, ctx, serverAddr, stateDir, "")
	assertEcho(t, dialLocal(t, c), []byte("identity before rotation"))
	pinBefore := requirePin(t, readKnownServers(t, ctx, stateDir), serverAddr)

	knownPath := knownServersPath(stateDir)
	bytesBefore, err := os.ReadFile(knownPath)
	if err != nil {
		t.Fatalf("read pin database: %v", err)
	}

	if err := c.Close(); err != nil {
		t.Fatalf("close first client: %v", err)
	}
	if err := s.Close(); err != nil {
		t.Fatalf("close first server: %v", err)
	}

	startAutomaticServer(t, ctx, echo.Addr(), stateDir, filepath.Join(stateDir, "other-identity.json"), listen, "")
	_, err = startTOFUClient(t, ctx, serverAddr, stateDir, "", 0)
	requireErrorCode(t, err, CodeIdentity)

	bytesAfter, err := os.ReadFile(knownPath)
	if err != nil {
		t.Fatalf("read pin database after rejection: %v", err)
	}
	if !bytes.Equal(bytesAfter, bytesBefore) {
		t.Fatalf("pin database was modified after an identity change:\nbefore: %s\nafter:  %s", bytesBefore, bytesAfter)
	}
	if got := requirePin(t, readKnownServers(t, ctx, stateDir), serverAddr); got != pinBefore {
		t.Fatalf("pin = %q, want unchanged %q", got, pinBefore)
	}
}

// TestTOFUForgetAllowsRetrust forgets the endpoint and then accepts a new
// automatic identity as a fresh first-use trust decision.
func TestTOFUForgetAllowsRetrust(t *testing.T) {
	ctx := newTestContext(t)
	echo := newEchoServer(t)
	stateDir := t.TempDir()

	s := startAutomaticServer(t, ctx, echo.Addr(), stateDir, "", "127.0.0.1:0", "")
	serverAddr := s.Addr().String()
	listen := serverListenAddress(t, s)

	c := mustStartTOFUClient(t, ctx, serverAddr, stateDir, "")
	assertEcho(t, dialLocal(t, c), []byte("identity before forget"))
	oldPin := requirePin(t, readKnownServers(t, ctx, stateDir), serverAddr)

	store := trust.New(knownServersPath(stateDir))
	if err := store.Forget(ctx, serverAddr); err != nil {
		t.Fatalf("Forget: %v", err)
	}
	entries, err := store.Entries(ctx)
	if err != nil {
		t.Fatalf("Entries after Forget: %v", err)
	}
	if len(entries) != 0 {
		t.Fatalf("Entries after Forget = %v, want empty", entries)
	}

	if err := c.Close(); err != nil {
		t.Fatalf("close first client: %v", err)
	}
	if err := s.Close(); err != nil {
		t.Fatalf("close first server: %v", err)
	}

	startAutomaticServer(t, ctx, echo.Addr(), stateDir, filepath.Join(stateDir, "replacement-identity.json"), listen, "")
	c2 := mustStartTOFUClient(t, ctx, serverAddr, stateDir, "")
	assertEcho(t, dialLocal(t, c2), []byte("fresh trust after forget"))

	newPin := requirePin(t, readKnownServers(t, ctx, stateDir), serverAddr)
	if newPin == oldPin {
		t.Fatal("replacement identity reused the old pin")
	}
}

// TestTOFUTokenFailureDoesNotCommit verifies that a rejected HY2 token leaves
// no pin database, and that the subsequent correct token commits the pin.
func TestTOFUTokenFailureDoesNotCommit(t *testing.T) {
	ctx := newTestContext(t)
	echo := newEchoServer(t)
	stateDir := t.TempDir()

	s := startAutomaticServer(t, ctx, echo.Addr(), stateDir, "", "127.0.0.1:0", "correct-horse")
	serverAddr := s.Addr().String()

	if _, err := startTOFUClient(t, ctx, serverAddr, stateDir, "wrong-battery", 0); err == nil {
		t.Fatal("StartClient succeeded with a wrong token")
	}
	// The lock file may exist, but no pin may be committed before auth succeeds.
	requireMissingFile(t, knownServersPath(stateDir))

	c := mustStartTOFUClient(t, ctx, serverAddr, stateDir, "correct-horse")
	assertEcho(t, dialLocal(t, c), []byte("token accepted after failure"))
	if pin := requirePin(t, readKnownServers(t, ctx, stateDir), serverAddr); pin == "" {
		t.Fatal("pin was not committed after successful authentication")
	}
}

// TestTOFUTimeoutDoesNotTrust checks that a server that never completes the
// handshake is not pinned.
func TestTOFUTimeoutDoesNotTrust(t *testing.T) {
	ctx := newTestContext(t)
	stateDir := t.TempDir()
	silent := newSilentUDP(t)

	if _, err := startTOFUClient(t, ctx, silent.Addr().String(), stateDir, "", 100); err == nil {
		t.Fatal("StartClient connected to a silent UDP endpoint")
	}
	requireMissingFile(t, knownServersPath(stateDir))
}

// TestTOFUCorruptStoreFailsClosed makes sure a malformed pin database is
// reported as a storage failure and is never replaced by a new pin.
func TestTOFUCorruptStoreFailsClosed(t *testing.T) {
	ctx := newTestContext(t)
	echo := newEchoServer(t)
	stateDir := t.TempDir()

	knownPath := knownServersPath(stateDir)
	corrupt := []byte(`{"version":1,"pins":{`)
	if err := os.WriteFile(knownPath, corrupt, 0o600); err != nil {
		t.Fatalf("write corrupt pin database: %v", err)
	}

	s := startAutomaticServer(t, ctx, echo.Addr(), stateDir, "", "127.0.0.1:0", "")
	_, err := startTOFUClient(t, ctx, s.Addr().String(), stateDir, "", 0)
	requireErrorCode(t, err, CodeStorage)

	after, readErr := os.ReadFile(knownPath)
	if readErr != nil {
		t.Fatalf("read corrupt pin database after failure: %v", readErr)
	}
	if !bytes.Equal(after, corrupt) {
		t.Fatalf("corrupt pin database was overwritten: %q", after)
	}
}

// TestTOFURejectsExpiredSelfSigned ensures TOFU still enforces the certificate
// validity period for self-signed certificates.
func TestTOFURejectsExpiredSelfSigned(t *testing.T) {
	ctx := newTestContext(t)
	echo := newEchoServer(t)
	stateDir := t.TempDir()

	now := time.Now()
	key := newTestKey(t)
	certFile, keyFile := writeTestCertificate(t, key, testCertificateTemplate(t, "localhost", now.Add(-48*time.Hour), now.Add(-24*time.Hour)))
	s := startServerFiles(t, ctx, echo.Addr(), certFile, keyFile, "127.0.0.1:0", "")

	_, err := startTOFUClient(t, ctx, s.Addr().String(), stateDir, "", 0)
	requireErrorCode(t, err, CodeTLS)
	requireMissingFile(t, knownServersPath(stateDir))
}

// TestTOFUSameKeyRenewalKeepsTrust re-issues the server certificate with the
// same key. Because the SPKI is unchanged, the existing pin remains valid.
func TestTOFUSameKeyRenewalKeepsTrust(t *testing.T) {
	ctx := newTestContext(t)
	echo := newEchoServer(t)
	stateDir := t.TempDir()

	now := time.Now()
	key := newTestKey(t)
	firstCert, firstKey := writeTestCertificate(t, key, testCertificateTemplate(t, "localhost", now.Add(-time.Hour), now.Add(24*time.Hour)))
	s := startServerFiles(t, ctx, echo.Addr(), firstCert, firstKey, "127.0.0.1:0", "")
	serverAddr := s.Addr().String()
	listen := serverListenAddress(t, s)

	c := mustStartTOFUClient(t, ctx, serverAddr, stateDir, "")
	assertEcho(t, dialLocal(t, c), []byte("certificate before renewal"))
	pinBefore := requirePin(t, readKnownServers(t, ctx, stateDir), serverAddr)

	if err := c.Close(); err != nil {
		t.Fatalf("close first client: %v", err)
	}
	if err := s.Close(); err != nil {
		t.Fatalf("close first server: %v", err)
	}

	renewedCert, renewedKey := writeTestCertificate(t, key, testCertificateTemplate(t, "localhost", now.Add(-time.Minute), now.Add(48*time.Hour)))
	if got, want := identity.Fingerprint(parseCertificateFile(t, renewedCert)), identity.Fingerprint(parseCertificateFile(t, firstCert)); got != want {
		t.Fatalf("renewed SPKI = %q, want unchanged %q", got, want)
	}

	startServerFiles(t, ctx, echo.Addr(), renewedCert, renewedKey, listen, "")
	c2 := mustStartTOFUClient(t, ctx, serverAddr, stateDir, "")
	assertEcho(t, dialLocal(t, c2), []byte("certificate after renewal"))

	if got := requirePin(t, readKnownServers(t, ctx, stateDir), serverAddr); got != pinBefore {
		t.Fatalf("pin after renewal = %q, want unchanged %q", got, pinBefore)
	}
}

// TestCAModeDoesNotCreateKnownStore is the regression guard for the existing
// explicit-CA tests: a configured CA file uses standard verification and must
// not create or touch a TOFU pin database.
func TestCAModeDoesNotCreateKnownStore(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	echo := newEchoServer(t)
	stateDir := t.TempDir()

	s := startServer(t, ctx, echo.Addr(), id, "")
	c, err := StartClient(ctx, config.Config{
		Listen:   "127.0.0.1:0",
		Server:   s.Addr().String(),
		CAFile:   id.certFile,
		StateDir: stateDir,
	})
	if err != nil {
		t.Fatalf("StartClient with explicit CA: %v", err)
	}
	t.Cleanup(func() { _ = c.Close() })

	assertEcho(t, dialLocal(t, c), []byte("standard CA verification"))
	requireMissingFile(t, knownServersPath(stateDir))
}
