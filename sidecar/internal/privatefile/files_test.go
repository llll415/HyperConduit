package privatefile

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

func mustWriteFile(t *testing.T, path string, data []byte) {
	t.Helper()
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}

func mustSymlink(t *testing.T, oldname, newname string) {
	t.Helper()
	if err := os.Symlink(oldname, newname); err != nil {
		t.Skipf("symlinks are not available on this system: %v", err)
	}
}

func assertNoTempFiles(t *testing.T, dir string) {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("read directory %s: %v", dir, err)
	}
	for _, entry := range entries {
		if strings.Contains(entry.Name(), ".tmp-") {
			t.Errorf("temporary file left behind: %s", entry.Name())
		}
	}
}

func TestReadMissingPreservesErrNotExist(t *testing.T) {
	path := filepath.Join(t.TempDir(), "missing")

	data, err := Read(path, 1024)
	if !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("Read error = %v, want os.ErrNotExist", err)
	}
	if data != nil {
		t.Fatalf("Read returned data on error: %q", data)
	}
}

func TestReadRejectsNonRegularFiles(t *testing.T) {
	dir := t.TempDir()

	data, err := Read(dir, 1024)
	if err == nil {
		t.Fatal("Read(directory) succeeded, want error")
	}
	if !errors.Is(err, errUnsafeFile) {
		t.Fatalf("Read(directory) error = %v, want errUnsafeFile", err)
	}
	if data != nil {
		t.Fatalf("Read returned data on error: %q", data)
	}
}

func TestReadRejectsSymlink(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "target")
	mustWriteFile(t, target, []byte("symlink-target-secret"))
	link := filepath.Join(dir, "link")
	mustSymlink(t, target, link)

	data, err := Read(link, 1024)
	if err == nil {
		t.Fatal("Read(symlink) succeeded, want error")
	}
	if !errors.Is(err, errUnsafeFile) {
		t.Fatalf("Read(symlink) error = %v, want errUnsafeFile", err)
	}
	if data != nil {
		t.Fatalf("Read returned data on error: %q", data)
	}
	if strings.Contains(err.Error(), "symlink-target-secret") {
		t.Fatalf("error leaked file contents: %v", err)
	}
}

func TestReadSizeLimit(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "data")
	mustWriteFile(t, path, []byte("hello"))

	tests := []struct {
		name    string
		max     int64
		want    string
		wantErr bool
	}{
		{name: "exact limit", max: 5, want: "hello"},
		{name: "below limit", max: 4, wantErr: true},
		{name: "zero limit non-empty", max: 0, wantErr: true},
		{name: "negative limit", max: -1, wantErr: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := Read(path, tt.max)
			if tt.wantErr {
				if err == nil {
					t.Fatalf("Read(max=%d) = %q, want error", tt.max, got)
				}
				if got != nil {
					t.Fatalf("Read returned data on error: %q", got)
				}
				return
			}
			if err != nil {
				t.Fatalf("Read(max=%d): %v", tt.max, err)
			}
			if string(got) != tt.want {
				t.Fatalf("Read(max=%d) = %q, want %q", tt.max, got, tt.want)
			}
		})
	}

	empty := filepath.Join(dir, "empty")
	mustWriteFile(t, empty, nil)
	got, err := Read(empty, 0)
	if err != nil {
		t.Fatalf("Read(empty, 0): %v", err)
	}
	if len(got) != 0 {
		t.Fatalf("Read(empty, 0) = %q, want empty", got)
	}
}

func TestReadErrorDoesNotLeakContents(t *testing.T) {
	path := filepath.Join(t.TempDir(), "secret")
	secret := "TOP-SECRET-CONTENT-THAT-MUST-NOT-APPEAR"
	mustWriteFile(t, path, []byte(secret))

	_, err := Read(path, 1)
	if err == nil {
		t.Fatal("Read succeeded, want size error")
	}
	if strings.Contains(err.Error(), secret) {
		t.Fatalf("error leaked file contents: %v", err)
	}
}

func TestReadTightensExistingFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "data")
	mustWriteFile(t, path, []byte("secret"))

	if runtime.GOOS != "windows" {
		// Start broad so the test can observe Read tightening the mode.
		if err := os.Chmod(path, 0o666); err != nil {
			t.Fatalf("broaden file mode: %v", err)
		}
	}

	got, err := Read(path, 64)
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if string(got) != "secret" {
		t.Fatalf("Read = %q, want %q", got, "secret")
	}
	if err := verifyPrivateFile(path); err != nil {
		t.Fatalf("Read did not tighten the file: %v", err)
	}
}

