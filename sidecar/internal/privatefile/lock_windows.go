//go:build windows

package privatefile

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"
)

const (
	// FILE_SHARE_DELETE lets other handles request delete access. Note that
	// MoveFileEx still fails if the destination itself is open, so Write must
	// fail closed rather than fall back to delete-then-rename.
	privateShareMode uint32 = windows.FILE_SHARE_READ | windows.FILE_SHARE_WRITE | windows.FILE_SHARE_DELETE
	privateFileAttrs uint32 = windows.FILE_ATTRIBUTE_NORMAL | windows.FILE_FLAG_OPEN_REPARSE_POINT
)

// tryLockFile attempts to take an exclusive, non-blocking byte-range lock on
// the first byte of the file. A false result with a nil error means the lock is
// currently held elsewhere.
func tryLockFile(f *os.File) (bool, error) {
	var overlapped windows.Overlapped
	err := windows.LockFileEx(
		windows.Handle(f.Fd()),
		windows.LOCKFILE_EXCLUSIVE_LOCK|windows.LOCKFILE_FAIL_IMMEDIATELY,
		0,
		1,
		0,
		&overlapped,
	)
	if err == nil {
		return true, nil
	}
	if errors.Is(err, windows.ERROR_LOCK_VIOLATION) || errors.Is(err, windows.ERROR_SHARING_VIOLATION) {
		return false, nil
	}
	return false, &os.PathError{Op: "LockFileEx", Path: f.Name(), Err: err}
}

func unlockFile(f *os.File) error {
	var overlapped windows.Overlapped
	if err := windows.UnlockFileEx(windows.Handle(f.Fd()), 0, 1, 0, &overlapped); err != nil {
		return &os.PathError{Op: "UnlockFileEx", Path: f.Name(), Err: err}
	}
	return nil
}

// currentUserSIDString returns the string form of the current process token's
// user SID.
func currentUserSIDString() (string, error) {
	token := windows.GetCurrentProcessToken()
	user, err := token.GetTokenUser()
	if err != nil {
		return "", fmt.Errorf("privatefile: get current process token user: %w", err)
	}
	if user.User.Sid == nil || !user.User.Sid.IsValid() {
		return "", errors.New("privatefile: current process token user SID is invalid")
	}
	sid := user.User.Sid.String()
	if sid == "" {
		return "", errors.New("privatefile: convert current process token user SID")
	}
	return sid, nil
}

// privateDACLSDDL returns a protected DACL granting full access only to
// SYSTEM and the current user.
func privateDACLSDDL() (string, error) {
	sid, err := currentUserSIDString()
	if err != nil {
		return "", err
	}
	return "D:P(A;;FA;;;SY)(A;;FA;;;" + sid + ")", nil
}

// privateSecurityAttributes builds SECURITY_ATTRIBUTES carrying the protected
// private DACL. Passing these to CreateFile applies the DACL atomically at
// creation time.
func privateSecurityAttributes() (*windows.SecurityAttributes, error) {
	sddl, err := privateDACLSDDL()
	if err != nil {
		return nil, err
	}
	sd, err := windows.SecurityDescriptorFromString(sddl)
	if err != nil {
		return nil, fmt.Errorf("privatefile: build private security descriptor: %w", err)
	}
	return &windows.SecurityAttributes{
		Length:             uint32(unsafe.Sizeof(windows.SecurityAttributes{})),
		SecurityDescriptor: sd,
		InheritHandle:      0,
	}, nil
}

// withPrivateDACL builds the protected private DACL and invokes fn while the
// backing security descriptor is still alive.
func withPrivateDACL(fn func(dacl *windows.ACL) error) error {
	sddl, err := privateDACLSDDL()
	if err != nil {
		return err
	}
	sd, err := windows.SecurityDescriptorFromString(sddl)
	if err != nil {
		return fmt.Errorf("privatefile: build private security descriptor: %w", err)
	}
	dacl, _, err := sd.DACL()
	if err != nil {
		return fmt.Errorf("privatefile: read private security descriptor DACL: %w", err)
	}
	if dacl == nil {
		return errors.New("privatefile: private security descriptor has an empty DACL")
	}
	return fn(dacl)
}

// setPrivateDACL is the path-based variant, retained for tests and callers
// that only have a path. Production paths use setPrivateDACLHandle on an
// already validated handle to avoid a path-swap race.
func setPrivateDACL(path string) error {
	return withPrivateDACL(func(dacl *windows.ACL) error {
		if err := windows.SetNamedSecurityInfo(
			path,
			windows.SE_FILE_OBJECT,
			windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION,
			nil,
			nil,
			dacl,
			nil,
		); err != nil {
			return fmt.Errorf("privatefile: SetNamedSecurityInfo %s: %w", path, err)
		}
		return nil
	})
}

