package tunnel

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"testing"

	coreerrors "github.com/apernet/hysteria/core/v2/errors"
	"github.com/apernet/quic-go"
	"github.com/llll415/HyperConduit/sidecar/internal/trust"
)

// timeoutTestError is a net.Error whose Timeout method reports true.
type timeoutTestError struct{}

func (timeoutTestError) Error() string   { return "i/o timeout" }
func (timeoutTestError) Timeout() bool   { return true }
func (timeoutTestError) Temporary() bool { return true }

// secretNetTestError is a non-timeout net.Error that carries a token and a
// private target in its message, mirroring raw network failures.
type secretNetTestError struct{}

func (secretNetTestError) Error() string {
	return "dial tcp 10.20.30.40:25565 with token supersecret"
}
func (secretNetTestError) Timeout() bool   { return false }
func (secretNetTestError) Temporary() bool { return false }

func TestClassifyErrorNil(t *testing.T) {
	if got := classifyError("connect", nil); got != nil {
		t.Fatalf("classifyError(nil) = %v, want nil", got)
	}
}

func TestClassifyErrorCodes(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want ErrorCode
	}{
		{"context canceled", context.Canceled, CodeCanceled},
		{"context deadline", context.DeadlineExceeded, CodeTimeout},
		{"os deadline", os.ErrDeadlineExceeded, CodeTimeout},
		{"net timeout", timeoutTestError{}, CodeTimeout},
		{"x509 unknown authority", x509.UnknownAuthorityError{}, CodeTLS},
		{"x509 hostname", x509.HostnameError{}, CodeTLS},
		{"x509 certificate invalid", x509.CertificateInvalidError{}, CodeTLS},
		{"tls verification pointer", &tls.CertificateVerificationError{}, CodeTLS},
		{"core auth", coreerrors.AuthError{StatusCode: 401}, CodeAuthentication},
		{"changed identity", &trust.ChangedError{}, CodeIdentity},
		{"trust storage", &trust.StorageError{Err: errors.New("private")}, CodeStorage},
		{"core config", coreerrors.ConfigError{Field: "token", Reason: "missing"}, CodeConfig},
		{"core dial", coreerrors.DialError{Message: "private backend"}, CodeTarget},
		{"quic stream limit value", quic.StreamLimitReachedError{}, CodeLimit},
		{"core admission limit", coreerrors.LimitError{}, CodeLimit},
		{"quic stream limit pointer", &quic.StreamLimitReachedError{}, CodeLimit},
		{"unknown transport", errors.New("unexpected EOF"), CodeTransport},
		{"secret net error", secretNetTestError{}, CodeTransport},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			result := classifyError("connect", tc.err)
			got, ok := result.(*Error)
			if !ok {
				t.Fatalf("classifyError returned %T, want *Error", result)
			}
			if got.Code != tc.want {
				t.Fatalf("Code = %q, want %q", got.Code, tc.want)
			}
			if got.Op != "connect" {
				t.Fatalf("Op = %q, want %q", got.Op, "connect")
			}
		})
	}
}

func TestClassifyErrorIdempotent(t *testing.T) {
	existing := &Error{Code: CodeTLS, Op: "connect", Err: errors.New("secret")}
	if got := classifyError("other", existing); got != existing {
		t.Fatalf("already classified error was replaced: %p != %p", got, existing)
	}

	wrapped := fmt.Errorf("secret outer message: %w", existing)
	if got := classifyError("other", wrapped); got != existing {
		t.Fatalf("classified error was not extracted: %p != %p", got, existing)
	}
}

func TestClassifyErrorNestedWrap(t *testing.T) {
	wrapped := fmt.Errorf("connect: %w", fmt.Errorf("handshake: %w", x509.UnknownAuthorityError{}))
	got := classifyError("connect", wrapped)
	classified, ok := got.(*Error)
	if !ok {
		t.Fatalf("classifyError returned %T, want *Error", got)
	}
	if classified.Code != CodeTLS {
		t.Fatalf("Code = %q, want %q", classified.Code, CodeTLS)
	}
	if !errors.Is(classified, wrapped) {
		t.Fatal("wrapped original error is no longer reachable")
	}
	var unknownAuthority x509.UnknownAuthorityError
	if !errors.As(classified, &unknownAuthority) {
		t.Fatal("x509 error is no longer reachable through Unwrap")
	}
}

func TestClassifyErrorJoinPrecedence(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want ErrorCode
	}{
		{
			name: "cancel before tls and timeout",
			err:  errors.Join(context.Canceled, &tls.CertificateVerificationError{}, timeoutTestError{}),
			want: CodeCanceled,
		},
		{
			name: "tls before timeout",
			err:  errors.Join(&tls.CertificateVerificationError{}, timeoutTestError{}),
			want: CodeTLS,
		},
		{
			name: "timeout before transport",
			err:  errors.Join(os.ErrDeadlineExceeded, errors.New("other")),
			want: CodeTimeout,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, ok := classifyError("connect", tc.err).(*Error)
			if !ok {
				t.Fatal("classifyError did not return *Error")
			}
			if got.Code != tc.want {
				t.Fatalf("Code = %q, want %q", got.Code, tc.want)
			}
		})
	}
}

