package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const managedTestTimeout = 3 * time.Second

func testFixturesDir() string {
	return filepath.Join("..", "..", "hy2-core", "internal", "integration_tests")
}

func managedServerConfig(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "server.json")
	fixtures := testFixturesDir()
	data, err := json.Marshal(map[string]any{
		"target":   "127.0.0.1:25565",
		"certFile": filepath.Join(fixtures, "test.crt"),
		"keyFile":  filepath.Join(fixtures, "test.key"),
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func waitManagedReady(t *testing.T, events <-chan []byte, done <-chan error) {
	t.Helper()
	select {
	case event := <-events:
		var ready struct {
			Version int    `json:"version"`
			Event   string `json:"event"`
			Mode    string `json:"mode"`
			Listen  string `json:"listen"`
		}
		if err := json.Unmarshal(event, &ready); err != nil {
			t.Fatalf("invalid ready event: %v", err)
		}
		if ready.Version != 1 || ready.Event != "ready" || ready.Mode != "server" || ready.Listen == "" {
			t.Fatalf("invalid ready event: %s", event)
		}
	case err := <-done:
		t.Fatalf("managed run exited before ready: %v", err)
	case <-time.After(managedTestTimeout):
		t.Fatal("managed run did not emit ready")
	}
}

func TestManagedServerStopsOnControl(t *testing.T) {
	tests := []struct {
		name    string
		control func(*io.PipeWriter) error
	}{
		{
			name: "shutdown command",
			control: func(pw *io.PipeWriter) error {
				_, err := io.WriteString(pw, `{"version":1,"command":"shutdown"}`+"\n")
				return err
			},
		},
		{
			name:    "stdin EOF",
			control: func(pw *io.PipeWriter) error { return pw.Close() },
		},
		{
			name: "unknown command",
			control: func(pw *io.PipeWriter) error {
				_, err := io.WriteString(pw, `{"version":1,"command":"keepalive"}`+"\n")
				return err
			},
		},
		{
			name: "unknown version",
			control: func(pw *io.PipeWriter) error {
				_, err := io.WriteString(pw, `{"version":2,"command":"shutdown"}`+"\n")
				return err
			},
		},
		{
			name: "malformed JSON",
			control: func(pw *io.PipeWriter) error {
				_, err := io.WriteString(pw, "not json\n")
				return err
			},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			path := managedServerConfig(t)
			pr, pw := io.Pipe()
			defer pw.Close()

			events := make(chan []byte, 1)
			var stderr bytes.Buffer
			done := make(chan error, 1)
			go func() {
				done <- runWithInput(context.Background(), []string{"server", "--config", path, "--managed"}, pr, eventWriter{events}, &stderr)
			}()
			waitManagedReady(t, events, done)

			if err := tt.control(pw); err != nil {
				t.Fatalf("write control input: %v", err)
			}
			select {
			case err := <-done:
				if err != nil {
					t.Fatalf("managed run returned %v, want nil", err)
				}
			case <-time.After(managedTestTimeout):
				t.Fatal("managed run did not exit after control input")
			}
			if stderr.Len() != 0 {
				t.Fatalf("managed run wrote an error: %q", stderr.String())
			}
		})
	}
}

func TestManagedClientStopsWhenStdinClosesDuringStartup(t *testing.T) {
	silent, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer silent.Close()
	go func() {
		buf := make([]byte, 64*1024)
		for {
			if _, _, err := silent.ReadFromUDP(buf); err != nil {
				return
			}
		}
	}()

	path := filepath.Join(t.TempDir(), "client.json")
	data, err := json.Marshal(map[string]any{
		"server":           silent.LocalAddr().String(),
		"caFile":           filepath.Join(testFixturesDir(), "test.crt"),
		"connectTimeoutMs": 60000,
		"stateDir":         t.TempDir(),
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}

	pr, pw := io.Pipe()
	var stderr bytes.Buffer
	done := make(chan error, 1)
	go func() {
		done <- runWithInput(context.Background(), []string{"client", "--config", path, "--managed"}, pr, io.Discard, &stderr)
	}()

	// Give the client time to enter its long handshake, then close stdin. The
	// 60s connect timeout must not keep the managed process alive.
	select {
	case err := <-done:
		t.Fatalf("managed client exited before stdin closed: %v", err)
	case <-time.After(200 * time.Millisecond):
	}
	start := time.Now()
	if err := pw.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != nil && !errors.Is(err, context.Canceled) {
			t.Fatalf("managed client returned %v", err)
		}
		if elapsed := time.Since(start); elapsed > time.Second {
			t.Fatalf("managed client took %s to stop after stdin closed", elapsed)
		}
	case <-time.After(managedTestTimeout):
		t.Fatal("managed client did not stop after stdin closed")
	}
	if stderr.Len() != 0 {
		t.Fatalf("managed client wrote an error: %q", stderr.String())
	}
}

type countingReader struct{ reads atomic.Int32 }

func (r *countingReader) Read([]byte) (int, error) {
	r.reads.Add(1)
	return 0, io.EOF
}

func TestNonManagedDoesNotReadStdin(t *testing.T) {
	path := managedServerConfig(t)
	stdin := &countingReader{}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	events := make(chan []byte, 1)
	done := make(chan error, 1)
	go func() {
		done <- runWithInput(ctx, []string{"server", "--config", path}, stdin, eventWriter{events}, &bytes.Buffer{})
	}()
	waitManagedReady(t, events, done)

	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(managedTestTimeout):
		t.Fatal("non-managed run did not exit after cancellation")
	}
	if got := stdin.reads.Load(); got != 0 {
		t.Fatalf("non-managed run read stdin %d time(s)", got)
	}
}

func TestManagedRequiresStdin(t *testing.T) {
	path := managedServerConfig(t)
	var stdout, stderr bytes.Buffer
	err := run(context.Background(), []string{"server", "--config", path, "--managed"}, &stdout, &stderr)
	if err == nil {
		t.Fatal("managed run without stdin succeeded")
	}
	if stdout.Len() != 0 {
		t.Fatalf("managed run without stdin emitted ready: %q", stdout.String())
	}
}

func TestReadManagedCommand(t *testing.T) {
	tests := []struct {
		name  string
		input string
		want  bool
	}{
		{name: "shutdown", input: `{"version":1,"command":"shutdown"}`, want: true},
		{name: "shutdown with newline", input: `{"version":1,"command":"shutdown"}` + "\n", want: true},
		{name: "shutdown with trailing space", input: `{"version":1,"command":"shutdown"} ` + "\n", want: true},
		{name: "empty", input: "", want: false},
		{name: "blank line", input: "\n", want: false},
		{name: "malformed", input: "{", want: false},
		{name: "unknown command", input: `{"version":1,"command":"keepalive"}`, want: false},
		{name: "unknown version", input: `{"version":2,"command":"shutdown"}`, want: false},
		{name: "unknown field", input: `{"version":1,"command":"shutdown","token":"secret"}`, want: false},
		{name: "trailing value", input: `{"version":1,"command":"shutdown"}{}`, want: false},
		{name: "oversized line", input: strings.Repeat("a", managedMaxCommandBytes+1), want: false},
		{name: "oversized valid prefix", input: `{"version":1,"command":"shutdown"}` + strings.Repeat(" ", managedMaxCommandBytes), want: false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := readManagedCommand(strings.NewReader(tt.input)); got != tt.want {
				t.Fatalf("readManagedCommand() = %v, want %v", got, tt.want)
			}
		})
	}
}
