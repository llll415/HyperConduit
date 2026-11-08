package tunnel

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"os"
	"sync"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/llll415/HyperConduit/sidecar/internal/config"
	"github.com/llll415/HyperConduit/sidecar/internal/identity"
	"github.com/llll415/HyperConduit/sidecar/internal/trust"
)

type trustSession struct {
	mu       sync.Mutex
	pin      string
	store    *trust.Store
	endpoint string
}

func (s *trustSession) commit(ctx context.Context) error {
	if s == nil {
		return nil
	}
	s.mu.Lock()
	pin := s.pin
	s.mu.Unlock()
	if pin == "" {
		return errors.New("server identity missing")
	}
	return s.store.Commit(ctx, s.endpoint, pin)
}

func clientTLS(ctx context.Context, c config.Config) (client.TLSConfig, *trustSession, error) {
	tlsConfig := client.TLSConfig{ServerName: c.ServerName}
	if c.CAFile == "" {
		s := &trustSession{store: trust.New(c.KnownServersFile), endpoint: c.Server}
		// Replace CA/hostname checks with SPKI verification, not unauthenticated
		// TLS. Go still verifies the TLS CertificateVerify signature/key proof.
		tlsConfig.InsecureSkipVerify = true
		tlsConfig.VerifyConnection = func(state tls.ConnectionState) (verifyErr error) {
			defer func() {
				if verifyErr == nil {
					return
				}
				code := classifyCode(verifyErr)
				if code == CodeTransport {
					code = CodeTLS
				}
				verifyErr = &Error{Code: code, Op: "verify", Err: verifyErr}
			}()
			if len(state.PeerCertificates) == 0 {
				return errors.New("server certificate missing")
			}
			cert := state.PeerCertificates[0]
			now := time.Now()
			if now.Before(cert.NotBefore) || !now.Before(cert.NotAfter) {
				return errors.New("server certificate outside validity period")
			}
			if cert.KeyUsage != 0 && cert.KeyUsage&x509.KeyUsageDigitalSignature == 0 {
				return errors.New("server certificate cannot sign")
			}
			if len(cert.UnhandledCriticalExtensions) > 0 {
				return errors.New("unsupported certificate extension")
			}
			if len(cert.ExtKeyUsage) > 0 {
				allowed := false
				for _, usage := range cert.ExtKeyUsage {
					if usage == x509.ExtKeyUsageServerAuth || usage == x509.ExtKeyUsageAny {
						allowed = true
					}
				}
				if !allowed {
					return errors.New("certificate not valid for server authentication")
				}
			}
			pin := identity.Fingerprint(cert)
			if err := s.store.Check(ctx, s.endpoint, pin); err != nil {
				return err
			}
			s.mu.Lock()
			s.pin = pin
			s.mu.Unlock()
			return nil
		}
		return tlsConfig, s, nil
	}
	pem, err := os.ReadFile(c.CAFile)
	if err != nil {
		return tlsConfig, nil, errors.New("cannot read caFile")
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(pem) {
		return tlsConfig, nil, errors.New("caFile contains no certificates")
	}
	tlsConfig.RootCAs = roots
	return tlsConfig, nil, nil
}

// Managed identities are rechecked at most daily and renewed before expiry,
// including when a server runs longer than a certificate's lifetime.
func managedCertificate(path string, initial tls.Certificate) func(*tls.ClientHelloInfo) (*tls.Certificate, error) {
	var mu sync.Mutex
	cached := initial
	checked := time.Now()
	return func(hello *tls.ClientHelloInfo) (*tls.Certificate, error) {
		mu.Lock()
		defer mu.Unlock()
		now := time.Now()
		if now.Sub(checked) >= 24*time.Hour || cached.Leaf == nil || now.Add(30*24*time.Hour).After(cached.Leaf.NotAfter) {
			ctx, cancel := context.WithTimeout(hello.Context(), 5*time.Second)
			defer cancel()
			cert, err := identity.LoadOrCreate(ctx, path)
			if err != nil {
				return nil, errors.New("managed identity unavailable")
			}
			cached = cert
			checked = now
		}
		result := cached
		return &result, nil
	}
}
