package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"time"

	"github.com/llll415/HyperConduit/sidecar/internal/config"
)

// managedMaxCommandBytes bounds a single control line. A managed parent must
// never be able to grow this process's memory by writing an endless line.
const managedMaxCommandBytes = 4 << 10

// managedCommand is the only control message accepted on stdin. Credentials
// are never read from stdin; configuration remains file-only.
type managedCommand struct {
	Version int    `json:"version"`
	Command string `json:"command"`
}

// readManagedCommand reports whether r supplied the single supported shutdown
// command. Every other outcome (EOF, a read error, malformed JSON, unknown
// fields, an unknown version or command, or an oversized line) is reported as
// false. Callers treat false as a fail-closed stop request. The raw input is
// never logged or included in an error.
func readManagedCommand(r io.Reader) bool {
	scanner := bufio.NewScanner(r)
	scanner.Buffer(make([]byte, 0, managedMaxCommandBytes), managedMaxCommandBytes)
	if !scanner.Scan() {
		return false
	}
	decoder := json.NewDecoder(strings.NewReader(scanner.Text()))
	decoder.DisallowUnknownFields()
	var command managedCommand
	if err := decoder.Decode(&command); err != nil {
		return false
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return false
	}
	return command.Version == 1 && command.Command == "shutdown"
}

// runManaged runs an endpoint under stdin control. Shutdown, EOF, invalid
// input, and read failures all cancel the endpoint, so a parent that closes
// the pipe can never leave an orphaned child behind.
func runManaged(ctx context.Context, mode string, c config.Config, stdin io.Reader, stdout io.Writer) error {
	if stdin == nil {
		return errors.New("--managed requires stdin")
	}
	managedCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	closer, closable := stdin.(io.ReadCloser)
	controlDone := make(chan struct{})
	go func() {
		_ = readManagedCommand(stdin)
		// Close before cancel so a clean endpoint exit caused by this request
		// is always observed as controlled by runManaged.
		close(controlDone)
		cancel()
	}()

	err := runEndpoint(managedCtx, mode, c, stdout)

	// Snapshot before interrupting the reader: only a control request that
	// completed on its own can explain an endpoint exit.
	controlled := false
	select {
	case <-controlDone:
		controlled = true
	default:
	}
	if closable {
		// A generic io.Reader cannot be interrupted. Closing pipes and files
		// lets the scanner goroutine finish instead of leaking it, and closes
		// the Windows stdin pipe so a blocked read is released.
		_ = closer.Close()
		select {
		case <-controlDone:
		case <-time.After(time.Second):
			// Some OS handles cannot interrupt a synchronous read. main may
			// exit without waiting on that reader; never hang process shutdown.
		}
	}
	if controlled {
		// A parent-requested shutdown, EOF, or invalid control line is not an
		// error: the endpoint was closed by the request.
		return nil
	}
	return err
}
