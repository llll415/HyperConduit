package trust

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"testing"
	"time"
)

func TestTrustProcessWorker(t *testing.T) {
	if os.Getenv("HC_TRUST_TEST_WORKER") != "1" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	err := New(os.Getenv("HC_TRUST_TEST_FILE")).Commit(ctx, "mc.example:25565", os.Getenv("HC_TRUST_TEST_PIN"))
	var changed *ChangedError
	if errors.As(err, &changed) {
		os.Exit(3)
	}
	if err != nil {
		os.Exit(4)
	}
	os.Exit(0)
}

func TestCrossProcessFirstTrustHasOneWinner(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nested", "known.json")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	commands := make([]*exec.Cmd, 6)
	for i := range commands {
		cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestTrustProcessWorker$")
		cmd.Env = append(os.Environ(), "HC_TRUST_TEST_WORKER=1", "HC_TRUST_TEST_FILE="+path, "HC_TRUST_TEST_PIN="+testPin(strconv.Itoa(i)))
		if err := cmd.Start(); err != nil {
			t.Fatal(err)
		}
		commands[i] = cmd
	}
	winners := 0
	for _, cmd := range commands {
		err := cmd.Wait()
		if err == nil {
			winners++
			continue
		}
		var exit *exec.ExitError
		if !errors.As(err, &exit) || exit.ExitCode() != 3 {
			t.Fatalf("unexpected worker error: %v", err)
		}
	}
	if winners != 1 {
		t.Fatalf("first trust winners: %d", winners)
	}
	entries, err := New(path).Entries(ctx)
	if err != nil || len(entries) != 1 {
		t.Fatalf("database after concurrent writers: %v %v", entries, err)
	}
}
