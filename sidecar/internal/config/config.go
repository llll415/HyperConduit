// Package config loads and validates the HyperConduit sidecar configuration.
package config

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/llll415/HyperConduit/sidecar/internal/privatefile"
)

const (
	// ModeClient is the client-side sidecar mode.
	ModeClient = "client"
	// ModeServer is the server-side sidecar mode.
	ModeServer = "server"

	maxConfigBytes   = 64 << 10
	maxTokenBytes    = 1024
	maxBandwidthMbps = 1_000_000

	defaultListen           = "127.0.0.1:0"
	defaultCongestion       = "bbr"
	defaultMaxConnections   = 64
	defaultMaxStreams       = 16
	defaultConnectTimeoutMS = 5000
	defaultRequestTimeoutMS = 5000
	defaultIdleTimeoutMS    = 30000
)

// Config is the on-disk sidecar configuration. Field names use camelCase JSON
// keys and are shared by the client and server modes; mode-specific fields are
// validated by Normalize.
type Config struct {
	Listen           string `json:"listen"`
	Server           string `json:"server"`
	Target           string `json:"target"`
	Token            string `json:"token,omitempty"`
	BridgeToken      string `json:"bridgeToken,omitempty"`
	ServerEndpoint   string `json:"serverEndpoint,omitempty"`
	CertFile         string `json:"certFile"`
	KeyFile          string `json:"keyFile"`
	CAFile           string `json:"caFile"`
	ServerName       string `json:"serverName"`
	StateDir         string `json:"stateDir"`
	IdentityFile     string `json:"identityFile"`
	KnownServersFile string `json:"knownServersFile"`
	Congestion       string `json:"congestion"`
	UpMbps           uint64 `json:"upMbps"`
	DownMbps         uint64 `json:"downMbps"`
	DisableBrutal    bool   `json:"disableBrutal"`
	MaxConnections   int    `json:"maxConnections"`
	MaxStreams       int64  `json:"maxStreams"`
	ConnectTimeoutMS int    `json:"connectTimeoutMs"`
	RequestTimeoutMS int    `json:"requestTimeoutMs"`
	IdleTimeoutMS    int    `json:"idleTimeoutMs"`
}

// Load reads a strict JSON configuration file, applies defaults, and validates
// it for the given mode. The file size is bounded and unknown fields, trailing
// data, and malformed JSON are rejected. Errors never include the file contents
// or the token value.
func Load(path, mode string) (Config, error) {
	// Configurations may contain tokens. Use the same protected regular-file
	// reader as identity storage, including restrictive Windows DACLs.
	data, err := privatefile.Read(path, maxConfigBytes+1)
	if err != nil {
		return Config{}, fmt.Errorf("config: cannot read file: %w", err)
	}
	if len(data) > maxConfigBytes {
		return Config{}, fmt.Errorf("config: file exceeds %d bytes", maxConfigBytes)
	}

	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()

	var cfg Config
	if err := dec.Decode(&cfg); err != nil {
		return Config{}, describeJSONError(err)
	}
	if err := dec.Decode(new(any)); err != io.EOF {
		return Config{}, errors.New("config: trailing data after JSON value")
	}

	return cfg.Normalize(mode)
}

// describeJSONError converts encoding/json errors into messages that do not
// echo configuration values.
func describeJSONError(err error) error {
	if errors.Is(err, io.EOF) {
		return errors.New("config: JSON value is missing")
	}

	var typeErr *json.UnmarshalTypeError
	if errors.As(err, &typeErr) {
		if typeErr.Field != "" {
			return fmt.Errorf("config: invalid JSON type for field %s", typeErr.Field)
		}
		return errors.New("config: invalid JSON type")
	}

	var syntaxErr *json.SyntaxError
	if errors.As(err, &syntaxErr) {
		return errors.New("config: invalid JSON syntax")
	}

	// Unknown field names are user-controlled too; do not echo them into logs.
	return errors.New("config: invalid JSON or unknown field")
}

