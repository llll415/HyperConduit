package identity

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/asn1"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

func testContext(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	t.Cleanup(cancel)
	return ctx
}

func testPath(t *testing.T) string {
	t.Helper()
	return filepath.Join(t.TempDir(), "identity.json")
}

func mustLoadAt(t *testing.T, ctx context.Context, path string, now time.Time) tls.Certificate {
	t.Helper()
	cert, err := loadOrCreate(ctx, path, now)
	if err != nil {
		t.Fatalf("loadOrCreate: %v", err)
	}
	return cert
}

func mustMarshalStored(t *testing.T, stored storedFile) []byte {
	t.Helper()
	data, err := json.Marshal(stored)
	if err != nil {
		t.Fatalf("marshal stored identity: %v", err)
	}
	return data
}

func newTestStored(t *testing.T, now time.Time) storedFile {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	certPEM, _, err := newCertificate(key, now)
	if err != nil {
		t.Fatalf("create certificate: %v", err)
	}
	keyPEM, err := marshalPrivateKey(key)
	if err != nil {
		t.Fatalf("marshal key: %v", err)
	}
	return storedFile{
		Version:        fileVersion,
		CertificatePEM: string(certPEM),
		PrivateKeyPEM:  string(keyPEM),
	}
}

func readStored(t *testing.T, path string) storedFile {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read identity file: %v", err)
	}
	stored, err := decodeStored(data)
	if err != nil {
		t.Fatalf("decode identity file: %v", err)
	}
	return stored
}

func assertP256Key(t *testing.T, cert tls.Certificate) *ecdsa.PublicKey {
	t.Helper()
	key, ok := cert.PrivateKey.(*ecdsa.PrivateKey)
	if !ok || key.Curve != elliptic.P256() {
		t.Fatalf("private key is not ECDSA P-256: %T", cert.PrivateKey)
	}
	pub, ok := cert.Leaf.PublicKey.(*ecdsa.PublicKey)
	if !ok || pub.Curve != elliptic.P256() {
		t.Fatalf("certificate public key is not ECDSA P-256: %T", cert.Leaf.PublicKey)
	}
	return pub
}

func TestFingerprint(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	_, leaf, err := newCertificate(key, now)
	if err != nil {
		t.Fatalf("create certificate: %v", err)
	}

	sum := sha256.Sum256(leaf.RawSubjectPublicKeyInfo)
	want := "SHA256:" + base64.RawStdEncoding.EncodeToString(sum[:])
	got := Fingerprint(leaf)
	if got != want {
		t.Fatalf("Fingerprint() = %q, want %q", got, want)
	}
	if strings.Contains(got, "=") {
		t.Fatalf("Fingerprint() must use unpadded base64: %q", got)
	}
	if Fingerprint(nil) != "" {
		t.Fatalf("Fingerprint(nil) = %q, want empty", Fingerprint(nil))
	}
}

