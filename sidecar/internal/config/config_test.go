package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func normalizeOK(t *testing.T, cfg Config, mode string) Config {
	t.Helper()
	got, err := cfg.Normalize(mode)
	if err != nil {
		t.Fatalf("Normalize(%q) returned unexpected error: %v", mode, err)
	}
	return got
}

func writeConfigFile(t *testing.T, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}
	return path
}

func TestNormalizeClientDefaults(t *testing.T) {
	got := normalizeOK(t, Config{Server: "example.com:443"}, ModeClient)

	if got.Listen != defaultListen {
		t.Errorf("Listen = %q, want %q", got.Listen, defaultListen)
	}
	if got.Congestion != "bbr" {
		t.Errorf("Congestion = %q, want bbr", got.Congestion)
	}
	if got.MaxConnections != 64 {
		t.Errorf("MaxConnections = %d, want 64", got.MaxConnections)
	}
	if got.MaxStreams != 16 {
		t.Errorf("MaxStreams = %d, want 16", got.MaxStreams)
	}
	if got.ConnectTimeoutMS != 5000 {
		t.Errorf("ConnectTimeoutMS = %d, want 5000", got.ConnectTimeoutMS)
	}
	if got.RequestTimeoutMS != 5000 {
		t.Errorf("RequestTimeoutMS = %d, want 5000", got.RequestTimeoutMS)
	}
	if got.IdleTimeoutMS != 30000 {
		t.Errorf("IdleTimeoutMS = %d, want 30000", got.IdleTimeoutMS)
	}
	if got.Token != "" {
		t.Errorf("Token = %q, want empty", got.Token)
	}
}

func TestNormalizeServerDefaults(t *testing.T) {
	got := normalizeOK(t, Config{
		Target:   "example.com:25565",
		CertFile: "cert.pem",
		KeyFile:  "key.pem",
	}, ModeServer)

	if got.Listen != defaultListen {
		t.Errorf("Listen = %q, want %q", got.Listen, defaultListen)
	}
	if got.Congestion != "bbr" {
		t.Errorf("Congestion = %q, want bbr", got.Congestion)
	}
	if got.MaxConnections != 64 || got.MaxStreams != 16 {
		t.Errorf("limits = %d/%d, want 64/16", got.MaxConnections, got.MaxStreams)
	}
	if got.ConnectTimeoutMS != 5000 || got.RequestTimeoutMS != 5000 || got.IdleTimeoutMS != 30000 {
		t.Errorf("timeouts = %d/%d/%d, want 5000/5000/30000",
			got.ConnectTimeoutMS, got.RequestTimeoutMS, got.IdleTimeoutMS)
	}
}

func TestNormalizeClientValid(t *testing.T) {
	tests := []struct {
		name string
		cfg  Config
	}{
		{
			name: "minimal hostname server",
			cfg:  Config{Server: "example.com:443"},
		},
		{
			name: "ipv4 loopback listen",
			cfg:  Config{Listen: "127.0.0.1:0", Server: "192.0.2.1:25565"},
		},
		{
			name: "ipv6 loopback listen and ipv6 server",
			cfg:  Config{Listen: "[::1]:0", Server: "[2001:db8::1]:443"},
		},
		{
			name: "full optional fields",
			cfg: Config{
				Listen:           "127.0.0.1:0",
				Server:           "mc.example.com:25565",
				Token:            "token with spaces",
				CAFile:           "ca.pem",
				ServerName:       "mc.example.com",
				Congestion:       "reno",
				UpMbps:           1_000_000,
				DownMbps:         1,
				MaxConnections:   4096,
				MaxStreams:       1024,
				ConnectTimeoutMS: 120000,
				RequestTimeoutMS: 100,
				IdleTimeoutMS:    120000,
			},
		},
		{
			name: "lower bounds",
			cfg: Config{
				Server:           "example.com:1",
				MaxConnections:   1,
				MaxStreams:       8,
				ConnectTimeoutMS: 100,
				RequestTimeoutMS: 100,
				IdleTimeoutMS:    4000,
			},
		},
		{
			name: "empty token is allowed",
			cfg:  Config{Server: "example.com:443", Token: ""},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			normalizeOK(t, tt.cfg, ModeClient)
		})
	}
}