// setPrivateDACLHandle replaces the DACL of the object referenced by h. The
// handle has already been validated as a regular non-symlink, so no path can
// be swapped underneath the operation.
func setPrivateDACLHandle(h windows.Handle) error {
	return withPrivateDACL(func(dacl *windows.ACL) error {
		if err := windows.SetSecurityInfo(
			h,
			windows.SE_FILE_OBJECT,
			windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION,
			nil,
			nil,
			dacl,
			nil,
		); err != nil {
			return fmt.Errorf("privatefile: SetSecurityInfo: %w", err)
		}
		return nil
	})
}

// secureFile tightens an already-open file's DACL to the private DACL. Files
// created by this package already carry the private DACL; skipping the setter
// for them avoids unnecessary security calls on every read.
func secureFile(f *os.File) error {
	private, err := isPrivateDACL(f)
	if err != nil {
		return err
	}
	if private {
		return nil
	}
	return setPrivateDACLHandle(windows.Handle(f.Fd()))
}

// isPrivateDACL reports whether f already has a protected DACL whose access
// entries exactly match the private DACL.
func isPrivateDACL(f *os.File) (bool, error) {
	want, err := privateDACLSDDL()
	if err != nil {
		return false, err
	}
	wantSD, err := windows.SecurityDescriptorFromString(want)
	if err != nil {
		return false, fmt.Errorf("privatefile: build private security descriptor: %w", err)
	}
	gotSD, err := windows.GetSecurityInfo(windows.Handle(f.Fd()), windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION)
	if err != nil {
		return false, err
	}
	control, _, err := gotSD.Control()
	if err != nil {
		return false, err
	}
	if control&windows.SE_DACL_PROTECTED == 0 {
		return false, nil
	}
	wantDACL, _, err := wantSD.DACL()
	if err != nil {
		return false, err
	}
	gotDACL, _, err := gotSD.DACL()
	if err != nil {
		return false, err
	}
	return compareDACLs(wantDACL, gotDACL) == nil, nil
}

// compareDACLs compares two DACLs ACE by ACE. SDDL string comparison is not
// safe here: Windows canonicalizes well-known SIDs to aliases and may set the
// SE_DACL_AUTO_INHERITED control bit even for an equivalent protected DACL.
func compareDACLs(want, got *windows.ACL) error {
	if want == nil || got == nil {
		if want == got {
			return nil
		}
		return errors.New("privatefile: nil DACL mismatch")
	}
	if want.AceCount != got.AceCount {
		return fmt.Errorf("privatefile: ACE count = %d, want %d", got.AceCount, want.AceCount)
	}
	for i := uint32(0); i < uint32(want.AceCount); i++ {
		var wantACE, gotACE *windows.ACCESS_ALLOWED_ACE
		if err := windows.GetAce(want, i, &wantACE); err != nil {
			return fmt.Errorf("privatefile: read expected ACE %d: %w", i, err)
		}
		if err := windows.GetAce(got, i, &gotACE); err != nil {
			return fmt.Errorf("privatefile: read actual ACE %d: %w", i, err)
		}
		if wantACE.Header.AceType != gotACE.Header.AceType {
			return fmt.Errorf("privatefile: ACE %d type = %d, want %d", i, gotACE.Header.AceType, wantACE.Header.AceType)
		}
		if wantACE.Header.AceFlags != gotACE.Header.AceFlags {
			return fmt.Errorf("privatefile: ACE %d flags = %#x, want %#x", i, gotACE.Header.AceFlags, wantACE.Header.AceFlags)
		}
		if wantACE.Mask != gotACE.Mask {
			return fmt.Errorf("privatefile: ACE %d mask = %#x, want %#x", i, gotACE.Mask, wantACE.Mask)
		}
		wantSID := (*windows.SID)(unsafe.Pointer(&wantACE.SidStart))
		gotSID := (*windows.SID)(unsafe.Pointer(&gotACE.SidStart))
		if !wantSID.Equals(gotSID) {
			return fmt.Errorf("privatefile: ACE %d SID = %s, want %s", i, gotSID.String(), wantSID.String())
		}
	}
	return nil
}

