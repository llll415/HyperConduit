package trust

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/llll415/HyperConduit/sidecar/internal/privatefile"
)

const testEndpoint = "example.com:443"

func testPin(label string) string {
	sum := sha256.Sum256([]byte(label))
	return "SHA256:" + base64.RawStdEncoding.EncodeToString(sum[:])
}

func newTestStore(t *testing.T) (*Store, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "trust.json")
	return New(path), path
}

func mustReadFile(t *testing.T, path string) []byte {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return b
}

func mustEntries(t *testing.T, st *Store) map[string]string {
	t.Helper()
	entries, err := st.Entries(context.Background())
	if err != nil {
		t.Fatalf("Entries: %v", err)
	}
	return entries
}

func requireSameBytes(t *testing.T, path string, want []byte) {
	t.Helper()
	if got := mustReadFile(t, path); !bytes.Equal(got, want) {
		t.Fatalf("file %s was modified:\n got: %q\nwant: %q", path, got, want)
	}
}

func requireStorageError(t *testing.T, err error) {
	t.Helper()
	var storageErr *StorageError
	if !errors.As(err, &storageErr) {
		t.Fatalf("error = %T (%v), want *StorageError", err, err)
	}
	if got := err.Error(); got != "trust store unavailable" {
		t.Fatalf("StorageError message = %q, want generic message", got)
	}
}

// Check must be a pure read: it may create the lock file but never the database.
func TestCheckDoesNotCreateDatabase(t *testing.T) {
	st, path := newTestStore(t)
	ctx := context.Background()

	if err := st.Check(ctx, testEndpoint, testPin("first")); err != nil {
		t.Fatalf("Check on empty store: %v", err)
	}
	if _, err := os.Stat(path); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("Check created database (stat err = %v)", err)
	}
	if entries := mustEntries(t, st); len(entries) != 0 {
		t.Fatalf("Entries = %v, want empty", entries)
	}

	// Forgetting an unknown endpoint is also a no-op and must not create the file.
	if err := st.Forget(ctx, testEndpoint); err != nil {
		t.Fatalf("Forget on empty store: %v", err)
	}
	if _, err := os.Stat(path); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("Forget created database (stat err = %v)", err)
	}
}

func TestCommitPersistsAcrossRestart(t *testing.T) {
	st, path := newTestStore(t)
	ctx := context.Background()
	pin := testPin("alpha")

	if err := st.Commit(ctx, testEndpoint, pin); err != nil {
		t.Fatalf("Commit: %v", err)
	}

	var persisted struct {
		Version int               `json:"version"`
		Pins    map[string]string `json:"pins"`
	}
	if err := json.Unmarshal(mustReadFile(t, path), &persisted); err != nil {
		t.Fatalf("persisted database is not valid JSON: %v", err)
	}
	if persisted.Version != 1 {
		t.Fatalf("persisted version = %d, want 1", persisted.Version)
	}
	if got := persisted.Pins[testEndpoint]; got != pin {
		t.Fatalf("persisted pin = %q, want %q", got, pin)
	}

	// A fresh Store instance models a process restart.
	reopened := New(path)
	entries := mustEntries(t, reopened)
	if len(entries) != 1 || entries[testEndpoint] != pin {
		t.Fatalf("Entries after restart = %v, want only %s", entries, testEndpoint)
	}
	if err := reopened.Check(ctx, testEndpoint, pin); err != nil {
		t.Fatalf("Check after restart: %v", err)
	}

	// Re-committing the same pin is idempotent and must not rewrite the file.
	before := mustReadFile(t, path)
	if err := reopened.Commit(ctx, testEndpoint, pin); err != nil {
		t.Fatalf("idempotent Commit: %v", err)
	}
	requireSameBytes(t, path, before)
}