func TestNormalizeClientInvalid(t *testing.T) {
	valid := Config{Server: "example.com:443"}

	tests := []struct {
		name      string
		mode      string
		cfg       Config
		wantField string
	}{
		{name: "hostname listen", cfg: with(valid, func(c *Config) { c.Listen = "localhost:0" }), wantField: "listen"},
		{name: "wildcard ipv4 listen", cfg: with(valid, func(c *Config) { c.Listen = "0.0.0.0:0" }), wantField: "listen"},
		{name: "wildcard ipv6 listen", cfg: with(valid, func(c *Config) { c.Listen = "[::]:0" }), wantField: "listen"},
		{name: "non-loopback listen", cfg: with(valid, func(c *Config) { c.Listen = "192.0.2.1:0" }), wantField: "listen"},
		{name: "non-zero listen port", cfg: with(valid, func(c *Config) { c.Listen = "127.0.0.1:443" }), wantField: "listen"},
		{name: "listen without port", cfg: with(valid, func(c *Config) { c.Listen = "127.0.0.1" }), wantField: "listen"},
		{name: "unbracketed ipv6 listen", cfg: with(valid, func(c *Config) { c.Listen = "::1:0" }), wantField: "listen"},

		{name: "missing server", cfg: Config{}, wantField: "server"},
		{name: "server without port", cfg: Config{Server: "example.com"}, wantField: "server"},
		{name: "server port zero", cfg: Config{Server: "example.com:0"}, wantField: "server"},
		{name: "server port too high", cfg: Config{Server: "example.com:65536"}, wantField: "server"},
		{name: "wildcard ipv4 server", cfg: Config{Server: "0.0.0.0:443"}, wantField: "server"},
		{name: "wildcard ipv6 server", cfg: Config{Server: "[::]:443"}, wantField: "server"},

		{name: "target set", cfg: with(valid, func(c *Config) { c.Target = "example.com:25565" }), wantField: "target"},
		{name: "cert file set", cfg: with(valid, func(c *Config) { c.CertFile = "cert.pem" }), wantField: "certFile"},
		{name: "key file set", cfg: with(valid, func(c *Config) { c.KeyFile = "key.pem" }), wantField: "keyFile"},
		{name: "disable brutal set", cfg: with(valid, func(c *Config) { c.DisableBrutal = true }), wantField: "disableBrutal"},

		{name: "congestion paced", cfg: with(valid, func(c *Config) { c.Congestion = "paced" }), wantField: "congestion"},
		{name: "congestion uppercase", cfg: with(valid, func(c *Config) { c.Congestion = "BBR" }), wantField: "congestion"},
		{name: "max connections low", cfg: with(valid, func(c *Config) { c.MaxConnections = -1 }), wantField: "maxConnections"},
		{name: "max connections high", cfg: with(valid, func(c *Config) { c.MaxConnections = 4097 }), wantField: "maxConnections"},
		{name: "max streams low", cfg: with(valid, func(c *Config) { c.MaxStreams = 7 }), wantField: "maxStreams"},
		{name: "max streams high", cfg: with(valid, func(c *Config) { c.MaxStreams = 1025 }), wantField: "maxStreams"},
		{name: "connect timeout low", cfg: with(valid, func(c *Config) { c.ConnectTimeoutMS = 99 }), wantField: "connectTimeoutMs"},
		{name: "connect timeout high", cfg: with(valid, func(c *Config) { c.ConnectTimeoutMS = 120001 }), wantField: "connectTimeoutMs"},
		{name: "request timeout low", cfg: with(valid, func(c *Config) { c.RequestTimeoutMS = 99 }), wantField: "requestTimeoutMs"},
		{name: "request timeout high", cfg: with(valid, func(c *Config) { c.RequestTimeoutMS = 120001 }), wantField: "requestTimeoutMs"},
		{name: "idle timeout low", cfg: with(valid, func(c *Config) { c.IdleTimeoutMS = 3999 }), wantField: "idleTimeoutMs"},
		{name: "idle timeout high", cfg: with(valid, func(c *Config) { c.IdleTimeoutMS = 120001 }), wantField: "idleTimeoutMs"},
		{name: "up mbps high", cfg: with(valid, func(c *Config) { c.UpMbps = 1_000_001 }), wantField: "upMbps"},
		{name: "down mbps high", cfg: with(valid, func(c *Config) { c.DownMbps = 1_000_001 }), wantField: "downMbps"},

		{name: "token too long", cfg: with(valid, func(c *Config) { c.Token = strings.Repeat("x", 1025) }), wantField: "token"},
		{name: "token contains cr", cfg: with(valid, func(c *Config) { c.Token = "secret\rvalue" }), wantField: "token"},
		{name: "token contains lf", cfg: with(valid, func(c *Config) { c.Token = "secret\nvalue" }), wantField: "token"},

		{name: "invalid mode", mode: "both", cfg: valid, wantField: "mode"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			mode := tt.mode
			if mode == "" {
				mode = ModeClient
			}
			_, err := tt.cfg.Normalize(mode)
			if err == nil {
				t.Fatal("Normalize returned nil error")
			}
			if !strings.Contains(err.Error(), tt.wantField) {
				t.Fatalf("error %q does not mention field %q", err, tt.wantField)
			}
			if tt.cfg.Token != "" && strings.Contains(err.Error(), tt.cfg.Token) {
				t.Fatalf("error leaks token value: %v", err)
			}
		})
	}
}

