package integration_tests

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
	"go.uber.org/goleak"
)

func TestStressTCPTransfer(t *testing.T) {
	for _, behavior := range []string{"echo", "corrupt", "early_close", "write_error", "blocked_read_and_write"} {
		t.Run(behavior, func(t *testing.T) {
			defer goleak.VerifyNone(t, goleak.IgnoreCurrent())
			ctx, cancel := context.WithTimeout(t.Context(), time.Second)
			defer cancel()
			a, b := net.Pipe()
			defer b.Close()
			started, release := make(chan struct{}), make(chan struct{})
			conn := &stressWriteSignalConn{Conn: a, signal: sync.OnceFunc(func() { close(started) })}
			writeFailure := errors.New("injected write failure")
			if behavior == "write_error" {
				conn.writeErr = writeFailure
			}
			done := make(chan struct{})
			go func() {
				defer close(done)
				defer b.Close()
				switch behavior {
				case "echo":
					_, _ = io.Copy(b, b)
				case "corrupt":
					buf := make([]byte, stressBufferSize)
					n, _ := b.Read(buf)
					buf[0] ^= 0xff
					_, _ = b.Write(buf[:n])
				case "blocked_read_and_write":
					// Leave the peer open and idle until the transfer has returned.
					// Only cancellation can release the blocked Read and Write.
					<-started
					cancel()
					<-release
				case "write_error":
					<-release
				}
			}()
			err := stressTCPTransfer(ctx, conn, 2*stressBufferSize+7, 42)
			close(release)
			<-done
			if behavior == "echo" {
				require.NoError(t, err)
			} else {
				require.Error(t, err)
				if behavior == "corrupt" {
					require.ErrorContains(t, err, "payload mismatch")
				}
				if behavior == "write_error" {
					require.ErrorIs(t, err, writeFailure)
				}
			}
		})
	}
}

type stressWriteSignalConn struct {
	net.Conn
	signal   func()
	writeErr error
}

func (c *stressWriteSignalConn) Write(data []byte) (int, error) {
	c.signal()
	if c.writeErr != nil {
		return 0, c.writeErr
	}
	return c.Conn.Write(data)
}

func TestStressWorkersJoinOnFailure(t *testing.T) {
	defer goleak.VerifyNone(t, goleak.IgnoreCurrent())
	ctx, cancel := context.WithCancelCause(t.Context())
	defer cancel(nil)
	failure := errors.New("injected failure")
	err := runStressWorkers(ctx, cancel, 3, 8, func(ctx context.Context, id int) error {
		if id == 0 {
			return failure
		}
		<-ctx.Done()
		return context.Cause(ctx)
	})
	require.ErrorIs(t, err, failure)
	require.ErrorContains(t, err, "iteration 0 worker 0")
}

func TestStressWorkersKeepDeadlineDetails(t *testing.T) {
	ctx, cancel := context.WithCancelCause(t.Context())
	defer cancel(nil)
	err := runStressWorkers(ctx, cancel, 1, 1, func(context.Context, int) error {
		cancel(context.DeadlineExceeded)
		return io.ErrUnexpectedEOF
	})
	require.ErrorIs(t, err, context.DeadlineExceeded)
	require.ErrorIs(t, err, io.ErrUnexpectedEOF)
	require.ErrorContains(t, err, "iteration 0 worker 0")
}
