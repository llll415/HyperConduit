// Package privatefile provides small, fail-closed helpers for reading,
// writing, and locking private files.
//
// All operations reject symbolic links and other non-regular files. Writes
// are performed through a temporary file in the destination directory and an
// atomic rename, so a failed write never truncates or replaces existing data.
package privatefile

import (
	"context"
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"
	"time"
)

const (
	privateDirMode   os.FileMode = 0o700
	privateFileMode  os.FileMode = 0o600
	lockPollInterval             = 10 * time.Millisecond
)

var (
	errUnsafeFile   = errors.New("privatefile: unsafe file")
	errFileTooLarge = errors.New("privatefile: file exceeds maximum size")
)

// Read reads the regular file at path. It refuses symbolic links and other
// non-regular files, and fails if the file is larger than maxBytes. The size
// limit is also enforced while reading so a file that grows concurrently
// cannot exceed the limit. os.ErrNotExist is preserved for missing files.
func Read(path string, maxBytes int64) ([]byte, error) {
	if maxBytes < 0 {
		return nil, fmt.Errorf("privatefile: negative maxBytes %d", maxBytes)
	}

	f, err := openExistingRegular(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	// Tighten an existing private file before reading it. This fails closed if
	// the file cannot be secured.
	if err := secureFile(f); err != nil {
		return nil, fmt.Errorf("privatefile: secure %s: %w", path, err)
	}

	info, err := f.Stat()
	if err != nil {
		return nil, fmt.Errorf("privatefile: stat %s: %w", path, err)
	}
	if info.Size() > maxBytes {
		return nil, fmt.Errorf("%w: %s", errFileTooLarge, path)
	}

	// Read at most one byte beyond the limit so growth is detected without
	// trusting the size reported by stat.
	limit := maxBytes
	if maxBytes < math.MaxInt64 {
		limit = maxBytes + 1
	}
	data, err := io.ReadAll(io.LimitReader(f, limit))
	if err != nil {
		return nil, fmt.Errorf("privatefile: read %s: %w", path, err)
	}
	if int64(len(data)) > maxBytes {
		return nil, fmt.Errorf("%w: %s", errFileTooLarge, path)
	}
	return data, nil
}

// Write atomically writes data to path. The parent directory is created with
// mode 0700 when missing; an existing parent directory is left untouched. The
// temporary file is created in the same directory with mode 0600, synced, and
// renamed over the destination. If any step fails, the previous destination is
// left intact and only the temporary file created by this call is removed.
func Write(path string, data []byte) error {
	if path == "" {
		return errors.New("privatefile: empty path")
	}

	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, privateDirMode); err != nil {
		return fmt.Errorf("privatefile: create parent directory for %s: %w", path, err)
	}
	if err := rejectUnsafeExisting(path); err != nil {
		return err
	}

	tmp, err := createTempPrivate(dir, "."+filepath.Base(path)+".tmp-*")
	if err != nil {
		return fmt.Errorf("privatefile: create temporary file in %s: %w", dir, err)
	}
	tmpPath := tmp.Name()
	committed := false
	defer func() {
		if !committed {
			_ = os.Remove(tmpPath)
		}
	}()

	// Tighten the temporary file before any data is written to it. On Windows
	// createTempPrivate also applies the private DACL atomically at creation;
	// this call keeps the Unix mode exact and is defense in depth elsewhere.
	if err := secureFile(tmp); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("privatefile: secure temporary file for %s: %w", path, err)
	}

	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("privatefile: write temporary file for %s: %w", path, err)
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("privatefile: sync temporary file for %s: %w", path, err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("privatefile: close temporary file for %s: %w", path, err)
	}
	// os.Rename uses MoveFileEx(MOVEFILE_REPLACE_EXISTING) on Windows. If the
	// destination is held open by another handle, that call fails; the error is
	// returned as-is and the old destination remains intact. There is no unsafe
	// delete-then-rename fallback.
	if err := os.Rename(tmpPath, path); err != nil {
		return fmt.Errorf("privatefile: replace %s: %w", path, err)
	}
	committed = true

	// Best effort: make the rename durable on Unix. Some filesystems do not
	// support fsync on directories, so failure here is intentionally ignored.
	_ = syncDir(dir)
	return nil
}