func TestLoadOrCreateCreatesAndReusesIdentity(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	path := testPath(t)
	ctx := testContext(t)

	first := mustLoadAt(t, ctx, path, now)
	if first.Leaf == nil {
		t.Fatal("returned certificate has no Leaf")
	}
	assertP256Key(t, first)

	if first.Leaf.IsCA {
		t.Fatal("managed certificate must not be a CA")
	}
	if first.Leaf.KeyUsage&x509.KeyUsageDigitalSignature == 0 {
		t.Fatal("managed certificate is missing digitalSignature key usage")
	}
	if !hasServerAuth(first.Leaf.ExtKeyUsage) {
		t.Fatal("managed certificate is missing serverAuth extended key usage")
	}
	if !first.Leaf.NotBefore.Equal(now.Add(-notBeforeSkew)) {
		t.Fatalf("NotBefore = %s, want %s", first.Leaf.NotBefore, now.Add(-notBeforeSkew))
	}
	if !first.Leaf.NotAfter.Equal(now.Add(certificateValidity)) {
		t.Fatalf("NotAfter = %s, want %s", first.Leaf.NotAfter, now.Add(certificateValidity))
	}
	if len(first.Leaf.DNSNames) != 1 || first.Leaf.DNSNames[0] != fixedDNSName {
		t.Fatalf("DNSNames = %v, want [%s]", first.Leaf.DNSNames, fixedDNSName)
	}
	if !containsIP(first.Leaf.IPAddresses, net.IPv4(127, 0, 0, 1)) || !containsIP(first.Leaf.IPAddresses, net.IPv6loopback) {
		t.Fatalf("IPAddresses = %v, want loopback SANs", first.Leaf.IPAddresses)
	}

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read identity file: %v", err)
	}
	if len(data) > maxFileBytes {
		t.Fatalf("identity file is %d bytes, max %d", len(data), maxFileBytes)
	}
	stored, err := decodeStored(data)
	if err != nil {
		t.Fatalf("decode identity file: %v", err)
	}
	certBlock, rest := pem.Decode([]byte(stored.CertificatePEM))
	if certBlock == nil || certBlock.Type != "CERTIFICATE" || len(bytes.TrimSpace(rest)) != 0 {
		t.Fatal("certificatePem is not exactly one CERTIFICATE PEM block")
	}
	keyBlock, rest := pem.Decode([]byte(stored.PrivateKeyPEM))
	if keyBlock == nil || keyBlock.Type != "PRIVATE KEY" || len(bytes.TrimSpace(rest)) != 0 {
		t.Fatal("privateKeyPem is not exactly one PKCS#8 PRIVATE KEY PEM block")
	}

	if _, err := os.Stat(path + ".lock"); err != nil {
		t.Fatalf("lock file was not created at path+\".lock\": %v", err)
	}

	if runtime.GOOS != "windows" {
		info, err := os.Stat(path)
		if err != nil {
			t.Fatalf("stat identity file: %v", err)
		}
		if info.Mode().Perm() != 0o600 {
			t.Fatalf("identity file mode = %o, want 0600", info.Mode().Perm())
		}
	}

	// A second call, including after some time has passed, must reuse both the
	// certificate and the key without rewriting the file.
	second := mustLoadAt(t, ctx, path, now.Add(time.Hour))
	if Fingerprint(first.Leaf) != Fingerprint(second.Leaf) {
		t.Fatalf("fingerprint changed across restart: %q != %q", Fingerprint(first.Leaf), Fingerprint(second.Leaf))
	}
	firstPub := assertP256Key(t, first)
	secondPub := assertP256Key(t, second)
	if !firstPub.Equal(secondPub) {
		t.Fatal("public key changed across restart")
	}
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read identity file after restart: %v", err)
	}
	if !bytes.Equal(data, after) {
		t.Fatal("valid identity file was rewritten without renewal")
	}
}

func TestLoadOrCreateRenewsWithSameKey(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	path := testPath(t)
	ctx := testContext(t)

	first := mustLoadAt(t, ctx, path, now)
	firstStored := readStored(t, path)
	firstPub := assertP256Key(t, first)

	// One hour inside the 30-day renewal window.
	renewAt := now.Add(certificateValidity - renewBefore + time.Hour)
	second := mustLoadAt(t, ctx, path, renewAt)

	if Fingerprint(first.Leaf) != Fingerprint(second.Leaf) {
		t.Fatalf("renewal changed SPKI fingerprint: %q != %q", Fingerprint(first.Leaf), Fingerprint(second.Leaf))
	}
	secondPub := assertP256Key(t, second)
	if !firstPub.Equal(secondPub) {
		t.Fatal("renewal changed the public key")
	}
	if second.Leaf.SerialNumber.Cmp(first.Leaf.SerialNumber) == 0 {
		t.Fatal("renewed certificate kept the old serial number")
	}
	if !second.Leaf.NotBefore.Equal(renewAt.Add(-notBeforeSkew)) {
		t.Fatalf("renewed NotBefore = %s, want %s", second.Leaf.NotBefore, renewAt.Add(-notBeforeSkew))
	}
	if !second.Leaf.NotAfter.Equal(renewAt.Add(certificateValidity)) {
		t.Fatalf("renewed NotAfter = %s, want %s", second.Leaf.NotAfter, renewAt.Add(certificateValidity))
	}

	secondStored := readStored(t, path)
	if secondStored.PrivateKeyPEM != firstStored.PrivateKeyPEM {
		t.Fatal("renewal rewrote the private key")
	}
	if secondStored.CertificatePEM == firstStored.CertificatePEM {
		t.Fatal("renewal did not write a new certificate")
	}
}

func TestLoadOrCreateDoesNotRenewOutsideWindow(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	path := testPath(t)
	ctx := testContext(t)

	first := mustLoadAt(t, ctx, path, now)
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read identity file: %v", err)
	}

	// One hour before the renewal window opens.
	early := now.Add(certificateValidity - renewBefore - time.Hour)
	second := mustLoadAt(t, ctx, path, early)
	if Fingerprint(first.Leaf) != Fingerprint(second.Leaf) {
		t.Fatal("certificate changed outside the renewal window")
	}
	if second.Leaf.SerialNumber.Cmp(first.Leaf.SerialNumber) != 0 {
		t.Fatal("certificate was re-signed outside the renewal window")
	}
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read identity file: %v", err)
	}
	if !bytes.Equal(before, after) {
		t.Fatal("identity file changed outside the renewal window")
	}
}

