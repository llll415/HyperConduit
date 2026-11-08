//go:build !windows

package privatefile

import (
	"errors"
	"fmt"
	"os"

	"golang.org/x/sys/unix"
)

// tryLockFile attempts to take an exclusive, non-blocking flock. A false
// result with a nil error means the lock is currently held elsewhere.
func tryLockFile(f *os.File) (bool, error) {
	err := unix.Flock(int(f.Fd()), unix.LOCK_EX|unix.LOCK_NB)
	if err == nil {
		return true, nil
	}
	if errors.Is(err, unix.EWOULDBLOCK) || errors.Is(err, unix.EAGAIN) || errors.Is(err, unix.EINTR) {
		return false, nil
	}
	return false, &os.PathError{Op: "flock", Path: f.Name(), Err: err}
}

func unlockFile(f *os.File) error {
	if err := unix.Flock(int(f.Fd()), unix.LOCK_UN); err != nil {
		return &os.PathError{Op: "funlock", Path: f.Name(), Err: err}
	}
	return nil
}

// openFileNoFollow opens name without following a final-component symbolic
// link. unix.O_NOFOLLOW makes the rejection race-free at the syscall level.
func openFileNoFollow(name string, flag int, perm os.FileMode) (*os.File, error) {
	fd, err := unix.Open(name, flag|unix.O_NOFOLLOW|unix.O_CLOEXEC, uint32(perm.Perm()))
	if err != nil {
		return nil, &os.PathError{Op: "open", Path: name, Err: err}
	}
	return os.NewFile(uintptr(fd), name), nil
}

// createTempPrivate creates a temporary file in dir. On Unix, CreateTemp
// already uses mode 0600; secureFile is still called by Write to make the mode
// exact regardless of umask.
func createTempPrivate(dir, pattern string) (*os.File, error) {
	return os.CreateTemp(dir, pattern)
}

// secureFile tightens an existing regular file to mode 0600. The fchmod is
// performed on the already-open descriptor, so it cannot be redirected through
// a symlink swap.
func secureFile(f *os.File) error {
	if err := f.Chmod(privateFileMode); err != nil {
		return &os.PathError{Op: "chmod", Path: f.Name(), Err: err}
	}
	return nil
}

// verifyPrivateFile is used by tests to assert that a private file is exactly
// 0600 on Unix.
func verifyPrivateFile(path string) error {
	info, err := os.Stat(path)
	if err != nil {
		return err
	}
	if perm := info.Mode().Perm(); perm != privateFileMode {
		return fmt.Errorf("privatefile: %s mode = %o, want %o", path, perm, privateFileMode)
	}
	return nil
}

// isRenameBlockedByOpenFile is Windows-specific; Unix renames replace open
// files atomically.
func isRenameBlockedByOpenFile(error) bool {
	return false
}

// syncDir makes directory metadata (including a completed rename) durable on
// filesystems that support fsync on directories.
func syncDir(dir string) error {
	f, err := os.Open(dir)
	if err != nil {
		return err
	}
	syncErr := f.Sync()
	closeErr := f.Close()
	if syncErr != nil {
		return syncErr
	}
	return closeErr
}
