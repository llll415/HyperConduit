package main

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestUsageRejectsUnknownModeAndCredentialFlags(t *testing.T) {
	for _, args := range [][]string{nil, {"proxy"}, {"server"}, {"client", "--token", "secret"}, {"client", "--config", "x", "extra"}} {
		var stdout, stderr bytes.Buffer
		if err := run(context.Background(), args, &stdout, &stderr); err == nil {
			t.Fatalf("expected usage/config error for %q", args)
		}
		if stdout.Len() != 0 {
			t.Fatal("invalid invocation emitted ready")
		}
	}
}

type eventWriter struct{ events chan []byte }

func (w eventWriter) Write(p []byte) (int, error) {
	w.events <- append([]byte(nil), p...)
	return len(p), nil
}

func TestServerReadyAndCancel(t *testing.T) {
	path := filepath.Join(t.TempDir(), "server.json")
	fixtures := filepath.Join("..", "..", "hy2-core", "internal", "integration_tests")
	data, _ := json.Marshal(map[string]any{
		"target": "127.0.0.1:25565", "certFile": filepath.Join(fixtures, "test.crt"), "keyFile": filepath.Join(fixtures, "test.key"),
	})
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	events := make(chan []byte, 1)
	done := make(chan error, 1)
	go func() { done <- run(ctx, []string{"server", "--config", path}, eventWriter{events}, &bytes.Buffer{}) }()
	select {
	case event := <-events:
		var ready struct {
			Version int    `json:"version"`
			Event   string `json:"event"`
			Mode    string `json:"mode"`
			Listen  string `json:"listen"`
		}
		if err := json.Unmarshal(event, &ready); err != nil {
			t.Fatal(err)
		}
		if ready.Version != 1 || ready.Event != "ready" || ready.Mode != "server" || ready.Listen == "127.0.0.1:0" {
			t.Fatalf("invalid ready event: %s", event)
		}
	case err := <-done:
		t.Fatalf("server exited before ready: %v", err)
	case <-time.After(3 * time.Second):
		t.Fatal("server did not emit ready")
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("server did not exit after cancellation")
	}
}

func TestConfigErrorsDoNotEmitReadyOrToken(t *testing.T) {
	path := filepath.Join(t.TempDir(), "client.json")
	const secret = "not-for-logs"
	data, _ := json.Marshal(map[string]any{"server": "127.0.0.1:25565", "token": secret, "target": "127.0.0.1:1"})
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	var stdout, stderr bytes.Buffer
	err := run(context.Background(), []string{"client", "--config", path}, &stdout, &stderr)
	if err == nil || strings.Contains(err.Error(), secret) || stdout.Len() != 0 {
		t.Fatalf("unexpected output/error: %s / %v", &stdout, err)
	}
}
