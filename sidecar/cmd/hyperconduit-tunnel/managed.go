package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"sync"
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

type managedAction int

const (
	managedStop managedAction = iota
	managedShutdown
	managedObserve
)

// parseManagedCommand accepts only the fixed version-1 commands. Every other
// outcome is a fail-closed stop request. The raw input is never logged.
//
// The line must be a single strict NDJSON object with exactly the keys
// "version" and "command". Duplicate keys, unknown keys, wrong value types,
// trailing data, and unsupported versions all fail closed.
func parseManagedCommand(line string) managedAction {
	if len(line) > managedMaxCommandBytes {
		return managedStop
	}
	command, ok := decodeManagedCommand(line)
	if !ok || command.Version != 1 {
		return managedStop
	}
	switch command.Command {
	case "shutdown":
		return managedShutdown
	case "observe":
		return managedObserve
	default:
		return managedStop
	}
}

// decodeManagedCommand tokenizes the line instead of relying only on
// encoding/json, which silently accepts duplicate object keys. It requires
// exactly the two known fields so a parent cannot smuggle extra data past the
// schema.
func decodeManagedCommand(line string) (managedCommand, bool) {
	decoder := json.NewDecoder(strings.NewReader(line))
	token, err := decoder.Token()
	if err != nil {
		return managedCommand{}, false
	}
	if delim, ok := token.(json.Delim); !ok || delim != '{' {
		return managedCommand{}, false
	}
	seen := make(map[string]struct{}, 2)
	var command managedCommand
	for decoder.More() {
		keyToken, err := decoder.Token()
		if err != nil {
			return managedCommand{}, false
		}
		key, ok := keyToken.(string)
		if !ok {
			return managedCommand{}, false
		}
		if _, duplicate := seen[key]; duplicate {
			return managedCommand{}, false
		}
		seen[key] = struct{}{}
		switch key {
		case "version":
			if err := decoder.Decode(&command.Version); err != nil {
				return managedCommand{}, false
			}
		case "command":
			if err := decoder.Decode(&command.Command); err != nil {
				return managedCommand{}, false
			}
		default:
			return managedCommand{}, false
		}
	}
	token, err = decoder.Token()
	if err != nil {
		return managedCommand{}, false
	}
	if delim, ok := token.(json.Delim); !ok || delim != '}' {
		return managedCommand{}, false
	}
	if _, err := decoder.Token(); err != io.EOF {
		return managedCommand{}, false
	}
	if _, ok := seen["version"]; !ok {
		return managedCommand{}, false
	}
	if _, ok := seen["command"]; !ok {
		return managedCommand{}, false
	}
	return command, true
}

// readManagedCommand reports whether r supplied the shutdown command. It exists
// for the single-command compatibility tests; managed mode uses the streaming
// loop in runManaged so observe can be followed by shutdown.
func readManagedCommand(r io.Reader) bool {
	scanner := bufio.NewScanner(r)
	scanner.Buffer(make([]byte, 0, managedMaxCommandBytes), managedMaxCommandBytes)
	if !scanner.Scan() {
		return false
	}
	return parseManagedCommand(scanner.Text()) == managedShutdown
}

// runManaged runs an endpoint under stdin control. Shutdown, EOF, invalid
// input, and read failures all cancel the endpoint, so a parent that closes
// the pipe can never leave an orphaned child behind. The observe command is the
// only non-terminating command: the first one enables the opt-in stats stream
// and any repeats are idempotent.
func runManaged(ctx context.Context, mode string, c config.Config, stdin io.Reader, stdout io.Writer) error {
	if stdin == nil {
		return errors.New("--managed requires stdin")
	}
	managedCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	observeSignal := make(chan struct{})
	var observeOnce sync.Once
	controlDone := make(chan struct{})
	var finishOnce sync.Once
	finish := func() {
		// Close before cancel so a clean endpoint exit caused by this request
		// is always observed as controlled by runManaged.
		finishOnce.Do(func() {
			close(controlDone)
			cancel()
		})
	}
	closer, closable := stdin.(io.ReadCloser)
	go func() {
		scanner := bufio.NewScanner(stdin)
		scanner.Buffer(make([]byte, 0, managedMaxCommandBytes), managedMaxCommandBytes)
		for scanner.Scan() {
			switch parseManagedCommand(scanner.Text()) {
			case managedObserve:
				// One subscription per process; repeated observe is a no-op.
				observeOnce.Do(func() { close(observeSignal) })
			case managedShutdown:
				finish()
				return
			default:
				finish()
				return
			}
		}
		// EOF, an oversized line, or a read error is a fail-closed stop.
		finish()
	}()

	err := runEndpointObserved(managedCtx, mode, c, stdout, observeSignal)

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