func TestChangedPinRejectedWithoutRewritingDatabase(t *testing.T) {
	st, path := newTestStore(t)
	ctx := context.Background()
	pinA := testPin("alpha")
	pinB := testPin("beta")

	if err := st.Commit(ctx, testEndpoint, pinA); err != nil {
		t.Fatalf("Commit: %v", err)
	}
	before := mustReadFile(t, path)

	var changed *ChangedError
	err := st.Check(ctx, testEndpoint, pinB)
	if !errors.As(err, &changed) {
		t.Fatalf("Check with changed pin = %T (%v), want *ChangedError", err, err)
	}
	if changed.Endpoint != testEndpoint || changed.Expected != pinA || changed.Observed != pinB {
		t.Fatalf("ChangedError = %+v, want endpoint/expected/observed populated", changed)
	}
	if got := err.Error(); got != "server identity changed" {
		t.Fatalf("ChangedError message = %q, want generic message", got)
	}
	requireSameBytes(t, path, before)

	err = st.Commit(ctx, testEndpoint, pinB)
	if !errors.As(err, &changed) {
		t.Fatalf("Commit with changed pin = %T (%v), want *ChangedError", err, err)
	}
	if changed.Expected != pinA || changed.Observed != pinB {
		t.Fatalf("Commit ChangedError = %+v, want original and new pin", changed)
	}
	requireSameBytes(t, path, before)
	if entries := mustEntries(t, st); len(entries) != 1 || entries[testEndpoint] != pinA {
		t.Fatalf("Entries after rejected change = %v, want original pin", entries)
	}

	// Forget removes trust, after which a new pin may be trusted.
	if err := st.Forget(ctx, testEndpoint); err != nil {
		t.Fatalf("Forget: %v", err)
	}
	if entries := mustEntries(t, st); len(entries) != 0 {
		t.Fatalf("Entries after Forget = %v, want empty", entries)
	}
	if err := st.Check(ctx, testEndpoint, pinB); err != nil {
		t.Fatalf("Check after Forget with new pin: %v", err)
	}
	if err := st.Commit(ctx, testEndpoint, pinB); err != nil {
		t.Fatalf("Commit after Forget with new pin: %v", err)
	}
	if entries := mustEntries(t, st); len(entries) != 1 || entries[testEndpoint] != pinB {
		t.Fatalf("Entries after re-trust = %v, want new pin", entries)
	}
	if err := st.Check(ctx, testEndpoint, pinA); !errors.As(err, &changed) {
		t.Fatalf("Check old pin after re-trust = %T (%v), want *ChangedError", err, err)
	}
}

func TestEntriesReturnsIndependentCopy(t *testing.T) {
	st, _ := newTestStore(t)
	ctx := context.Background()
	pin := testPin("alpha")
	if err := st.Commit(ctx, testEndpoint, pin); err != nil {
		t.Fatalf("Commit: %v", err)
	}

	entries := mustEntries(t, st)
	entries[testEndpoint] = testPin("tampered")
	entries["attacker.example:443"] = testPin("attacker")

	again := mustEntries(t, st)
	if len(again) != 1 {
		t.Fatalf("Entries after mutating previous result = %v, want one entry", again)
	}
	if got := again[testEndpoint]; got != pin {
		t.Fatalf("stored pin = %q, want %q; Entries did not return a copy", got, pin)
	}
}

func TestConcurrentSamePinCommitsAllSucceed(t *testing.T) {
	st, _ := newTestStore(t)
	pin := testPin("shared")
	const workers = 16

	start := make(chan struct{})
	errs := make(chan error, workers)
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			errs <- st.Commit(context.Background(), testEndpoint, pin)
		}()
	}
	close(start)
	wg.Wait()
	close(errs)

	for err := range errs {
		if err != nil {
			t.Errorf("concurrent Commit with identical pin: %v", err)
		}
	}
	entries := mustEntries(t, st)
	if len(entries) != 1 || entries[testEndpoint] != pin {
		t.Fatalf("Entries = %v, want only the shared pin", entries)
	}
}

func TestConcurrentDifferentPinsHaveSingleWinner(t *testing.T) {
	st, _ := newTestStore(t)
	const workers = 8
	pins := make([]string, workers)
	for i := range pins {
		pins[i] = testPin(string(rune('a' + i)))
	}

	type result struct {
		pin string
		err error
	}
	start := make(chan struct{})
	results := make(chan result, workers)
	var wg sync.WaitGroup
	for _, pin := range pins {
		pin := pin
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			results <- result{pin: pin, err: st.Commit(context.Background(), testEndpoint, pin)}
		}()
	}
	close(start)
	wg.Wait()
	close(results)

	winners := 0
	winnerPin := ""
	for res := range results {
		if res.err == nil {
			winners++
			winnerPin = res.pin
			continue
		}
		var changed *ChangedError
		if !errors.As(res.err, &changed) {
			t.Errorf("losing Commit error = %T (%v), want *ChangedError", res.err, res.err)
			continue
		}
		if changed.Observed != res.pin {
			t.Errorf("ChangedError.Observed = %q, want losing pin %q", changed.Observed, res.pin)
		}
	}
	if winners != 1 {
		t.Fatalf("winners = %d, want exactly 1", winners)
	}

	entries := mustEntries(t, st)
	if len(entries) != 1 {
		t.Fatalf("Entries = %v, want exactly one stored pin", entries)
	}
	if got := entries[testEndpoint]; got != winnerPin {
		t.Fatalf("stored pin = %q, want winning pin %q", got, winnerPin)
	}
}

