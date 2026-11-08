package tunnel

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"net"
	"os"

	coreerrors "github.com/apernet/hysteria/core/v2/errors"
	"github.com/apernet/quic-go"
)

// ErrorCode is a fixed, non-sensitive classification of a tunnel failure.
// Callers can branch on it without inspecting the original error, whose text
// may contain credentials or private target addresses.
type ErrorCode string

const (
	CodeConfig         ErrorCode = "config"
	CodeTLS            ErrorCode = "tls"
	CodeAuthentication ErrorCode = "authentication"
	CodeTimeout        ErrorCode = "timeout"
	CodeCanceled       ErrorCode = "canceled"
	CodeLimit          ErrorCode = "limit"
	CodeTarget         ErrorCode = "target"
	CodeTransport      ErrorCode = "transport"
)

// Error is a classified tunnel error.
//
// Error() intentionally never includes the wrapped Err: it may carry a token
// or a private target address, and that text must not reach logs, protocol
// responses, or user-facing output. Err is kept only so that errors.Is and
// errors.As continue to work; it is excluded from JSON for the same reason.
type Error struct {
	Code ErrorCode `json:"code"`
	Op   string    `json:"op"`
	Err  error     `json:"-"`
}

// Error returns a fixed safe description prefixed by the internal operation
// name. It never calls Err.Error().
func (e *Error) Error() string {
	if e == nil {
		return "tunnel error"
	}
	description := e.Code.description()
	if e.Op == "" {
		return description
	}
	return e.Op + ": " + description
}

// Unwrap exposes the original error to errors.Is and errors.As only.
func (e *Error) Unwrap() error {
	if e == nil {
		return nil
	}
	return e.Err
}

// description maps every code to a constant string with no error details.
func (c ErrorCode) description() string {
	switch c {
	case CodeConfig:
		return "invalid configuration"
	case CodeTLS:
		return "TLS verification failed"
	case CodeAuthentication:
		return "authentication failed"
	case CodeTimeout:
		return "operation timed out"
	case CodeCanceled:
		return "operation canceled"
	case CodeLimit:
		return "connection limit reached"
	case CodeTarget:
		return "target unavailable"
	case CodeTransport:
		return "transport failure"
	default:
		return "tunnel error"
	}
}

// classifyError wraps err in an *Error carrying op and a safe ErrorCode.
//
// A nil error stays nil. Existing classifications discard any outer wrapper
// text, which may itself contain sensitive data.
func classifyError(op string, err error) error {
	if err == nil {
		return nil
	}
	var classified *Error
	if errors.As(err, &classified) && classified != nil {
		return classified
	}
	return &Error{Code: classifyCode(err), Op: op, Err: err}
}

// classifyCode applies a fixed precedence so a combined error has one stable
// classification: cancellation, then certificate verification, then timeout,
// then the core error types, then the QUIC stream limit, and finally transport.
func classifyCode(err error) ErrorCode {
	switch {
	case errors.Is(err, context.Canceled):
		return CodeCanceled
	case isCertificateError(err):
		return CodeTLS
	case errors.Is(err, context.DeadlineExceeded),
		errors.Is(err, os.ErrDeadlineExceeded),
		isTimeout(err):
		return CodeTimeout
	}

	var authErr coreerrors.AuthError
	if errors.As(err, &authErr) {
		return CodeAuthentication
	}

	var configErr coreerrors.ConfigError
	if errors.As(err, &configErr) {
		return CodeConfig
	}

	// The server-supplied DialError message is never parsed: it can describe a
	// private backend and must not influence classification or output.
	var dialErr coreerrors.DialError
	if errors.As(err, &dialErr) {
		return CodeTarget
	}

	var streamLimit quic.StreamLimitReachedError
	var admissionLimit coreerrors.LimitError
	if errors.As(err, &admissionLimit) {
		return CodeLimit
	}
	if errors.As(err, &streamLimit) {
		return CodeLimit
	}
	var streamLimitPtr *quic.StreamLimitReachedError
	if errors.As(err, &streamLimitPtr) {
		return CodeLimit
	}

	return CodeTransport
}

// isCertificateError recognizes both the x509 value errors returned by the
// standard library and the pointer-form tls.CertificateVerificationError.
func isCertificateError(err error) bool {
	var unknownAuthority x509.UnknownAuthorityError
	if errors.As(err, &unknownAuthority) {
		return true
	}
	var hostname x509.HostnameError
	if errors.As(err, &hostname) {
		return true
	}
	var invalid x509.CertificateInvalidError
	if errors.As(err, &invalid) {
		return true
	}
	var verification *tls.CertificateVerificationError
	return errors.As(err, &verification)
}

func isTimeout(err error) bool {
	var netErr net.Error
	return errors.As(err, &netErr) && netErr.Timeout()
}
