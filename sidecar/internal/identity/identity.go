// Package identity owns the automatically managed TLS identity of this sidecar.
//
// The identity is a single versioned JSON file containing a self-signed ECDSA
// P-256 certificate and its PKCS#8 private key. A missing file is created on
// first use; an existing file is never silently replaced when it is damaged,
// unreadable, or does not contain a matching key pair.
//
// The certificate is valid for one year and is renewed with the same key during
// the final 30 days. This keeps the SPKI fingerprint stable across restarts and
// renewals while still avoiding a long-lived certificate. Callers pin the SPKI
// fingerprint (TOFU), so the short validity window does not require a stable
// certificate serial or issuer.
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
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"math/big"
	"net"
	"os"
	"time"

	"github.com/llll415/HyperConduit/sidecar/internal/privatefile"
)

const (
	// fileVersion is the only supported on-disk schema version.
	fileVersion = 1

	// maxFileBytes bounds the identity file before it is parsed.
	maxFileBytes = 64 << 10

	// certificateValidity is the lifetime of a freshly signed certificate.
	// It is intentionally one year rather than a multi-year default.
	certificateValidity = 365 * 24 * time.Hour

	// renewBefore is how long before expiry a certificate is re-signed with
	// the existing key. The SPKI fingerprint therefore does not change.
	renewBefore = 30 * 24 * time.Hour

	// notBeforeSkew tolerates small clock differences between hosts.
	notBeforeSkew = 5 * time.Minute

	// fixedDNSName is the only DNS SAN on the managed certificate. Callers
	// pin the SPKI, so this name is informational rather than a discovery
	// mechanism.
	fixedDNSName = "hyperconduit.invalid"
)

// storedFile is the versioned JSON representation. PrivateKeyPEM is never
// included in errors or log output.
type storedFile struct {
	Version        int    `json:"version"`
	CertificatePEM string `json:"certificatePem"`
	PrivateKeyPEM  string `json:"privateKeyPem"`
}

// LoadOrCreate returns the managed TLS identity at path, creating it on first
// use and renewing an expiring certificate with the same key. The operation is
// serialized with privatefile.WithLock, and the file is re-read under that lock
// on every call so concurrent processes observe a single identity.
//
// The check is performed at call time. A server that stays up longer than the
// renewal window must call LoadOrCreate again (for example from GetCertificate)
// rather than caching the returned certificate forever.
func LoadOrCreate(ctx context.Context, path string) (tls.Certificate, error) {
	return loadOrCreate(ctx, path, time.Now())
}

// loadOrCreate is the deterministic-time implementation used by tests.
func loadOrCreate(ctx context.Context, path string, now time.Time) (tls.Certificate, error) {
	var result tls.Certificate
	// privatefile.WithLock expects the full path of the lock file, not the
	// data file. Keep the lock next to the identity so all processes that use
	// the same identity contend on the same lock.
	err := privatefile.WithLock(ctx, path+".lock", func() error {
		cert, err := loadOrCreateLocked(path, now)
		if err != nil {
			return err
		}
		result = cert
		return nil
	})
	if err != nil {
		return tls.Certificate{}, err
	}
	return result, nil
}

func loadOrCreateLocked(path string, now time.Time) (tls.Certificate, error) {
	data, err := privatefile.Read(path, maxFileBytes)
	if err != nil {
		if isNotExist(err) {
			return createAndSave(path, now)
		}
		return tls.Certificate{}, fmt.Errorf("identity: cannot read identity file: %w", err)
	}
	if len(data) > maxFileBytes {
		return tls.Certificate{}, fmt.Errorf("identity: identity file exceeds %d bytes", maxFileBytes)
	}

	stored, err := decodeStored(data)
	if err != nil {
		return tls.Certificate{}, err
	}

	pair, leaf, key, err := validateStored(stored, now)
	if err != nil {
		return tls.Certificate{}, err
	}

	if needsRenewal(leaf, now) {
		return renewAndSave(path, key, now)
	}
	return pair, nil
}

// Fingerprint returns the TOFU identity of a certificate: the SHA-256 digest of
// its DER-encoded SubjectPublicKeyInfo, prefixed with "SHA256:" and encoded with
// unpadded standard base64.
func Fingerprint(cert *x509.Certificate) string {
	if cert == nil {
		return ""
	}
	sum := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
	return "SHA256:" + base64.RawStdEncoding.EncodeToString(sum[:])
}