func TestMalformedDatabaseFailsClosed(t *testing.T) {
	validPin := testPin("valid")
	cases := []struct {
		name    string
		content string
	}{
		{"empty", ""},
		{"truncated json", `{"version":1,"pins":`},
		{"null", `null`},
		{"empty object", `{}`},
		{"missing version", `{"pins":{}}`},
		{"null pins", `{"version":1,"pins":null}`},
		{"wrong version", `{"version":2,"pins":{}}`},
		{"duplicate version", `{"version":1,"version":1,"pins":{}}`},
		{"duplicate pins", `{"version":1,"pins":{},"pins":{}}`},
		{"invalid pin", `{"version":1,"pins":{"example.com:443":"SHA256:do-not-leak-marker"}}`},
		{"bad canonical endpoint", `{"version":1,"pins":{"Example.com:443":"` + validPin + `"}}`},
		{"unknown field", `{"version":1,"pins":{},"extra":true}`},
		{"trailing data", `{"version":1,"pins":{}} {"version":1}`},
		{"trailing garbage", `{"version":1,"pins":{}} trailing`},
		{"array", `[]`},
		{"wrong pin type", `{"version":1,"pins":{"example.com:443":1}}`},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "trust.json")
			if err := os.WriteFile(path, []byte(tc.content), 0o600); err != nil {
				t.Fatalf("write malformed database: %v", err)
			}
			st := New(path)
			ctx := context.Background()
			before := mustReadFile(t, path)

			// Check must fail closed instead of treating a broken file as empty
			// trust, and must not repair or overwrite it.
			checkErr := st.Check(ctx, testEndpoint, validPin)
			if checkErr == nil {
				t.Fatal("Check accepted a malformed database")
			}
			requireStorageError(t, checkErr)
			requireSameBytes(t, path, before)

			// Commit must not auto-trust or overwrite malformed contents.
			commitErr := st.Commit(ctx, testEndpoint, validPin)
			if commitErr == nil {
				t.Fatal("Commit accepted a malformed database")
			}
			requireStorageError(t, commitErr)
			requireSameBytes(t, path, before)

			entries, entriesErr := st.Entries(ctx)
			if entriesErr == nil {
				t.Fatal("Entries accepted a malformed database")
			}
			requireStorageError(t, entriesErr)
			if entries != nil {
				t.Fatalf("Entries on malformed database = %v, want nil", entries)
			}
			requireSameBytes(t, path, before)

			// Forget must fail closed too; it must not silently rewrite the file.
			forgetErr := st.Forget(ctx, testEndpoint)
			if forgetErr == nil {
				t.Fatal("Forget accepted a malformed database")
			}
			requireStorageError(t, forgetErr)
			requireSameBytes(t, path, before)
		})
	}
}

func TestErrorsDoNotLeakDatabaseContent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "trust.json")
	content := `{"version":1,"pins":{"secret-host.invalid:443":"SHA256:do-not-leak-marker"}}`
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("write malformed database: %v", err)
	}

	err := New(path).Check(context.Background(), testEndpoint, testPin("candidate"))
	if err == nil {
		t.Fatal("Check accepted malformed database")
	}
	for _, secret := range []string{"do-not-leak-marker", "secret-host.invalid"} {
		if strings.Contains(err.Error(), secret) {
			t.Fatalf("error %q leaks database content %q", err, secret)
		}
	}

	// ChangedError exposes structured fields to the caller but must not render
	// expected or observed pins in its message.
	st, _ := newTestStore(t)
	pinA, pinB := testPin("alpha"), testPin("beta")
	if err := st.Commit(context.Background(), testEndpoint, pinA); err != nil {
		t.Fatalf("Commit: %v", err)
	}
	err = st.Check(context.Background(), testEndpoint, pinB)
	var changed *ChangedError
	if !errors.As(err, &changed) {
		t.Fatalf("Check with changed pin = %T (%v), want *ChangedError", err, err)
	}
	for _, pin := range []string{pinA, pinB} {
		if strings.Contains(err.Error(), pin) {
			t.Fatalf("ChangedError message %q leaks pin %q", err, pin)
		}
	}
}