// WithLock acquires an exclusive advisory lock on the lock file at path, runs
// fn, and then releases the lock. path is the full path of the lock file; it is
// created with mode 0600 when missing and is deliberately never removed, which
// avoids stale-lock and inode-reuse races. The operating system releases the
// lock if the process crashes.
//
// While another process (or another file descriptor) holds the lock, WithLock
// polls every 10ms until the lock is available or ctx is done. Context errors
// are returned unchanged. If fn returns an error, that error is returned as-is
// after the lock has been released.
func WithLock(ctx context.Context, path string, fn func() error) error {
	if path == "" {
		return errors.New("privatefile: empty lock path")
	}
	if fn == nil {
		return errors.New("privatefile: nil lock callback")
	}
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return err
	}

	f, err := openLockFile(path)
	if err != nil {
		return err
	}

	// Tighten the lock file before taking the advisory lock. Newly created
	// lock files already receive a private DACL at creation on Windows; this
	// also tightens an existing lock file and enforces 0600 on Unix.
	if err := secureFile(f); err != nil {
		_ = f.Close()
		return fmt.Errorf("privatefile: secure lock file %s: %w", path, err)
	}

	released := false
	defer func() {
		// Guarantee the OS lock is dropped even if fn panics.
		if !released {
			_ = unlockFile(f)
			_ = f.Close()
		}
	}()

	if err := acquireLock(ctx, f); err != nil {
		return err
	}

	callbackErr := fn()
	unlockErr := unlockFile(f)
	closeErr := f.Close()
	released = true

	if callbackErr != nil {
		return callbackErr
	}
	if unlockErr != nil {
		return unlockErr
	}
	return closeErr
}

// acquireLock polls the non-blocking native lock until it succeeds or ctx is
// canceled.
func acquireLock(ctx context.Context, f *os.File) error {
	ticker := time.NewTicker(lockPollInterval)
	defer ticker.Stop()

	for {
		acquired, err := tryLockFile(f)
		if err != nil {
			return err
		}
		if acquired {
			return nil
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
		}
	}
}

// openExistingRegular opens an existing regular file without following a
// final-component symbolic link. Errors from Lstat are returned unchanged so
// callers can use errors.Is(err, os.ErrNotExist).
func openExistingRegular(path string) (*os.File, error) {
	if path == "" {
		return nil, errors.New("privatefile: empty path")
	}

	before, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !before.Mode().IsRegular() {
		return nil, unsafeFileError(path, "not a regular file")
	}

	f, err := openFileNoFollow(path, os.O_RDONLY, 0)
	if err != nil {
		return nil, err
	}
	info, err := f.Stat()
	if err != nil {
		_ = f.Close()
		return nil, fmt.Errorf("privatefile: stat %s: %w", path, err)
	}
	if !info.Mode().IsRegular() {
		_ = f.Close()
		return nil, unsafeFileError(path, "not a regular file after open")
	}
	// Detect the path being replaced between Lstat and open. This closes the
	// remaining symlink/regular-file swap window at the cost of failing closed
	// if a concurrent atomic replace happens at exactly this moment.
	if !os.SameFile(before, info) {
		_ = f.Close()
		return nil, unsafeFileError(path, "file replaced while opening")
	}
	return f, nil
}

// openLockFile opens or creates the lock file without following a symlink.
// The lock file's parent directory is created with mode 0700 when missing; an
// existing parent directory is never chmodded or otherwise broadened/narrowed.
func openLockFile(path string) (*os.File, error) {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, privateDirMode); err != nil {
		return nil, fmt.Errorf("privatefile: create lock directory for %s: %w", path, err)
	}

	if info, err := os.Lstat(path); err == nil {
		if !info.Mode().IsRegular() {
			return nil, unsafeFileError(path, "lock file is not a regular file")
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}

	f, err := openFileNoFollow(path, os.O_RDWR|os.O_CREATE, privateFileMode)
	if err != nil {
		return nil, err
	}
	info, err := f.Stat()
	if err != nil {
		_ = f.Close()
		return nil, fmt.Errorf("privatefile: stat lock file %s: %w", path, err)
	}
	if !info.Mode().IsRegular() {
		_ = f.Close()
		return nil, unsafeFileError(path, "lock file is not a regular file after open")
	}
	return f, nil
}

// rejectUnsafeExisting fails closed if path already exists and is not a
// regular file. A missing path is fine.
func rejectUnsafeExisting(path string) error {
	info, err := os.Lstat(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return err
	}
	if !info.Mode().IsRegular() {
		return unsafeFileError(path, "destination is not a regular file")
	}
	return nil
}

func unsafeFileError(path, reason string) error {
	return fmt.Errorf("%w: %s: %s", errUnsafeFile, path, reason)
}
