package tunnel

import (
	"context"
	"errors"
	"io"
	"net"
	"time"
)

// prefaceWriteWindow bounds a preface write when the caller supplied no
// deadline. The stream/request timeout remains the normal upper bound.
const prefaceWriteWindow = 5 * time.Second

// writePrefaceFrame writes one complete preface frame before any application
// bytes are forwarded. Both the private HCMETA01 bridge preface and the public
// PROXY protocol v2 header use it, so the two formats share exactly one
// short-write, deadline, and cancellation implementation.
//
// The write is bounded by ctx's deadline, or prefaceWriteWindow when ctx has
// none. Cancelling ctx interrupts a blocked write by installing an immediate
// write deadline; the cancellation callback is joined before the deadline is
// cleared so it cannot race with the connection being handed to the normal copy
// loop. On any failure the caller must discard the connection: a partially
// written frame is never followed by application data.
func writePrefaceFrame(ctx context.Context, conn net.Conn, frame []byte) error {
	if conn == nil {
		return errors.New("preface: nil connection")
	}
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return err
	}

	deadline, ok := ctx.Deadline()
	if !ok {
		deadline = time.Now().Add(prefaceWriteWindow)
	}
	if err := conn.SetWriteDeadline(deadline); err != nil {
		return err
	}

	canceled := make(chan struct{})
	stopCancel := context.AfterFunc(ctx, func() {
		defer close(canceled)
		_ = conn.SetWriteDeadline(time.Now())
	})
	defer func() {
		// Join the callback before clearing the deadline. Otherwise a callback
		// that started just as the write finished could install a past deadline
		// after the connection was handed to the copy loop.
		if !stopCancel() {
			<-canceled
		}
		_ = conn.SetWriteDeadline(time.Time{})
	}()

	if err := writePrefaceAll(conn, frame); err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return ctxErr
		}
		// The connection deadline and the context deadline may fire at the same
		// instant; the write can observe the timeout before ctx.Err() is set.
		if deadline, ok := ctx.Deadline(); ok && !time.Now().Before(deadline) {
			return context.DeadlineExceeded
		}
		return err
	}
	return nil
}

// writePrefaceAll handles short writes without relying on WriterTo/ReaderFrom
// optimizations. A writer that returns (0, nil) is treated as a short write so
// the preface can never loop forever.
func writePrefaceAll(w io.Writer, p []byte) error {
	for len(p) > 0 {
		n, err := w.Write(p)
		if n < 0 || n > len(p) {
			return io.ErrShortWrite
		}
		p = p[n:]
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
	}
	return nil
}