func createAndSave(path string, now time.Time) (tls.Certificate, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, fmt.Errorf("identity: cannot generate key: %w", err)
	}
	certPEM, leaf, err := newCertificate(key, now)
	if err != nil {
		return tls.Certificate{}, err
	}
	keyPEM, err := marshalPrivateKey(key)
	if err != nil {
		return tls.Certificate{}, err
	}
	return savePair(path, certPEM, keyPEM, leaf)
}

func renewAndSave(path string, key *ecdsa.PrivateKey, now time.Time) (tls.Certificate, error) {
	certPEM, leaf, err := newCertificate(key, now)
	if err != nil {
		return tls.Certificate{}, err
	}
	keyPEM, err := marshalPrivateKey(key)
	if err != nil {
		return tls.Certificate{}, err
	}
	return savePair(path, certPEM, keyPEM, leaf)
}

func savePair(path string, certPEM, keyPEM []byte, leaf *x509.Certificate) (tls.Certificate, error) {
	stored := storedFile{
		Version:        fileVersion,
		CertificatePEM: string(certPEM),
		PrivateKeyPEM:  string(keyPEM),
	}
	data, err := json.Marshal(stored)
	if err != nil {
		return tls.Certificate{}, errors.New("identity: cannot encode identity file")
	}
	if len(data) > maxFileBytes {
		return tls.Certificate{}, fmt.Errorf("identity: identity file would exceed %d bytes", maxFileBytes)
	}
	if err := privatefile.Write(path, data); err != nil {
		return tls.Certificate{}, fmt.Errorf("identity: cannot write identity file: %w", err)
	}

	pair, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return tls.Certificate{}, errors.New("identity: generated certificate and key do not match")
	}
	pair.Leaf = leaf
	return pair, nil
}

// newCertificate signs a self-signed leaf certificate with key. The same key
// can be passed again during renewal; only the certificate and serial change.
func newCertificate(key *ecdsa.PrivateKey, now time.Time) ([]byte, *x509.Certificate, error) {
	if key == nil || key.Curve != elliptic.P256() {
		return nil, nil, errors.New("identity: managed key must be ECDSA P-256")
	}

	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, nil, fmt.Errorf("identity: cannot generate serial number: %w", err)
	}
	if serial.Sign() == 0 {
		serial = big.NewInt(1)
	}

	template := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "hyperconduit"},
		NotBefore:             now.Add(-notBeforeSkew),
		NotAfter:              now.Add(certificateValidity),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IsCA:                  false,
		DNSNames:              []string{fixedDNSName},
		IPAddresses:           []net.IP{net.IPv4(127, 0, 0, 1), net.IPv6loopback},
	}

	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		return nil, nil, fmt.Errorf("identity: cannot create certificate: %w", err)
	}
	leaf, err := x509.ParseCertificate(der)
	if err != nil {
		return nil, nil, fmt.Errorf("identity: cannot parse generated certificate: %w", err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), leaf, nil
}

func marshalPrivateKey(key *ecdsa.PrivateKey) ([]byte, error) {
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return nil, fmt.Errorf("identity: cannot marshal private key: %w", err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), nil
}

func decodeStored(data []byte) (storedFile, error) {
	// encoding/json resolves duplicate object keys by keeping the last value.
	// The identity schema is flat, so rejecting duplicate top-level keys here
	// is enough to avoid that ambiguity until a shared strict JSON helper exists.
	if err := rejectDuplicateTopLevelKeys(data); err != nil {
		return storedFile{}, err
	}

	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()

	var stored storedFile
	if err := dec.Decode(&stored); err != nil {
		return storedFile{}, describeJSONError(err)
	}
	if err := dec.Decode(new(any)); err != io.EOF {
		return storedFile{}, errors.New("identity: trailing data after JSON value")
	}
	if stored.Version == 0 {
		return storedFile{}, errors.New("identity: version is required")
	}
	if stored.Version != fileVersion {
		return storedFile{}, fmt.Errorf("identity: unsupported version %d", stored.Version)
	}
	if stored.CertificatePEM == "" || stored.PrivateKeyPEM == "" {
		return storedFile{}, errors.New("identity: certificate or private key is missing")
	}
	return stored, nil
}