func TestOpenFileNoFollowRejectsSymlink(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "target")
	mustWriteFile(t, target, []byte("target"))
	link := filepath.Join(dir, "link")
	mustSymlink(t, target, link)

	f, err := openFileNoFollow(link, os.O_RDONLY, 0)
	if err != nil {
		// Unix rejects the final symlink at open time with O_NOFOLLOW.
		return
	}
	defer f.Close()

	info, err := f.Stat()
	if err != nil {
		t.Fatalf("stat opened symlink: %v", err)
	}
	if info.Mode().IsRegular() {
		t.Fatal("openFileNoFollow followed a symbolic link")
	}
}

func TestWriteCreatesParentAndReplaces(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "nested", "private", "data")

	if err := Write(path, []byte("first")); err != nil {
		t.Fatalf("Write(first): %v", err)
	}
	got, err := Read(path, 16)
	if err != nil {
		t.Fatalf("Read(first): %v", err)
	}
	if string(got) != "first" {
		t.Fatalf("Read(first) = %q, want %q", got, "first")
	}

	if err := Write(path, []byte("second")); err != nil {
		t.Fatalf("Write(second): %v", err)
	}
	got, err = Read(path, 16)
	if err != nil {
		t.Fatalf("Read(second): %v", err)
	}
	if string(got) != "second" {
		t.Fatalf("Read(second) = %q, want %q", got, "second")
	}
	if err := verifyPrivateFile(path); err != nil {
		t.Fatalf("destination is not private: %v", err)
	}

	if runtime.GOOS != "windows" {
		info, err := os.Stat(path)
		if err != nil {
			t.Fatalf("stat destination: %v", err)
		}
		if perm := info.Mode().Perm(); perm&0o077 != 0 {
			t.Errorf("destination mode = %o, want no group/other permissions", perm)
		}
		dirInfo, err := os.Stat(filepath.Dir(path))
		if err != nil {
			t.Fatalf("stat parent: %v", err)
		}
		if perm := dirInfo.Mode().Perm(); perm&0o077 != 0 {
			t.Errorf("parent mode = %o, want no group/other permissions", perm)
		}
	}
	assertNoTempFiles(t, filepath.Dir(path))
}

func TestWriteFailurePreservesExistingContent(t *testing.T) {
	if runtime.GOOS != "windows" {
		// On Unix, replacing a read-only file is governed by the parent
		// directory permissions, so a read-only destination does not make the
		// atomic rename fail.
		t.Skip("read-only destination blocks replacement only on Windows")
	}

	dir := t.TempDir()
	path := filepath.Join(dir, "data")
	if err := Write(path, []byte("original")); err != nil {
		t.Fatalf("initial write: %v", err)
	}
	if err := os.Chmod(path, 0o400); err != nil {
		t.Fatalf("make destination read-only: %v", err)
	}
	defer os.Chmod(path, 0o600) //nolint:errcheck // best-effort cleanup

	if err := Write(path, []byte("replacement")); err == nil {
		t.Fatal("Write over read-only destination succeeded, want error")
	}
	if err := os.Chmod(path, 0o600); err != nil {
		t.Fatalf("restore destination permissions: %v", err)
	}

	got, err := Read(path, 64)
	if err != nil {
		t.Fatalf("read destination after failed write: %v", err)
	}
	if string(got) != "original" {
		t.Fatalf("destination = %q, want unchanged %q", got, "original")
	}
	assertNoTempFiles(t, dir)
}

func TestWriteRejectsSymlink(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "target")
	mustWriteFile(t, target, []byte("original"))
	link := filepath.Join(dir, "link")
	mustSymlink(t, target, link)

	err := Write(link, []byte("attacker-controlled"))
	if err == nil {
		t.Fatal("Write(symlink) succeeded, want error")
	}
	if !errors.Is(err, errUnsafeFile) {
		t.Fatalf("Write(symlink) error = %v, want errUnsafeFile", err)
	}

	got, err := os.ReadFile(target)
	if err != nil {
		t.Fatalf("read symlink target: %v", err)
	}
	if string(got) != "original" {
		t.Fatalf("symlink target = %q, want unchanged %q", got, "original")
	}

	info, err := os.Lstat(link)
	if err != nil {
		t.Fatalf("lstat link: %v", err)
	}
	if info.Mode()&os.ModeSymlink == 0 {
		t.Fatal("Write replaced the symlink instead of rejecting it")
	}
}

