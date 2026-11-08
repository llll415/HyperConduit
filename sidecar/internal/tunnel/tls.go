package tunnel

import (
	"crypto/x509"
	"errors"
	"os"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/llll415/HyperConduit/sidecar/internal/config"
)

func clientTLS(c config.Config) (client.TLSConfig, error) {
	tlsConfig := client.TLSConfig{ServerName: c.ServerName}
	if c.CAFile == "" {
		return tlsConfig, nil // Standard certificate validation against system roots.
	}
	pem, err := os.ReadFile(c.CAFile)
	if err != nil {
		return tlsConfig, errors.New("cannot read caFile")
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(pem) {
		return tlsConfig, errors.New("caFile contains no certificates")
	}
	tlsConfig.RootCAs = roots
	return tlsConfig, nil
}