func TestNormalizeServerValid(t *testing.T) {
	base := Config{
		Target:   "example.com:25565",
		CertFile: "cert.pem",
		KeyFile:  "key.pem",
	}

	tests := []struct {
		name string
		cfg  Config
	}{
		{name: "minimal hostname target", cfg: base},
		{name: "ipv4 target", cfg: with(base, func(c *Config) { c.Target = "192.0.2.10:25565" })},
		{name: "ipv6 target", cfg: with(base, func(c *Config) { c.Target = "[2001:db8::10]:25565" })},
		{name: "wildcard ipv4 listen", cfg: with(base, func(c *Config) { c.Listen = "0.0.0.0:25565" })},
		{name: "wildcard ipv6 listen", cfg: with(base, func(c *Config) { c.Listen = "[::]:25565" })},
		{name: "listen port zero", cfg: with(base, func(c *Config) { c.Listen = "127.0.0.1:0" })},
		{name: "listen port max", cfg: with(base, func(c *Config) { c.Listen = "127.0.0.1:65535" })},
		{
			name: "full optional fields",
			cfg: with(base, func(c *Config) {
				c.Listen = "0.0.0.0:25565"
				c.Token = "server-token"
				c.Congestion = "reno"
				c.UpMbps = 1_000_000
				c.DownMbps = 1_000_000
				c.DisableBrutal = true
				c.MaxConnections = 1
				c.MaxStreams = 1024
				c.ConnectTimeoutMS = 100
				c.RequestTimeoutMS = 120000
				c.IdleTimeoutMS = 4000
			}),
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			normalizeOK(t, tt.cfg, ModeServer)
		})
	}
}

func TestNormalizeServerInvalid(t *testing.T) {
	valid := Config{
		Target:   "example.com:25565",
		CertFile: "cert.pem",
		KeyFile:  "key.pem",
	}

	tests := []struct {
		name      string
		cfg       Config
		wantField string
	}{
		{name: "hostname listen", cfg: with(valid, func(c *Config) { c.Listen = "localhost:25565" }), wantField: "listen"},
		{name: "empty listen host", cfg: with(valid, func(c *Config) { c.Listen = ":25565" }), wantField: "listen"},
		{name: "listen port too high", cfg: with(valid, func(c *Config) { c.Listen = "127.0.0.1:65536" }), wantField: "listen"},
		{name: "listen without port", cfg: with(valid, func(c *Config) { c.Listen = "127.0.0.1" }), wantField: "listen"},

		{name: "server set", cfg: with(valid, func(c *Config) { c.Server = "example.com:443" }), wantField: "server"},
		{name: "missing target", cfg: with(valid, func(c *Config) { c.Target = "" }), wantField: "target"},
		{name: "target port zero", cfg: with(valid, func(c *Config) { c.Target = "example.com:0" }), wantField: "target"},
		{name: "target wildcard ipv4", cfg: with(valid, func(c *Config) { c.Target = "0.0.0.0:25565" }), wantField: "target"},
		{name: "target wildcard ipv6", cfg: with(valid, func(c *Config) { c.Target = "[::]:25565" }), wantField: "target"},
		{name: "target without port", cfg: with(valid, func(c *Config) { c.Target = "example.com" }), wantField: "target"},

		{name: "missing cert file", cfg: with(valid, func(c *Config) { c.CertFile = "" }), wantField: "certFile"},
		{name: "missing key file", cfg: with(valid, func(c *Config) { c.KeyFile = "" }), wantField: "keyFile"},
		{name: "ca file set", cfg: with(valid, func(c *Config) { c.CAFile = "ca.pem" }), wantField: "caFile"},
		{name: "server name set", cfg: with(valid, func(c *Config) { c.ServerName = "example.com" }), wantField: "serverName"},

		{name: "congestion paced", cfg: with(valid, func(c *Config) { c.Congestion = "paced" }), wantField: "congestion"},
		{name: "max connections high", cfg: with(valid, func(c *Config) { c.MaxConnections = 4097 }), wantField: "maxConnections"},
		{name: "token too long", cfg: with(valid, func(c *Config) { c.Token = strings.Repeat("x", 1025) }), wantField: "token"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := tt.cfg.Normalize(ModeServer)
			if err == nil {
				t.Fatal("Normalize returned nil error")
			}
			if !strings.Contains(err.Error(), tt.wantField) {
				t.Fatalf("error %q does not mention field %q", err, tt.wantField)
			}
		})
	}
}