func TestWriteRejectsDanglingSymlink(t *testing.T) {
	dir := t.TempDir()
	missing := filepath.Join(dir, "missing-target")
	link := filepath.Join(dir, "link")
	mustSymlink(t, missing, link)

	err := Write(link, []byte("data"))
	if err == nil {
		t.Fatal("Write(dangling symlink) succeeded, want error")
	}
	if !errors.Is(err, errUnsafeFile) {
		t.Fatalf("Write(dangling symlink) error = %v, want errUnsafeFile", err)
	}
	if _, err := os.Lstat(missing); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("Write created the symlink target; lstat error = %v", err)
	}
}

func TestWriteRejectsDirectory(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "target")
	if err := os.Mkdir(target, 0o700); err != nil {
		t.Fatalf("mkdir target: %v", err)
	}

	err := Write(target, []byte("data"))
	if err == nil {
		t.Fatal("Write(directory) succeeded, want error")
	}
	if !errors.Is(err, errUnsafeFile) {
		t.Fatalf("Write(directory) error = %v, want errUnsafeFile", err)
	}
}

func TestWithLockCallbackErrorIsPreserved(t *testing.T) {
	dir := t.TempDir()
	lockPath := filepath.Join(dir, "state.lock")
	sentinel := errors.New("callback failed")

	err := WithLock(context.Background(), lockPath, func() error {
		return sentinel
	})
	if !errors.Is(err, sentinel) {
		t.Fatalf("WithLock error = %v, want %v", err, sentinel)
	}

	// The lock must be released after a callback error.
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	called := false
	if err := WithLock(ctx, lockPath, func() error {
		called = true
		return nil
	}); err != nil {
		t.Fatalf("re-acquire lock: %v", err)
	}
	if !called {
		t.Fatal("callback was not called after re-acquiring the lock")
	}

	info, err := os.Lstat(lockPath)
	if err != nil {
		t.Fatalf("lock file was removed: %v", err)
	}
	if !info.Mode().IsRegular() {
		t.Fatalf("lock file mode = %v, want regular file", info.Mode())
	}
	if err := verifyPrivateFile(lockPath); err != nil {
		t.Fatalf("lock file is not private: %v", err)
	}
}

func TestWithLockCreatesParentDirectory(t *testing.T) {
	dir := t.TempDir()
	lockPath := filepath.Join(dir, "nested", "state", "state.lock")

	if err := WithLock(context.Background(), lockPath, func() error { return nil }); err != nil {
		t.Fatalf("WithLock: %v", err)
	}
	if err := verifyPrivateFile(lockPath); err != nil {
		t.Fatalf("lock file is not private: %v", err)
	}
	if runtime.GOOS != "windows" {
		info, err := os.Stat(filepath.Dir(lockPath))
		if err != nil {
			t.Fatalf("stat lock parent: %v", err)
		}
		if perm := info.Mode().Perm(); perm&0o077 != 0 {
			t.Errorf("lock parent mode = %o, want no group/other permissions", perm)
		}
	}
}

func TestWithLockDoesNotChmodExistingParent(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX directory modes are not meaningful on Windows")
	}

	dir := t.TempDir()
	parent := filepath.Join(dir, "existing")
	if err := os.Mkdir(parent, 0o755); err != nil {
		t.Fatalf("mkdir parent: %v", err)
	}
	if err := os.Chmod(parent, 0o755); err != nil {
		t.Fatalf("chmod parent: %v", err)
	}
	lockPath := filepath.Join(parent, "state.lock")

	if err := WithLock(context.Background(), lockPath, func() error { return nil }); err != nil {
		t.Fatalf("WithLock: %v", err)
	}
	info, err := os.Stat(parent)
	if err != nil {
		t.Fatalf("stat parent: %v", err)
	}
	if perm := info.Mode().Perm(); perm != 0o755 {
		t.Fatalf("existing parent mode changed to %o, want 0755", perm)
	}
}

func TestWithLockContextCancelWhileWaiting(t *testing.T) {
	dir := t.TempDir()
	lockPath := filepath.Join(dir, "state.lock")

	acquired := make(chan struct{})
	release := make(chan struct{})
	holderDone := make(chan error, 1)
	go func() {
		holderDone <- WithLock(context.Background(), lockPath, func() error {
			close(acquired)
			<-release
			return nil
		})
	}()

	select {
	case <-acquired:
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for the lock holder")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	called := false
	err := WithLock(ctx, lockPath, func() error {
		called = true
		return nil
	})
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("WithLock error = %v, want context.DeadlineExceeded", err)
	}
	if called {
		t.Fatal("callback ran while another holder had the lock")
	}

	close(release)
	if err := <-holderDone; err != nil {
		t.Fatalf("lock holder returned error: %v", err)
	}
}

func TestWithLockAlreadyCanceled(t *testing.T) {
	lockPath := filepath.Join(t.TempDir(), "state.lock")
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	called := false
	err := WithLock(ctx, lockPath, func() error {
		called = true
		return nil
	})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("WithLock error = %v, want context.Canceled", err)
	}
	if called {
		t.Fatal("callback ran with an already canceled context")
	}
}