func TestLoadOrCreateRenewsExpiredCertificate(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	path := testPath(t)
	ctx := testContext(t)

	first := mustLoadAt(t, ctx, path, now)
	firstPub := assertP256Key(t, first)

	expiredAt := now.Add(certificateValidity + time.Hour)
	second := mustLoadAt(t, ctx, path, expiredAt)
	if Fingerprint(first.Leaf) != Fingerprint(second.Leaf) {
		t.Fatal("expired certificate was not renewed with the same key")
	}
	secondPub := assertP256Key(t, second)
	if !firstPub.Equal(secondPub) {
		t.Fatal("expired certificate renewal changed the public key")
	}
	if !second.Leaf.NotAfter.Equal(expiredAt.Add(certificateValidity)) {
		t.Fatalf("renewed NotAfter = %s, want %s", second.Leaf.NotAfter, expiredAt.Add(certificateValidity))
	}
}

func TestLoadOrCreateRejectsFutureNotBeforeWithoutRebuild(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	path := testPath(t)
	ctx := testContext(t)

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	certPEM, _, err := newCertificate(key, now.Add(time.Hour))
	if err != nil {
		t.Fatalf("create certificate: %v", err)
	}
	keyPEM, err := marshalPrivateKey(key)
	if err != nil {
		t.Fatalf("marshal key: %v", err)
	}
	stored := storedFile{Version: fileVersion, CertificatePEM: string(certPEM), PrivateKeyPEM: string(keyPEM)}
	data := mustMarshalStored(t, stored)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatalf("write identity file: %v", err)
	}

	if _, err := loadOrCreate(ctx, path, now); err == nil {
		t.Fatal("loadOrCreate accepted a certificate whose NotBefore is in the future")
	} else if !strings.Contains(err.Error(), "not valid yet") {
		t.Fatalf("unexpected error: %v", err)
	}

	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read identity file: %v", err)
	}
	if !bytes.Equal(data, after) {
		t.Fatal("rejected future certificate was silently rebuilt")
	}
}

func TestLoadOrCreateRejectsCorruptFileWithoutRebuild(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	ctx := testContext(t)
	valid := newTestStored(t, now)
	other := newTestStored(t, now)
	validJSON := mustMarshalStored(t, valid)

	unknownField := fmt.Sprintf(
		`{"version":1,"certificatePem":%q,"privateKeyPem":%q,"extra":true}`,
		valid.CertificatePEM, valid.PrivateKeyPEM,
	)
	duplicateField := fmt.Sprintf(
		`{"version":1,"version":1,"certificatePem":%q,"privateKeyPem":%q}`,
		valid.CertificatePEM, valid.PrivateKeyPEM,
	)
	trailing := append(append([]byte(nil), validJSON...), []byte(`{}`)...)

	cases := []struct {
		name string
		data []byte
	}{
		{name: "empty", data: nil},
		{name: "invalid json", data: []byte("{")},
		{name: "missing version", data: mustMarshalStored(t, storedFile{
			CertificatePEM: valid.CertificatePEM, PrivateKeyPEM: valid.PrivateKeyPEM,
		})},
		{name: "unsupported version", data: mustMarshalStored(t, storedFile{
			Version: fileVersion + 1, CertificatePEM: valid.CertificatePEM, PrivateKeyPEM: valid.PrivateKeyPEM,
		})},
		{name: "missing certificate", data: mustMarshalStored(t, storedFile{
			Version: fileVersion, PrivateKeyPEM: valid.PrivateKeyPEM,
		})},
		{name: "missing key", data: mustMarshalStored(t, storedFile{
			Version: fileVersion, CertificatePEM: valid.CertificatePEM,
		})},
		{name: "unknown field", data: []byte(unknownField)},
		{name: "duplicate field", data: []byte(duplicateField)},
		{name: "trailing data", data: trailing},
		{name: "unparseable certificate", data: mustMarshalStored(t, storedFile{
			Version: fileVersion, CertificatePEM: "not a certificate", PrivateKeyPEM: valid.PrivateKeyPEM,
		})},
		{name: "unparseable key", data: mustMarshalStored(t, storedFile{
			Version: fileVersion, CertificatePEM: valid.CertificatePEM, PrivateKeyPEM: "not a key",
		})},
		{name: "mismatched key", data: mustMarshalStored(t, storedFile{
			Version: fileVersion, CertificatePEM: valid.CertificatePEM, PrivateKeyPEM: other.PrivateKeyPEM,
		})},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			path := testPath(t)
			if err := os.WriteFile(path, tc.data, 0o600); err != nil {
				t.Fatalf("write corrupt file: %v", err)
			}
			if _, err := loadOrCreate(ctx, path, now); err == nil {
				t.Fatal("loadOrCreate accepted a corrupt identity file")
			}

			after, err := os.ReadFile(path)
			if err != nil {
				t.Fatalf("read identity file after rejection: %v", err)
			}
			if !bytes.Equal(tc.data, after) {
				t.Fatal("corrupt identity file was silently rebuilt or modified")
			}
		})
	}
}