func TestLoadValid(t *testing.T) {
	path := writeConfigFile(t, `{
		"listen": "0.0.0.0:25565",
		"target": "192.0.2.10:25565",
		"certFile": "cert.pem",
		"keyFile": "key.pem",
		"congestion": "reno",
		"upMbps": 100,
		"downMbps": 50,
		"disableBrutal": true,
		"maxConnections": 128,
		"maxStreams": 64,
		"connectTimeoutMs": 1000,
		"requestTimeoutMs": 2000,
		"idleTimeoutMs": 60000,
		"token": "abc"
	}`)

	got, err := Load(path, ModeServer)
	if err != nil {
		t.Fatalf("Load returned unexpected error: %v", err)
	}
	if got.Listen != "0.0.0.0:25565" || got.Target != "192.0.2.10:25565" {
		t.Errorf("addresses = %q/%q", got.Listen, got.Target)
	}
	if got.CertFile != "cert.pem" || got.KeyFile != "key.pem" {
		t.Errorf("cert files = %q/%q", got.CertFile, got.KeyFile)
	}
	if got.Congestion != "reno" || got.UpMbps != 100 || got.DownMbps != 50 {
		t.Errorf("congestion/bandwidth = %q/%d/%d", got.Congestion, got.UpMbps, got.DownMbps)
	}
	if !got.DisableBrutal || got.MaxConnections != 128 || got.MaxStreams != 64 {
		t.Errorf("limits = %t/%d/%d", got.DisableBrutal, got.MaxConnections, got.MaxStreams)
	}
	if got.ConnectTimeoutMS != 1000 || got.RequestTimeoutMS != 2000 || got.IdleTimeoutMS != 60000 {
		t.Errorf("timeouts = %d/%d/%d", got.ConnectTimeoutMS, got.RequestTimeoutMS, got.IdleTimeoutMS)
	}
	if got.Token != "abc" {
		t.Errorf("Token = %q, want abc", got.Token)
	}
}

func TestLoadAppliesDefaults(t *testing.T) {
	got, err := Load(writeConfigFile(t, `{"server":"example.com:443"}`), ModeClient)
	if err != nil {
		t.Fatalf("Load returned unexpected error: %v", err)
	}
	if got.Listen != defaultListen || got.Congestion != "bbr" {
		t.Errorf("defaults = %q/%q", got.Listen, got.Congestion)
	}
	if got.MaxConnections != 64 || got.MaxStreams != 16 {
		t.Errorf("limits = %d/%d", got.MaxConnections, got.MaxStreams)
	}
	if got.ConnectTimeoutMS != 5000 || got.RequestTimeoutMS != 5000 || got.IdleTimeoutMS != 30000 {
		t.Errorf("timeouts = %d/%d/%d", got.ConnectTimeoutMS, got.RequestTimeoutMS, got.IdleTimeoutMS)
	}
}

func TestLoadRejectsHeaderUnsafeToken(t *testing.T) {
	for _, token := range []string{" secret", "secret ", "\tsecret", "secret\x00", "密钥"} {
		_, err := (Config{Server: "example.com:443", Token: token}).Normalize(ModeClient)
		if err == nil || !strings.Contains(err.Error(), "token:") || strings.Contains(err.Error(), token) {
			t.Fatalf("unsafe token was accepted or exposed: %v", err)
		}
	}
}

func TestLoadStrictErrors(t *testing.T) {
	tests := []struct {
		name    string
		content string
		want    string
	}{
		{
			name:    "unknown field",
			content: `{"server":"example.com:443","unknown":1}`,
			want:    "unknown",
		},
		{
			name:    "trailing value",
			content: `{"server":"example.com:443"} {"server":"example.com:443"}`,
			want:    "trailing",
		},
		{
			name:    "trailing garbage",
			content: `{"server":"example.com:443"} x`,
			want:    "trailing",
		},
		{
			name:    "invalid json",
			content: `{"server":`,
			want:    "JSON",
		},
		{
			name:    "empty file",
			content: ``,
			want:    "JSON",
		},
		{
			name:    "oversized file",
			content: `{"server":"example.com:443","token":"` + strings.Repeat("a", maxConfigBytes) + `"}`,
			want:    "exceeds",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := Load(writeConfigFile(t, tt.content), ModeClient)
			if err == nil {
				t.Fatal("Load returned nil error")
			}
			if !strings.Contains(err.Error(), tt.want) {
				t.Fatalf("error %q does not contain %q", err, tt.want)
			}
		})
	}
}