func TestWithLockRejectsSymlink(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "target")
	mustWriteFile(t, target, []byte("target"))
	link := filepath.Join(dir, "state.lock")
	mustSymlink(t, target, link)

	called := false
	err := WithLock(context.Background(), link, func() error {
		called = true
		return nil
	})
	if err == nil {
		t.Fatal("WithLock(symlink) succeeded, want error")
	}
	if !errors.Is(err, errUnsafeFile) {
		t.Fatalf("WithLock(symlink) error = %v, want errUnsafeFile", err)
	}
	if called {
		t.Fatal("callback ran with an unsafe lock file")
	}
}

func TestWithLockRejectsNonRegularLockFile(t *testing.T) {
	dir := t.TempDir()

	called := false
	err := WithLock(context.Background(), dir, func() error {
		called = true
		return nil
	})
	if err == nil {
		t.Fatal("WithLock(directory) succeeded, want error")
	}
	if !errors.Is(err, errUnsafeFile) {
		t.Fatalf("WithLock(directory) error = %v, want errUnsafeFile", err)
	}
	if called {
		t.Fatal("callback ran with a non-regular lock file")
	}
}

func TestWithLockConcurrentIncrements(t *testing.T) {
	dir := t.TempDir()
	dataPath := filepath.Join(dir, "counter")
	lockPath := filepath.Join(dir, "counter.lock")
	if err := Write(dataPath, []byte("0")); err != nil {
		t.Fatalf("initialize counter: %v", err)
	}

	const (
		workers    = 8
		increments = 25
	)

	var wg sync.WaitGroup
	errs := make(chan error, workers)
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < increments; j++ {
				err := WithLock(context.Background(), lockPath, func() error {
					raw, err := Read(dataPath, 64)
					if err != nil {
						return err
					}
					n, err := strconv.Atoi(string(raw))
					if err != nil {
						return fmt.Errorf("parse counter %q: %w", raw, err)
					}
					return Write(dataPath, []byte(strconv.Itoa(n+1)))
				})
				if err != nil {
					errs <- err
					return
				}
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Fatalf("increment failed: %v", err)
	}

	raw, err := Read(dataPath, 64)
	if err != nil {
		t.Fatalf("read final counter: %v", err)
	}
	want := strconv.Itoa(workers * increments)
	if string(raw) != want {
		t.Fatalf("counter = %q, want %q", raw, want)
	}
	if _, err := os.Lstat(lockPath); err != nil {
		t.Fatalf("lock file was removed: %v", err)
	}
}

func TestWriteAtomicReadersNeverSeePartialData(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "data")
	payloadA := bytes.Repeat([]byte("A"), 64*1024)
	payloadB := bytes.Repeat([]byte("B"), 64*1024)
	if err := Write(path, payloadA); err != nil {
		t.Fatalf("initial write: %v", err)
	}

	stop := make(chan struct{})
	errCh := make(chan error, 4)
	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-stop:
					return
				default:
				}
				got, err := Read(path, int64(len(payloadA)))
				if err != nil {
					// A concurrent atomic replacement can land between Lstat
					// and open; the SameFile check fails closed, so retry.
					if errors.Is(err, errUnsafeFile) {
						continue
					}
					select {
					case errCh <- err:
					default:
					}
					return
				}
				if !bytes.Equal(got, payloadA) && !bytes.Equal(got, payloadB) {
					select {
					case errCh <- fmt.Errorf("torn read of %d bytes", len(got)):
					default:
					}
					return
				}
			}
		}()
	}

	for i := 0; i < 50; i++ {
		payload := payloadA
		if i%2 == 1 {
			payload = payloadB
		}
		if err := Write(path, payload); err != nil {
			if isRenameBlockedByOpenFile(err) {
				// Windows MoveFileEx cannot replace a destination that a
				// reader holds open. Write fails closed and keeps the old
				// content; there is deliberately no unsafe delete fallback.
				break
			}
			close(stop)
			wg.Wait()
			t.Fatalf("replace %d: %v", i, err)
		}
	}
	close(stop)
	wg.Wait()

	select {
	case err := <-errCh:
		t.Fatalf("reader failed: %v", err)
	default:
	}

	final, err := Read(path, int64(len(payloadA)))
	if err != nil {
		t.Fatalf("read final content: %v", err)
	}
	if !bytes.Equal(final, payloadA) && !bytes.Equal(final, payloadB) {
		t.Fatalf("final content is torn: %d bytes", len(final))
	}
}
