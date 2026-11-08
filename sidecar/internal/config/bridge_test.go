package config

import (
	"strings"
	"testing"
)

func TestBridgeCredentialAndEndpointScope(t *testing.T) {
	valid := strings.Repeat("a", 64)
	for _, tc := range []struct {
		mode string
		cfg  Config
		ok   bool
	}{
		{"server", Config{Target: "127.0.0.1:25565", BridgeToken: valid}, true},
		{"server", Config{Target: "127.0.0.1:25565", BridgeToken: "secret"}, false},
		{"server", Config{Target: "127.0.0.1:25565", ServerEndpoint: "mc.example:25565"}, false},
		{"client", Config{Server: "127.0.0.1:25565", BridgeToken: valid}, false},
		{"client", Config{Server: "127.0.0.1:25565", ServerEndpoint: "mc.example:25565"}, true},
		{"client", Config{Server: "127.0.0.1:25565", ServerEndpoint: "invalid"}, false},
	} {
		_, err := tc.cfg.Normalize(tc.mode)
		if (err == nil) != tc.ok {
			t.Fatalf("bridge mode %s accepted=%v, error=%v", tc.mode, err == nil, err)
		}
	}
}