func TestSymlinkDatabaseRejected(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "target.json")
	st := New(target)
	ctx := context.Background()
	pinA := testPin("alpha")

	if err := st.Commit(ctx, testEndpoint, pinA); err != nil {
		t.Fatalf("seed target database: %v", err)
	}
	before := mustReadFile(t, target)

	link := filepath.Join(dir, "trust.json")
	if err := os.Symlink(target, link); err != nil {
		// Creating symlinks needs extra privileges on Windows. Skip instead of
		// weakening permissions or enabling developer mode.
		t.Skipf("symlink unavailable without permission workaround: %v", err)
	}

	linked := New(link)
	ops := []struct {
		name string
		call func() error
	}{
		{"Check", func() error { return linked.Check(ctx, testEndpoint, pinA) }},
		{"Commit", func() error { return linked.Commit(ctx, testEndpoint, testPin("beta")) }},
		{"Entries", func() error { _, err := linked.Entries(ctx); return err }},
		{"Forget", func() error { return linked.Forget(ctx, testEndpoint) }},
	}
	for _, op := range ops {
		if err := op.call(); err == nil {
			t.Errorf("%s followed the symlink, want rejection", op.name)
		}
	}
	requireSameBytes(t, target, before)
	if entries := mustEntries(t, st); len(entries) != 1 || entries[testEndpoint] != pinA {
		t.Fatalf("target database changed through symlink: %v", entries)
	}
}

func TestFirstHandshakeCheckThenCommit(t *testing.T) {
	st, path := newTestStore(t)
	ctx := context.Background()
	pin := testPin("first-connection")

	// First connection: Check establishes that the candidate is not known yet,
	// but external successful authentication (outside this package) has not
	// happened, so no trust may be recorded.
	if err := st.Check(ctx, testEndpoint, pin); err != nil {
		t.Fatalf("first Check: %v", err)
	}
	if _, err := os.Stat(path); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("Check recorded trust before successful auth (stat err = %v)", err)
	}
	if entries := mustEntries(t, st); len(entries) != 0 {
		t.Fatalf("Entries before successful auth = %v, want empty", entries)
	}

	// The caller now reports successful auth, so the same pin is committed.
	if err := st.Commit(ctx, testEndpoint, pin); err != nil {
		t.Fatalf("Commit after successful auth: %v", err)
	}
	if entries := mustEntries(t, st); len(entries) != 1 || entries[testEndpoint] != pin {
		t.Fatalf("Entries after successful auth = %v, want the authenticated pin", entries)
	}
	if err := st.Check(ctx, testEndpoint, pin); err != nil {
		t.Fatalf("subsequent Check with trusted pin: %v", err)
	}

	var changed *ChangedError
	if err := st.Check(ctx, testEndpoint, testPin("attacker")); !errors.As(err, &changed) {
		t.Fatalf("subsequent Check with different pin = %T (%v), want *ChangedError", err, err)
	}
}

func TestEndpointCanonicalization(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		{"lowercase", "Example.COM:443", "example.com:443"},
		{"idna punycode", "BÜCHER.Example:443", "xn--bcher-kva.example:443"},
		{"trailing dot", "Example.COM.:443", "example.com:443"},
		{"IPv4", "127.0.0.1:443", "127.0.0.1:443"},
		{"IPv6 brackets and case", "[2001:DB8::1]:443", "[2001:db8::1]:443"},
		{"IPv4-mapped IPv6", "[::ffff:192.0.2.1]:443", "192.0.2.1:443"},
		{"port leading zeroes", "example.com:0443", "example.com:443"},
		{"max port", "example.com:65535", "example.com:65535"},
		{"localhost not resolved", "localhost:443", "localhost:443"},
		{"invalid TLD not resolved", "unresolvable.invalid:443", "unresolvable.invalid:443"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, err := Endpoint(tc.in)
			if err != nil {
				t.Fatalf("Endpoint(%q): %v", tc.in, err)
			}
			if got != tc.want {
				t.Fatalf("Endpoint(%q) = %q, want %q", tc.in, got, tc.want)
			}
		})
	}
}

