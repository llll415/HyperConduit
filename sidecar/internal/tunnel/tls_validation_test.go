package tunnel

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/asn1"
	"path/filepath"
	"testing"
	"time"

	"github.com/llll415/HyperConduit/sidecar/internal/config"
)

func TestTOFUCertificateConstraints(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func(*x509.Certificate)
	}{
		{"expired", func(c *x509.Certificate) { c.NotAfter = time.Now().Add(-time.Second) }},
		{"future", func(c *x509.Certificate) { c.NotBefore = time.Now().Add(time.Hour) }},
		{"wrong usage", func(c *x509.Certificate) { c.ExtKeyUsage = []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth} }},
		{"unknown critical", func(c *x509.Certificate) { c.UnhandledCriticalExtensions = []asn1.ObjectIdentifier{{1, 2, 3, 4}} }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			id := newTestIdentity(t, "localhost")
			cert := *parseCertificateFile(t, id.certFile)
			tc.mutate(&cert)
			cfg, err := (config.Config{Server: "127.0.0.1:25565", StateDir: t.TempDir()}).Normalize("client")
			if err != nil {
				t.Fatal(err)
			}
			tcConfig, _, err := clientTLS(context.Background(), cfg)
			if err != nil {
				t.Fatal(err)
			}
			err = tcConfig.VerifyConnection(tls.ConnectionState{PeerCertificates: []*x509.Certificate{&cert}})
			requireErrorCode(t, err, CodeTLS)
			requireMissingFile(t, filepath.Join(cfg.StateDir, "known-servers.json"))
		})
	}
}

func TestTOFUCheckDoesNotCommit(t *testing.T) {
	id := newTestIdentity(t, "localhost")
	cert := parseCertificateFile(t, id.certFile)
	cfg, err := (config.Config{Server: "127.0.0.1:25565", StateDir: t.TempDir()}).Normalize("client")
	if err != nil {
		t.Fatal(err)
	}
	tc, _, err := clientTLS(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := tc.VerifyConnection(tls.ConnectionState{PeerCertificates: []*x509.Certificate{cert}}); err != nil {
		t.Fatal(err)
	}
	requireMissingFile(t, cfg.KnownServersFile)
}