func TestErrorUnwrapPreservesSentinel(t *testing.T) {
	sentinel := errors.New("sentinel")
	got := classifyError("read", fmt.Errorf("copy: %w", sentinel))
	classified, ok := got.(*Error)
	if !ok {
		t.Fatalf("classifyError returned %T, want *Error", got)
	}
	if !errors.Is(classified, sentinel) {
		t.Fatal("sentinel is not reachable with errors.Is")
	}

	var target *Error
	if !errors.As(got, &target) || target != classified {
		t.Fatal("*Error is not reachable with errors.As")
	}
}

func TestErrorDoesNotLeakUnderlying(t *testing.T) {
	const token = "supersecret-token"
	const target = "10.20.30.40:25565"

	cases := []struct {
		name string
		err  error
		want string
	}{
		{
			name: "tls",
			err:  fmt.Errorf("certificate for %s rejected while using %s", target, token),
			want: "connect: TLS verification failed",
		},
		{
			name: "dial",
			err:  coreerrors.DialError{Message: token + " -> " + target},
			want: "connect: target unavailable",
		},
		{
			name: "raw net",
			err:  secretNetTestError{},
			want: "connect: transport failure",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var got error
			if tc.name == "tls" {
				// Force the TLS classification without relying on string parsing.
				got = classifyError("connect", errors.Join(x509.UnknownAuthorityError{}, tc.err))
			} else {
				got = classifyError("connect", tc.err)
			}
			if got == nil {
				t.Fatal("classifyError returned nil")
			}
			message := got.Error()
			if message != tc.want {
				t.Fatalf("Error() = %q, want %q", message, tc.want)
			}
			if strings.Contains(message, token) || strings.Contains(message, target) {
				t.Fatalf("Error() leaked sensitive data: %q", message)
			}
		})
	}
}

func TestErrorDescriptions(t *testing.T) {
	cases := []struct {
		code ErrorCode
		want string
	}{
		{CodeConfig, "invalid configuration"},
		{CodeTLS, "TLS verification failed"},
		{CodeIdentity, "server identity changed"},
		{CodeStorage, "identity or trust storage unavailable"},
		{CodeAuthentication, "authentication failed"},
		{CodeTimeout, "operation timed out"},
		{CodeCanceled, "operation canceled"},
		{CodeLimit, "connection limit reached"},
		{CodeTarget, "target unavailable"},
		{CodeTransport, "transport failure"},
		{ErrorCode("future"), "tunnel error"},
	}

	for _, tc := range cases {
		t.Run(string(tc.code), func(t *testing.T) {
			err := &Error{Code: tc.code, Op: "connect"}
			if got := err.Error(); got != "connect: "+tc.want {
				t.Fatalf("Error() = %q, want %q", got, "connect: "+tc.want)
			}
			noOp := &Error{Code: tc.code}
			if got := noOp.Error(); got != tc.want {
				t.Fatalf("Error() without Op = %q, want %q", got, tc.want)
			}
		})
	}
}

func TestErrorNilReceiver(t *testing.T) {
	var err *Error
	if got := err.Error(); got != "tunnel error" {
		t.Fatalf("nil Error() = %q, want %q", got, "tunnel error")
	}
	if got := err.Unwrap(); got != nil {
		t.Fatalf("nil Unwrap() = %v, want nil", got)
	}
}

func TestErrorJSONSanitized(t *testing.T) {
	err := &Error{
		Code: CodeTarget,
		Op:   "connect",
		Err:  errors.New("token=supersecret target=10.20.30.40:25565"),
	}

	data, marshalErr := json.Marshal(err)
	if marshalErr != nil {
		t.Fatalf("json.Marshal: %v", marshalErr)
	}
	if strings.Contains(string(data), "supersecret") || strings.Contains(string(data), "10.20.30.40") {
		t.Fatalf("JSON leaked sensitive data: %s", data)
	}

	var decoded map[string]any
	if unmarshalErr := json.Unmarshal(data, &decoded); unmarshalErr != nil {
		t.Fatalf("json.Unmarshal: %v", unmarshalErr)
	}
	if decoded["code"] != "target" {
		t.Fatalf("JSON code = %v, want %q", decoded["code"], "target")
	}
	if decoded["op"] != "connect" {
		t.Fatalf("JSON op = %v, want %q", decoded["op"], "connect")
	}
	if _, ok := decoded["Err"]; ok {
		t.Fatal("JSON unexpectedly contains Err")
	}
}