func TestEndpointDistinguishesPortsAndIPv6(t *testing.T) {
	v4, err := Endpoint("192.0.2.1:443")
	if err != nil {
		t.Fatalf("Endpoint IPv4: %v", err)
	}
	v4OtherPort, err := Endpoint("192.0.2.1:8443")
	if err != nil {
		t.Fatalf("Endpoint IPv4 other port: %v", err)
	}
	if v4 == v4OtherPort {
		t.Fatalf("ports collapsed: %q == %q", v4, v4OtherPort)
	}

	v6, err := Endpoint("[2001:db8::1]:443")
	if err != nil {
		t.Fatalf("Endpoint IPv6: %v", err)
	}
	v6OtherPort, err := Endpoint("[2001:db8::1]:8443")
	if err != nil {
		t.Fatalf("Endpoint IPv6 other port: %v", err)
	}
	if v6 == v6OtherPort {
		t.Fatalf("ports collapsed: %q == %q", v6, v6OtherPort)
	}

	mapped, err := Endpoint("[::ffff:192.0.2.1]:443")
	if err != nil {
		t.Fatalf("Endpoint mapped IPv4: %v", err)
	}
	if mapped != v4 {
		t.Fatalf("mapped IPv4 endpoint = %q, want %q", mapped, v4)
	}
}

func TestEndpointRejectsInvalidAddresses(t *testing.T) {
	invalid := []string{
		"",
		"example.com",
		"example.com:",
		":443",
		"example.com:0",
		"example.com:65536",
		"example.com:-1",
		"example.com:abc",
		"example.com: 443",
		"0.0.0.0:443",
		"[::]:443",
		"2001:db8::1:443",
		"example.com:443/path",
		"bad host:443",
	}
	for _, address := range invalid {
		if got, err := Endpoint(address); err == nil {
			t.Errorf("Endpoint(%q) = %q, want error", address, got)
		}
	}
}

func TestInvalidCandidatesDoNotTouchStore(t *testing.T) {
	st, path := newTestStore(t)
	ctx := context.Background()
	goodPin := testPin("good")

	badAddresses := []string{
		"",
		"example.com",
		":443",
		"example.com:0",
		"example.com:65536",
		"0.0.0.0:443",
		"[::]:443",
		"bad host:443",
	}
	for _, address := range badAddresses {
		if err := st.Check(ctx, address, goodPin); err == nil {
			t.Errorf("Check(%q) accepted invalid address", address)
		}
		if err := st.Commit(ctx, address, goodPin); err == nil {
			t.Errorf("Commit(%q) accepted invalid address", address)
		}
	}

	padded := base64.StdEncoding.EncodeToString(make([]byte, sha256.Size))
	badPins := []string{
		"",
		"SHA256",
		"sha256:" + base64.RawStdEncoding.EncodeToString(make([]byte, sha256.Size)),
		"SHA256:",
		"SHA256:AAAA",
		"SHA256:" + padded, // padded base64 is not canonical RawStdEncoding
		"SHA256:" + base64.RawStdEncoding.EncodeToString(make([]byte, sha256.Size-1)),
		goodPin + "\n",
	}
	for _, pin := range badPins {
		if err := st.Check(ctx, testEndpoint, pin); err == nil {
			t.Errorf("Check accepted invalid pin %q", pin)
		}
		if err := st.Commit(ctx, testEndpoint, pin); err == nil {
			t.Errorf("Commit accepted invalid pin %q", pin)
		}
	}

	if _, err := os.Stat(path); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("invalid candidates created database (stat err = %v)", err)
	}
}

func TestCheckContextCanceledWhileLockHeld(t *testing.T) {
	st, path := newTestStore(t)
	lockPath := path + ".lock"

	held := make(chan struct{})
	release := make(chan struct{})
	var releaseOnce sync.Once
	releaseLock := func() { releaseOnce.Do(func() { close(release) }) }
	defer releaseLock()

	holderDone := make(chan error, 1)
	go func() {
		holderDone <- privatefile.WithLock(context.Background(), lockPath, func() error {
			close(held)
			<-release
			return nil
		})
	}()

	select {
	case <-held:
	case <-time.After(5 * time.Second):
		t.Fatal("lock holder did not acquire the lock")
	}

	// Safety valve so a buggy lock implementation cannot hang the test.
	timer := time.AfterFunc(5*time.Second, releaseLock)
	defer timer.Stop()

	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()

	start := time.Now()
	err := st.Check(ctx, testEndpoint, testPin("waiting"))
	elapsed := time.Since(start)

	releaseLock()
	if holderErr := <-holderDone; holderErr != nil {
		t.Fatalf("lock holder: %v", holderErr)
	}

	if !errors.Is(err, context.DeadlineExceeded) && !errors.Is(err, context.Canceled) {
		t.Fatalf("Check while lock held = %T (%v), want context cancellation", err, err)
	}
	if elapsed < 100*time.Millisecond {
		t.Fatalf("Check returned after %v, want it to wait at least 100ms for the lock", elapsed)
	}
	if elapsed > 5*time.Second {
		t.Fatalf("Check waited %v, want bounded by context", elapsed)
	}
}