// rejectDuplicateTopLevelKeys scans the first JSON object and rejects repeated
// keys. Parse errors are left to the strict struct decoder, which produces a
// more precise message; this helper only reports duplicates.
func rejectDuplicateTopLevelKeys(data []byte) error {
	dec := json.NewDecoder(bytes.NewReader(data))
	token, err := dec.Token()
	if err != nil {
		return nil
	}
	open, ok := token.(json.Delim)
	if !ok || open != '{' {
		return nil
	}

	seen := make(map[string]struct{})
	for dec.More() {
		token, err := dec.Token()
		if err != nil {
			return nil
		}
		key, ok := token.(string)
		if !ok {
			return nil
		}
		if _, exists := seen[key]; exists {
			return errors.New("identity: duplicate JSON field")
		}
		seen[key] = struct{}{}

		var value json.RawMessage
		if err := dec.Decode(&value); err != nil {
			return nil
		}
	}
	return nil
}

// describeJSONError avoids echoing file contents, like config.Load does.
func describeJSONError(err error) error {
	if errors.Is(err, io.EOF) {
		return errors.New("identity: JSON value is missing")
	}

	var typeErr *json.UnmarshalTypeError
	if errors.As(err, &typeErr) {
		if typeErr.Field != "" {
			return fmt.Errorf("identity: invalid JSON type for field %s", typeErr.Field)
		}
		return errors.New("identity: invalid JSON type")
	}

	var syntaxErr *json.SyntaxError
	if errors.As(err, &syntaxErr) {
		return errors.New("identity: invalid JSON syntax")
	}

	return errors.New("identity: invalid JSON or unknown field")
}

// validateStored checks every property that the automatic manager relies on.
// It returns the loaded pair, parsed leaf, and key for optional renewal.
func validateStored(stored storedFile, now time.Time) (tls.Certificate, *x509.Certificate, *ecdsa.PrivateKey, error) {
	certPEM := []byte(stored.CertificatePEM)
	keyPEM := []byte(stored.PrivateKeyPEM)

	pair, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return tls.Certificate{}, nil, nil, errors.New("identity: certificate and key are invalid or do not match")
	}
	if len(pair.Certificate) != 1 {
		return tls.Certificate{}, nil, nil, errors.New("identity: managed file must contain exactly one certificate")
	}

	leaf, err := x509.ParseCertificate(pair.Certificate[0])
	if err != nil {
		return tls.Certificate{}, nil, nil, errors.New("identity: cannot parse certificate")
	}

	key, ok := pair.PrivateKey.(*ecdsa.PrivateKey)
	if !ok || key.Curve != elliptic.P256() {
		return tls.Certificate{}, nil, nil, errors.New("identity: managed key must be ECDSA P-256")
	}
	pub, ok := leaf.PublicKey.(*ecdsa.PublicKey)
	if !ok || pub.Curve != elliptic.P256() {
		return tls.Certificate{}, nil, nil, errors.New("identity: managed certificate must use ECDSA P-256")
	}

	if !bytes.Equal(leaf.RawIssuer, leaf.RawSubject) {
		return tls.Certificate{}, nil, nil, errors.New("identity: certificate is not self-signed")
	}
	if err := leaf.CheckSignature(leaf.SignatureAlgorithm, leaf.RawTBSCertificate, leaf.Signature); err != nil {
		return tls.Certificate{}, nil, nil, errors.New("identity: certificate self-signature is invalid")
	}
	if leaf.KeyUsage&x509.KeyUsageDigitalSignature == 0 {
		return tls.Certificate{}, nil, nil, errors.New("identity: certificate is missing digitalSignature key usage")
	}
	if !hasServerAuth(leaf.ExtKeyUsage) {
		return tls.Certificate{}, nil, nil, errors.New("identity: certificate is missing serverAuth extended key usage")
	}
	if len(leaf.UnhandledCriticalExtensions) != 0 {
		return tls.Certificate{}, nil, nil, errors.New("identity: certificate contains unhandled critical extensions")
	}

	if leaf.NotAfter.IsZero() || !leaf.NotAfter.After(leaf.NotBefore) {
		return tls.Certificate{}, nil, nil, errors.New("identity: certificate validity period is invalid")
	}
	if now.Before(leaf.NotBefore) {
		return tls.Certificate{}, nil, nil, errors.New("identity: certificate is not valid yet")
	}

	pair.Leaf = leaf
	return pair, leaf, key, nil
}

func hasServerAuth(usages []x509.ExtKeyUsage) bool {
	for _, usage := range usages {
		if usage == x509.ExtKeyUsageServerAuth {
			return true
		}
	}
	return false
}

func needsRenewal(leaf *x509.Certificate, now time.Time) bool {
	return !leaf.NotAfter.After(now.Add(renewBefore))
}

func isNotExist(err error) bool {
	return errors.Is(err, fs.ErrNotExist) || os.IsNotExist(err)
}