func TestLoadSizeBoundary(t *testing.T) {
	base := `{"server":"example.com:443"}`
	if len(base) > maxConfigBytes {
		t.Fatal("test base is too large")
	}
	content := base + strings.Repeat(" ", maxConfigBytes-len(base))
	if len(content) != maxConfigBytes {
		t.Fatalf("test content length = %d, want %d", len(content), maxConfigBytes)
	}

	if _, err := Load(writeConfigFile(t, content), ModeClient); err != nil {
		t.Fatalf("Load at exact size limit returned error: %v", err)
	}
}

func TestLoadRedactsToken(t *testing.T) {
	const secret = "SUPER_SECRET_TOKEN_123"

	tests := []struct {
		name    string
		content string
	}{
		{
			name:    "unknown field",
			content: `{"server":"example.com:443","token":"` + secret + `","unknown":1}`,
		},
		{
			name:    "invalid token type",
			content: `{"server":"example.com:443","token":123456789}`,
		},
		{
			name:    "truncated json",
			content: `{"server":"example.com:443","token":"` + secret,
		},
		{
			name:    "token with newline",
			content: `{"server":"example.com:443","token":"` + secret + `\nvalue"}`,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := Load(writeConfigFile(t, tt.content), ModeClient)
			if err == nil {
				t.Fatal("Load returned nil error")
			}
			if strings.Contains(err.Error(), secret) {
				t.Fatalf("error leaks token value: %v", err)
			}
		})
	}
}

func TestNormalizeErrorsDoNotEchoValues(t *testing.T) {
	const listen = "not-a-literal-host:1"
	if _, err := (Config{Listen: listen, Server: "example.com:443"}).Normalize(ModeClient); err == nil {
		t.Fatal("expected listen error")
	} else if strings.Contains(err.Error(), listen) {
		t.Fatalf("listen error leaks value: %v", err)
	}

	const target = "secret-target.invalid:0"
	if _, err := (Config{Target: target, CertFile: "cert.pem", KeyFile: "key.pem"}).Normalize(ModeServer); err == nil {
		t.Fatal("expected target error")
	} else if strings.Contains(err.Error(), target) {
		t.Fatalf("target error leaks value: %v", err)
	}

	const server = "secret-server.invalid:0"
	if _, err := (Config{Server: server}).Normalize(ModeClient); err == nil {
		t.Fatal("expected server error")
	} else if strings.Contains(err.Error(), server) {
		t.Fatalf("server error leaks value: %v", err)
	}
}

func TestBandwidthBytesPerSecond(t *testing.T) {
	tests := []struct {
		mbps uint64
		want uint64
	}{
		{mbps: 0, want: 0},
		{mbps: 1, want: 125000},
		{mbps: 8, want: 1000000},
		{mbps: 100, want: 12500000},
		{mbps: 1_000_000, want: 125000000000},
	}

	for _, tt := range tests {
		cfg := Config{UpMbps: tt.mbps, DownMbps: tt.mbps}
		if got := cfg.UpBytesPerSecond(); got != tt.want {
			t.Errorf("UpBytesPerSecond(%d) = %d, want %d", tt.mbps, got, tt.want)
		}
		if got := cfg.DownBytesPerSecond(); got != tt.want {
			t.Errorf("DownBytesPerSecond(%d) = %d, want %d", tt.mbps, got, tt.want)
		}
	}
}

func TestNoPacedCompatibility(t *testing.T) {
	if _, err := (Config{Server: "example.com:443", Congestion: "paced"}).Normalize(ModeClient); err == nil {
		t.Fatal("paced congestion must be rejected")
	} else if !strings.Contains(err.Error(), "congestion") {
		t.Fatalf("error %q does not mention congestion", err)
	}

	path := writeConfigFile(t, `{"server":"example.com:443","congestion":"paced"}`)
	if _, err := Load(path, ModeClient); err == nil {
		t.Fatal("Load accepted paced congestion")
	} else if !strings.Contains(err.Error(), "congestion") {
		t.Fatalf("error %q does not mention congestion", err)
	}
}

func with(cfg Config, mutate func(*Config)) Config {
	mutate(&cfg)
	return cfg
}
