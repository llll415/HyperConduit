package identity

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestIdentityProcessWorker(t *testing.T) {
	if os.Getenv("HC_IDENTITY_TEST_WORKER") != "1" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	cert, err := LoadOrCreate(ctx, os.Getenv("HC_IDENTITY_TEST_FILE"))
	if err != nil {
		os.Exit(4)
	}
	_, _ = os.Stdout.WriteString(Fingerprint(cert.Leaf))
	os.Exit(0)
}

func TestConcurrentProcessesReuseOneKey(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nested", "identity.json")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	type result struct {
		pin string
		err error
	}
	results := make(chan result, 6)
	for range 6 {
		go func() {
			cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestIdentityProcessWorker$")
			cmd.Env = append(os.Environ(), "HC_IDENTITY_TEST_WORKER=1", "HC_IDENTITY_TEST_FILE="+path)
			out, err := cmd.Output()
			results <- result{strings.TrimSpace(string(out)), err}
		}()
	}
	pin := ""
	for range 6 {
		got := <-results
		if got.err != nil {
			t.Fatal(got.err)
		}
		if pin == "" {
			pin = got.pin
		}
		if got.pin != pin || !strings.HasPrefix(pin, "SHA256:") {
			t.Fatalf("concurrent process identity mismatch: %q != %q", got.pin, pin)
		}
	}
}
