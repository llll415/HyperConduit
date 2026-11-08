package server

import (
	"crypto/tls"
	"net"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/apernet/hysteria/core/v2/errors"
)

// testConfigOutbound is a minimal Outbound for config validation tests.
// None of its methods are expected to be called by fill().
type testConfigOutbound struct{}

func (testConfigOutbound) TCP(reqAddr string) (net.Conn, error) {
	return nil, errors.ConfigError{Field: "Outbound", Reason: "not available in tests"}
}

func (testConfigOutbound) UDP(reqAddr string) (UDPConn, error) {
	return nil, errors.ConfigError{Field: "Outbound", Reason: "not available in tests"}
}

func (testConfigOutbound) CheckUDP(reqAddr string) error {
	return nil
}

type testConfigAuthenticator struct{}

func (testConfigAuthenticator) Authenticate(addr net.Addr, auth string, tx uint64) (bool, string) {
	return false, ""
}

// newValidTestConfig returns a Config that passes fill(), backed by a real
// loopback packet conn that is closed automatically after the test.
func newValidTestConfig(t *testing.T) *Config {
	t.Helper()
	conn, err := net.ListenPacket("udp", "127.0.0.1:0")
	require.NoError(t, err)
	t.Cleanup(func() { _ = conn.Close() })
	return &Config{
		TLSConfig: TLSConfig{
			// fill() only checks that at least one certificate is present.
			Certificates: []tls.Certificate{{}},
		},
		Conn:          conn,
		Outbound:      testConfigOutbound{},
		Authenticator: testConfigAuthenticator{},
	}
}

// assertConfigError asserts that err is a ConfigError for the given field.
func assertConfigError(t *testing.T, err error, field string) {
	t.Helper()
	var configErr errors.ConfigError
	require.ErrorAs(t, err, &configErr)
	assert.Equal(t, field, configErr.Field)
}

// TestConfigFillRequiresOutbound makes sure a server can no longer silently
// fall back to dialing arbitrary destinations.
func TestConfigFillRequiresOutbound(t *testing.T) {
	c := newValidTestConfig(t)
	c.Outbound = nil

	err := c.fill()
	require.Error(t, err)
	assertConfigError(t, err, "Outbound")
}

func TestConfigFillRejectsNegativeMaxConnections(t *testing.T) {
	c := newValidTestConfig(t)
	c.MaxConnections = -1

	err := c.fill()
	require.Error(t, err)
	assertConfigError(t, err, "MaxConnections")
}

func TestConfigFillRejectsNegativeRequestTimeout(t *testing.T) {
	c := newValidTestConfig(t)
	c.RequestTimeout = -time.Second

	err := c.fill()
	require.Error(t, err)
	assertConfigError(t, err, "RequestTimeout")
}

// TestConfigFillAcceptsZeroAndPositiveLimits verifies the new fields are
// optional: zero means unlimited/disabled and positive values are accepted.
func TestConfigFillAcceptsZeroAndPositiveLimits(t *testing.T) {
	c := newValidTestConfig(t)
	require.NoError(t, c.fill())
	assert.Zero(t, c.MaxConnections)
	assert.Zero(t, c.RequestTimeout)

	c = newValidTestConfig(t)
	c.MaxConnections = 64
	c.RequestTimeout = 5 * time.Second
	require.NoError(t, c.fill())
	assert.Equal(t, 64, c.MaxConnections)
	assert.Equal(t, 5*time.Second, c.RequestTimeout)
}
