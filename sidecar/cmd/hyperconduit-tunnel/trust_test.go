package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/llll415/HyperConduit/sidecar/internal/trust"
)

func TestTrustListAndForget(t *testing.T) {
	dir := t.TempDir()
	db := filepath.Join(dir, "known.json")
	file := filepath.Join(dir, "client.json")
	b, _ := json.Marshal(map[string]string{"server": "mc.example:25565", "knownServersFile": db})
	if err := os.WriteFile(file, b, 0o600); err != nil {
		t.Fatal(err)
	}
	pin := "SHA256:" + base64.RawStdEncoding.EncodeToString(make([]byte, 32))
	if err := trust.New(db).Commit(context.Background(), "mc.example:25565", pin); err != nil {
		t.Fatal(err)
	}
	var out, stderr bytes.Buffer
	if err := run(context.Background(), []string{"trust", "list", "--config", file}, &out, &stderr); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), pin) || !strings.Contains(out.String(), "mc.example:25565") {
		t.Fatal(out.String())
	}
	out.Reset()
	if err := run(context.Background(), []string{"trust", "forget", "--config", file, "--server", "mc.example:25565"}, &out, &stderr); err != nil {
		t.Fatal(err)
	}
	entries, err := trust.New(db).Entries(context.Background())
	if err != nil || len(entries) != 0 {
		t.Fatalf("trust not removed: %v %v", entries, err)
	}
}