// createWindowsFile wraps windows.CreateFile with the private DACL and a share
// mode that includes FILE_SHARE_DELETE, allowing atomic renames while readers
// hold the file open.
func createWindowsFile(name string, access, createmode uint32) (*os.File, error) {
	// READ_CONTROL lets secureFile inspect the DACL and WRITE_DAC lets it
	// tighten an existing file. Fail closed if the current user cannot obtain
	// these rights on the private file.
	access |= windows.READ_CONTROL | windows.WRITE_DAC

	namep, err := windows.UTF16PtrFromString(name)
	if err != nil {
		return nil, &os.PathError{Op: "CreateFile", Path: name, Err: err}
	}
	sa, err := privateSecurityAttributes()
	if err != nil {
		return nil, err
	}
	h, err := windows.CreateFile(namep, access, privateShareMode, sa, createmode, privateFileAttrs, 0)
	if err != nil {
		return nil, &os.PathError{Op: "CreateFile", Path: name, Err: err}
	}
	return os.NewFile(uintptr(h), name), nil
}

// openFileNoFollow opens name without following a final-component symbolic
// link. FILE_FLAG_OPEN_REPARSE_POINT makes the handle refer to the reparse
// point itself, which the caller then rejects via Stat.
func openFileNoFollow(name string, flag int, _ os.FileMode) (*os.File, error) {
	var access uint32
	switch {
	case flag&os.O_RDWR != 0:
		access = windows.GENERIC_READ | windows.GENERIC_WRITE
	case flag&os.O_WRONLY != 0:
		access = windows.GENERIC_WRITE
	default:
		access = windows.GENERIC_READ
	}
	if flag&os.O_CREATE != 0 {
		access |= windows.GENERIC_WRITE
	}

	var createmode uint32
	switch {
	case flag&(os.O_CREATE|os.O_EXCL) == (os.O_CREATE | os.O_EXCL):
		createmode = windows.CREATE_NEW
	case flag&os.O_CREATE != 0:
		createmode = windows.OPEN_ALWAYS
	default:
		createmode = windows.OPEN_EXISTING
	}
	return createWindowsFile(name, access, createmode)
}

// createTempPrivate creates a new temporary file with the private DACL applied
// atomically by CreateFile. The pattern follows os.CreateTemp semantics: the
// last '*' is replaced by random characters, or the random suffix is appended.
func createTempPrivate(dir, pattern string) (*os.File, error) {
	prefix, suffix := pattern, ""
	if i := strings.LastIndex(pattern, "*"); i >= 0 {
		prefix, suffix = pattern[:i], pattern[i+1:]
	}
	if strings.ContainsAny(prefix+suffix, `/\`) {
		return nil, &os.PathError{Op: "createtemp", Path: pattern, Err: errors.New("pattern contains path separator")}
	}

	var random [8]byte
	for i := 0; i < 10000; i++ {
		if _, err := rand.Read(random[:]); err != nil {
			return nil, fmt.Errorf("privatefile: generate temporary file name: %w", err)
		}
		name := filepath.Join(dir, prefix+hex.EncodeToString(random[:])+suffix)
		f, err := createWindowsFile(name, windows.GENERIC_READ|windows.GENERIC_WRITE, windows.CREATE_NEW)
		if err == nil {
			return f, nil
		}
		if errors.Is(err, windows.ERROR_FILE_EXISTS) || errors.Is(err, windows.ERROR_ALREADY_EXISTS) {
			continue
		}
		return nil, err
	}
	return nil, errors.New("privatefile: unable to create a unique temporary file")
}

// verifyPrivateFile is used by tests to assert that the DACL is exactly the
// protected SYSTEM + current-user DACL and no inherited entries remain.
func verifyPrivateFile(path string) error {
	want, err := privateDACLSDDL()
	if err != nil {
		return err
	}
	wantSD, err := windows.SecurityDescriptorFromString(want)
	if err != nil {
		return fmt.Errorf("privatefile: build private security descriptor: %w", err)
	}
	gotSD, err := windows.GetNamedSecurityInfo(path, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION)
	if err != nil {
		return err
	}
	control, _, err := gotSD.Control()
	if err != nil {
		return err
	}
	if control&windows.SE_DACL_PROTECTED == 0 {
		return fmt.Errorf("privatefile: %s DACL is not protected", path)
	}
	wantDACL, _, err := wantSD.DACL()
	if err != nil {
		return err
	}
	gotDACL, _, err := gotSD.DACL()
	if err != nil {
		return err
	}
	if err := compareDACLs(wantDACL, gotDACL); err != nil {
		return fmt.Errorf("privatefile: %s DACL mismatch: %w", path, err)
	}
	return nil
}

// isRenameBlockedByOpenFile reports whether err is the expected Windows
// failure when os.Rename cannot replace a destination that another handle has
// open. Write returns this error as-is and leaves the old destination intact.
func isRenameBlockedByOpenFile(err error) bool {
	return errors.Is(err, windows.ERROR_ACCESS_DENIED) || errors.Is(err, windows.ERROR_SHARING_VIOLATION)
}

// syncDir is a no-op on Windows; directory handles do not support FlushFileBuffers.
func syncDir(string) error {
	return nil
}