// Normalize applies mode-specific defaults and validates the configuration.
// Error messages contain only the JSON field name and the reason; values are
// never included.
func (c Config) Normalize(mode string) (Config, error) {
	switch mode {
	case ModeClient:
		return c.normalizeClient()
	case ModeServer:
		return c.normalizeServer()
	default:
		return Config{}, errors.New("mode: must be client or server")
	}
}

func (c Config) normalizeClient() (Config, error) {
	c = c.withDefaults()

	if err := c.validateCommon(); err != nil {
		return Config{}, err
	}
	if err := validateClientListen(c.Listen); err != nil {
		return Config{}, err
	}
	if err := validateDialAddress("server", c.Server); err != nil {
		return Config{}, err
	}
	if c.Target != "" {
		return Config{}, errors.New("target: must be empty in client mode")
	}
	if c.CertFile != "" {
		return Config{}, errors.New("certFile: must be empty in client mode")
	}
	if c.KeyFile != "" {
		return Config{}, errors.New("keyFile: must be empty in client mode")
	}
	if c.IdentityFile != "" {
		return Config{}, errors.New("identityFile: only available in server mode")
	}
	if c.BridgeToken != "" {
		return Config{}, errors.New("bridgeToken: only available in server mode")
	}
	if c.ServerEndpoint != "" {
		if err := validateDialAddress("serverEndpoint", c.ServerEndpoint); err != nil {
			return Config{}, err
		}
	}
	if c.CAFile != "" && c.KnownServersFile != "" {
		return Config{}, errors.New("caFile: cannot be combined with knownServersFile")
	}
	if c.CAFile == "" && c.ServerName != "" {
		return Config{}, errors.New("serverName: only used with caFile")
	}
	if c.CAFile == "" && c.KnownServersFile == "" {
		c.KnownServersFile = filepath.Join(c.StateDir, "known-servers.json")
	}
	if c.DisableBrutal {
		return Config{}, errors.New("disableBrutal: must be false in client mode")
	}

	return c, nil
}

func (c Config) normalizeServer() (Config, error) {
	c = c.withDefaults()

	if err := c.validateCommon(); err != nil {
		return Config{}, err
	}
	if err := validateServerListen(c.Listen); err != nil {
		return Config{}, err
	}
	if c.Server != "" {
		return Config{}, errors.New("server: must be empty in server mode")
	}
	if c.ServerEndpoint != "" {
		return Config{}, errors.New("serverEndpoint: only available in client mode")
	}
	if c.BridgeToken != "" {
		if len(c.BridgeToken) != 64 {
			return Config{}, errors.New("bridgeToken: invalid bridge credential")
		}
		for _, b := range c.BridgeToken {
			if !strings.ContainsRune("0123456789abcdef", b) {
				return Config{}, errors.New("bridgeToken: invalid bridge credential")
			}
		}
	}
	if err := validateDialAddress("target", c.Target); err != nil {
		return Config{}, err
	}
	if (c.CertFile == "") != (c.KeyFile == "") {
		return Config{}, errors.New("certFile/keyFile: must be provided together")
	}
	if c.CertFile != "" && c.IdentityFile != "" {
		return Config{}, errors.New("identityFile: cannot be combined with certFile/keyFile")
	}
	if c.CertFile == "" && c.IdentityFile == "" {
		c.IdentityFile = filepath.Join(c.StateDir, "server-identity.json")
	}
	if c.KnownServersFile != "" {
		return Config{}, errors.New("knownServersFile: only available in client mode")
	}
	if c.CAFile != "" {
		return Config{}, errors.New("caFile: must be empty in server mode")
	}
	if c.ServerName != "" {
		return Config{}, errors.New("serverName: must be empty in server mode")
	}

	return c, nil
}