func TestLoadOrCreateRejectsReadErrorWithoutRebuild(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	ctx := testContext(t)

	// A directory cannot be read as an identity file; this exercises the
	// non-ENOENT read-error path without relying on chmod semantics.
	path := t.TempDir()
	if _, err := loadOrCreate(ctx, path, now); err == nil {
		t.Fatal("loadOrCreate accepted a directory as an identity file")
	}
	info, err := os.Stat(path)
	if err != nil || !info.IsDir() {
		t.Fatalf("directory was removed or replaced after read error: %v", err)
	}
}

func TestLoadOrCreateRejectsInvalidManagedCertificate(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	ctx := testContext(t)

	cases := []struct {
		name string
		edit func(*x509.Certificate)
	}{
		{name: "missing digitalSignature", edit: func(template *x509.Certificate) {
			template.KeyUsage = x509.KeyUsageKeyEncipherment
		}},
		{name: "missing serverAuth", edit: func(template *x509.Certificate) {
			template.ExtKeyUsage = []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth}
		}},
		{name: "unhandled critical extension", edit: func(template *x509.Certificate) {
			template.ExtraExtensions = []pkix.Extension{{
				Id:       asn1.ObjectIdentifier{1, 2, 3, 4},
				Critical: true,
				Value:    []byte{0x05, 0x00},
			}}
		}},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			path := testPath(t)
			key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
			if err != nil {
				t.Fatalf("generate key: %v", err)
			}
			template := &x509.Certificate{
				SerialNumber:          big.NewInt(1),
				Subject:               pkix.Name{CommonName: "hyperconduit"},
				NotBefore:             now.Add(-time.Minute),
				NotAfter:              now.Add(time.Hour),
				KeyUsage:              x509.KeyUsageDigitalSignature,
				ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
				BasicConstraintsValid: true,
			}
			tc.edit(template)
			der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
			if err != nil {
				t.Fatalf("create certificate: %v", err)
			}
			keyPEM, err := marshalPrivateKey(key)
			if err != nil {
				t.Fatalf("marshal key: %v", err)
			}
			stored := storedFile{
				Version:        fileVersion,
				CertificatePEM: string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})),
				PrivateKeyPEM:  string(keyPEM),
			}
			data := mustMarshalStored(t, stored)
			if err := os.WriteFile(path, data, 0o600); err != nil {
				t.Fatalf("write identity file: %v", err)
			}

			if _, err := loadOrCreate(ctx, path, now); err == nil {
				t.Fatal("loadOrCreate accepted a certificate with invalid usage")
			}
			after, err := os.ReadFile(path)
			if err != nil {
				t.Fatalf("read identity file: %v", err)
			}
			if !bytes.Equal(data, after) {
				t.Fatal("invalid certificate was silently replaced")
			}
		})
	}
}

func TestLoadOrCreateConcurrentSamePath(t *testing.T) {
	path := testPath(t)
	const workers = 8

	start := make(chan struct{})
	type result struct {
		cert tls.Certificate
		err  error
	}
	results := make(chan result, workers)

	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			defer cancel()
			cert, err := LoadOrCreate(ctx, path)
			results <- result{cert: cert, err: err}
		}()
	}
	close(start)
	wg.Wait()
	close(results)

	var want string
	for res := range results {
		if res.err != nil {
			t.Fatalf("concurrent LoadOrCreate: %v", res.err)
		}
		if res.cert.Leaf == nil {
			t.Fatal("concurrent LoadOrCreate returned a certificate without Leaf")
		}
		got := Fingerprint(res.cert.Leaf)
		if want == "" {
			want = got
			continue
		}
		if got != want {
			t.Fatalf("concurrent callers observed different identities: %q != %q", got, want)
		}
	}

	stored := readStored(t, path)
	pair, leaf, _, err := validateStored(stored, time.Now())
	if err != nil {
		t.Fatalf("concurrent result is invalid: %v", err)
	}
	if Fingerprint(leaf) != want {
		t.Fatalf("persisted fingerprint %q, want %q", Fingerprint(leaf), want)
	}
	if Fingerprint(pair.Leaf) != want {
		t.Fatalf("returned fingerprint %q, want %q", Fingerprint(pair.Leaf), want)
	}
}

func containsIP(ips []net.IP, want net.IP) bool {
	for _, ip := range ips {
		if ip.Equal(want) {
			return true
		}
	}
	return false
}
