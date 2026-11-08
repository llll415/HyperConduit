package config

import (
	"path/filepath"
	"testing"
)

func TestAutomaticIdentityDefaults(t *testing.T) {
	s, err := (Config{Target: "127.0.0.1:25565"}).Normalize(ModeServer)
	if err != nil {
		t.Fatal(err)
	}
	if s.IdentityFile != filepath.Join("config", "hyperconduit", "server-identity.json") {
		t.Fatal(s.IdentityFile)
	}
	c, err := (Config{Server: "127.0.0.1:25565"}).Normalize(ModeClient)
	if err != nil {
		t.Fatal(err)
	}
	if c.KnownServersFile != filepath.Join("config", "hyperconduit", "known-servers.json") {
		t.Fatal(c.KnownServersFile)
	}
	if _, err := c.Normalize(ModeClient); err != nil {
		t.Fatal("normalization not idempotent", err)
	}
	if _, err := s.Normalize(ModeServer); err != nil {
		t.Fatal("normalization not idempotent", err)
	}
}

func TestIdentityModeConflicts(t *testing.T) {
	for _, tc := range []struct {
		cfg  Config
		mode string
	}{
		{Config{Server: "localhost:25565", IdentityFile: "key.json"}, ModeClient},
		{Config{Server: "localhost:25565", CAFile: "cert.pem", KnownServersFile: "pins.json"}, ModeClient},
		{Config{Server: "localhost:25565", ServerName: "mc.example"}, ModeClient},
		{Config{Target: "localhost:25565", CertFile: "cert.pem", KeyFile: "key.pem", IdentityFile: "key.json"}, ModeServer},
		{Config{Target: "localhost:25565", KnownServersFile: "pins.json"}, ModeServer},
	} {
		if _, err := tc.cfg.Normalize(tc.mode); err == nil {
			t.Fatalf("accepted conflicting identity settings in %s", tc.mode)
		}
	}
}