func (c Config) withDefaults() Config {
	if c.StateDir == "" {
		c.StateDir = filepath.Join("config", "hyperconduit")
	}
	if c.Listen == "" {
		c.Listen = defaultListen
	}
	if c.Congestion == "" {
		c.Congestion = defaultCongestion
	}
	if c.MaxConnections == 0 {
		c.MaxConnections = defaultMaxConnections
	}
	if c.MaxStreams == 0 {
		c.MaxStreams = defaultMaxStreams
	}
	if c.ConnectTimeoutMS == 0 {
		c.ConnectTimeoutMS = defaultConnectTimeoutMS
	}
	if c.RequestTimeoutMS == 0 {
		c.RequestTimeoutMS = defaultRequestTimeoutMS
	}
	if c.IdleTimeoutMS == 0 {
		c.IdleTimeoutMS = defaultIdleTimeoutMS
	}
	return c
}

func (c Config) validateCommon() error {
	if c.Congestion != "bbr" && c.Congestion != "reno" {
		return errors.New("congestion: must be bbr or reno")
	}
	if c.MaxConnections < 1 || c.MaxConnections > 4096 {
		return errors.New("maxConnections: must be between 1 and 4096")
	}
	if c.MaxStreams < 8 || c.MaxStreams > 1024 {
		return errors.New("maxStreams: must be between 8 and 1024")
	}
	if c.ConnectTimeoutMS < 100 || c.ConnectTimeoutMS > 120000 {
		return errors.New("connectTimeoutMs: must be between 100 and 120000")
	}
	if c.RequestTimeoutMS < 100 || c.RequestTimeoutMS > 120000 {
		return errors.New("requestTimeoutMs: must be between 100 and 120000")
	}
	if c.IdleTimeoutMS < 4000 || c.IdleTimeoutMS > 120000 {
		return errors.New("idleTimeoutMs: must be between 4000 and 120000")
	}
	if c.UpMbps > maxBandwidthMbps {
		return errors.New("upMbps: must be between 0 and 1000000")
	}
	if c.DownMbps > maxBandwidthMbps {
		return errors.New("downMbps: must be between 0 and 1000000")
	}
	if len(c.Token) > maxTokenBytes {
		return errors.New("token: must be at most 1024 bytes")
	}
	if strings.ContainsAny(c.Token, "\r\n") {
		return errors.New("token: must not contain CR or LF")
	}
	if strings.TrimSpace(c.Token) != c.Token {
		return errors.New("token: must not have surrounding whitespace")
	}
	for _, b := range []byte(c.Token) {
		if b < 0x20 || b > 0x7e {
			return errors.New("token: must contain printable ASCII only")
		}
	}
	return nil
}

func validateClientListen(addr string) error {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return errors.New("listen: must be a literal loopback IP address with port 0")
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return errors.New("listen: must be a literal loopback IP address")
	}
	p, err := strconv.Atoi(port)
	if err != nil || p != 0 {
		return errors.New("listen: port must be 0")
	}
	return nil
}

func validateServerListen(addr string) error {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return errors.New("listen: must be an IP address and port")
	}
	if net.ParseIP(host) == nil {
		return errors.New("listen: host must be an IP address literal")
	}
	p, err := strconv.Atoi(port)
	if err != nil || p < 0 || p > 65535 {
		return errors.New("listen: port must be between 0 and 65535")
	}
	return nil
}

func validateDialAddress(field, addr string) error {
	host, port, err := net.SplitHostPort(addr)
	if err != nil || host == "" {
		return fmt.Errorf("%s: must be a valid host:port", field)
	}
	p, err := strconv.Atoi(port)
	if err != nil || p < 1 || p > 65535 {
		return fmt.Errorf("%s: port must be between 1 and 65535", field)
	}
	if strings.ContainsAny(host, " \t\r\n/") {
		return fmt.Errorf("%s: host is not valid", field)
	}
	if ip := net.ParseIP(host); ip != nil && ip.IsUnspecified() {
		return fmt.Errorf("%s: host must not be a wildcard address", field)
	}
	return nil
}

// UpBytesPerSecond returns the configured upload bandwidth in bytes per second.
func (c Config) UpBytesPerSecond() uint64 {
	return c.UpMbps * 1_000_000 / 8
}

// DownBytesPerSecond returns the configured download bandwidth in bytes per second.
func (c Config) DownBytesPerSecond() uint64 {
	return c.DownMbps * 1_000_000 / 8
}
