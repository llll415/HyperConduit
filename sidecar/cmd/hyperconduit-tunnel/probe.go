package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"sync"
	"time"

	"github.com/llll415/HyperConduit/sidecar/internal/probe"
)

const (
	probeMaxLine = 4096
	probeWorkers = 4
	probeQueue   = 32
)

var errInvalidProbeInput = errors.New("probe: invalid input")

type checkFunc func(context.Context, string) probe.Result

type probeRequest struct {
	Version *int    `json:"version"`
	Command *string `json:"command"`
	ID      *int64  `json:"id"`
	Server  *string `json:"server"`
}

type pendingProbe struct {
	cancel context.CancelFunc
}

type probeJob struct {
	id     int64
	server string
	ctx    context.Context
	task   *pendingProbe
}

type readyEvent struct {
	Version int    `json:"version"`
	Event   string `json:"event"`
}

type resultEvent struct {
	Version int    `json:"version"`
	Event   string `json:"event"`
	ID      int64  `json:"id"`
	State   string `json:"state"`
}

type probeRunner struct {
	ctx    context.Context
	cancel context.CancelFunc
	stdout io.Writer
	check  checkFunc
	jobs   chan probeJob

	outMu sync.Mutex

	pendingMu sync.Mutex
	pending   map[int64]*pendingProbe

	workers  sync.WaitGroup
	failOnce sync.Once
	errMu    sync.Mutex
	err      error
}

// runProbe is the worker-mode entry point. It deliberately has no config or
// managed flag: stdin is the whole control plane and EOF closes the worker.
func runProbe(ctx context.Context, stdin io.Reader, stdout io.Writer) error {
	return runProbeWithCheck(ctx, stdin, stdout, probe.Check)
}

func runProbeWithCheck(ctx context.Context, stdin io.Reader, stdout io.Writer, check checkFunc) error {
	if stdin == nil {
		return errors.New("probe: stdin is required")
	}
	if stdout == nil {
		return errors.New("probe: stdout is required")
	}
	if check == nil {
		return errors.New("probe: checker is required")
	}
	workerCtx, cancel := context.WithCancel(ctx)
	r := &probeRunner{
		ctx:     workerCtx,
		cancel:  cancel,
		stdout:  stdout,
		check:   check,
		jobs:    make(chan probeJob, probeQueue),
		pending: make(map[int64]*pendingProbe),
	}
	if err := r.writeEvent(readyEvent{Version: 1, Event: "ready"}); err != nil {
		cancel()
		return errors.New("probe: cannot write ready event")
	}
	r.startWorkers()
	defer r.stop()

	readDone := make(chan error, 1)
	go func() { readDone <- r.readLoop(stdin) }()

	select {
	case <-r.ctx.Done():
		// A generic io.Reader cannot be interrupted. Closing pipes and files lets
		// the scanner finish instead of leaving a goroutine behind.
		if closer, ok := stdin.(io.Closer); ok {
			_ = closer.Close()
			select {
			case <-readDone:
			case <-time.After(time.Second):
				// Some OS handles cannot interrupt a synchronous read. The
				// process is stopping; never hang shutdown waiting for it.
			}
		}
		r.stop()
		return r.firstError()
	case readErr := <-readDone:
		r.stop()
		if err := r.firstError(); err != nil {
			return err
		}
		return readErr
	}
}

func (r *probeRunner) startWorkers() {
	for i := 0; i < probeWorkers; i++ {
		r.workers.Add(1)
		go r.worker()
	}
}

func (r *probeRunner) stop() {
	r.cancel()
	r.workers.Wait()
}

func (r *probeRunner) worker() {
	defer r.workers.Done()
	for {
		select {
		case <-r.ctx.Done():
			return
		case job := <-r.jobs:
			if job.ctx.Err() != nil {
				// Canceled before it started: no result is required.
				r.finish(job)
				continue
			}
			result := r.check(job.ctx, job.server)
			r.finish(job)
			if r.ctx.Err() != nil {
				// Shutdown/EOF/protocol error: no result is required and the
				// parent may already be closing stdout.
				continue
			}
			if err := r.writeResult(job.id, normalizeState(result.State)); err != nil {
				r.fail(err)
				return
			}
		}
	}
}

func (r *probeRunner) readLoop(stdin io.Reader) error {
	scanner := bufio.NewScanner(stdin)
	// Allow a token of 4096 bytes plus the line terminator, then enforce the
	// limit ourselves so a line of exactly 4096 bytes remains valid.
	scanner.Buffer(make([]byte, 0, probeMaxLine+1), probeMaxLine+1)
	for scanner.Scan() {
		line := scanner.Bytes()
		if len(line) > probeMaxLine {
			return errInvalidProbeInput
		}
		stop, err := r.handleLine(line)
		if err != nil {
			return err
		}
		if stop {
			return nil
		}
	}
	if err := scanner.Err(); err != nil {
		return errInvalidProbeInput
	}
	return nil
}

func (r *probeRunner) handleLine(line []byte) (bool, error) {
	if len(line) == 0 {
		return false, errInvalidProbeInput
	}
	keys, err := validateProbeObject(line)
	if err != nil {
		return false, errInvalidProbeInput
	}

	decoder := json.NewDecoder(bytes.NewReader(line))
	decoder.DisallowUnknownFields()
	var req probeRequest
	if err := decoder.Decode(&req); err != nil {
		return false, errInvalidProbeInput
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return false, errInvalidProbeInput
	}
	if req.Version == nil || *req.Version != 1 || req.Command == nil {
		return false, errInvalidProbeInput
	}

	switch *req.Command {
	case "probe":
		if !hasExactKeys(keys, "version", "command", "id", "server") ||
			req.ID == nil || *req.ID <= 0 || req.Server == nil || *req.Server == "" {
			return false, errInvalidProbeInput
		}
		if err := r.submit(*req.ID, *req.Server); err != nil {
			return false, err
		}
		return false, nil
	case "cancel":
		if !hasExactKeys(keys, "version", "command", "id") ||
			req.ID == nil || *req.ID <= 0 {
			return false, errInvalidProbeInput
		}
		r.cancelProbe(*req.ID)
		return false, nil
	case "shutdown":
		if !hasExactKeys(keys, "version", "command") {
			return false, errInvalidProbeInput
		}
		return true, nil
	default:
		return false, errInvalidProbeInput
	}
}

func hasExactKeys(keys map[string]struct{}, names ...string) bool {
	if len(keys) != len(names) {
		return false
	}
	for _, name := range names {
		if _, ok := keys[name]; !ok {
			return false
		}
	}
	return true
}

func (r *probeRunner) submit(id int64, server string) error {
	taskCtx, cancel := context.WithCancel(r.ctx)
	task := &pendingProbe{cancel: cancel}

	r.pendingMu.Lock()
	if _, exists := r.pending[id]; exists {
		r.pendingMu.Unlock()
		// Never replace the original task's cancel function. A duplicate id is
		// a protocol violation: fail the worker instead of emitting a second
		// result that could complete the parent's future with the wrong state.
		cancel()
		return errInvalidProbeInput
	}
	r.pending[id] = task
	r.pendingMu.Unlock()

	job := probeJob{id: id, server: server, ctx: taskCtx, task: task}
	select {
	case r.jobs <- job:
		return nil
	default:
		// The bounded queue is full. Never block the input loop.
		r.finish(job)
		if err := r.writeResult(id, probe.StateUnknown); err != nil {
			r.fail(err)
		}
		return nil
	}
}

func (r *probeRunner) cancelProbe(id int64) {
	r.pendingMu.Lock()
	task := r.pending[id]
	r.pendingMu.Unlock()
	if task != nil {
		task.cancel()
	}
}

func (r *probeRunner) finish(job probeJob) {
	r.pendingMu.Lock()
	if r.pending[job.id] == job.task {
		delete(r.pending, job.id)
	}
	r.pendingMu.Unlock()
	job.task.cancel()
}

func (r *probeRunner) writeResult(id int64, state string) error {
	return r.writeEvent(resultEvent{Version: 1, Event: "result", ID: id, State: state})
}

// writeEvent serializes every stdout write. It can block if the parent stops
// draining stdout; the probe protocol assumes the parent drains continuously
// (the Java parent does) or terminates the child with a bounded kill.
func (r *probeRunner) writeEvent(event any) error {
	r.outMu.Lock()
	defer r.outMu.Unlock()
	return json.NewEncoder(r.stdout).Encode(event)
}

func (r *probeRunner) fail(err error) {
	r.failOnce.Do(func() {
		r.errMu.Lock()
		r.err = err
		r.errMu.Unlock()
		r.cancel()
	})
}

func (r *probeRunner) firstError() error {
	r.errMu.Lock()
	defer r.errMu.Unlock()
	return r.err
}

func normalizeState(state string) string {
	switch state {
	case probe.StateEnabled, probe.StateDisabled, probe.StateUndeclared,
		probe.StateUnknown, probe.StateIncompatible:
		return state
	default:
		return probe.StateUnknown
	}
}

// validateProbeObject rejects duplicate top-level keys and trailing data.
// encoding/json's DisallowUnknownFields does not catch duplicates, so this
// separate pass is required before the typed decode.
func validateProbeObject(data []byte) (map[string]struct{}, error) {
	decoder := json.NewDecoder(bytes.NewReader(data))
	tok, err := decoder.Token()
	if err != nil {
		return nil, err
	}
	if delim, ok := tok.(json.Delim); !ok || delim != '{' {
		return nil, errors.New("probe: request is not an object")
	}
	seen := make(map[string]struct{}, 4)
	for decoder.More() {
		keyToken, err := decoder.Token()
		if err != nil {
			return nil, err
		}
		key, ok := keyToken.(string)
		if !ok {
			return nil, errors.New("probe: invalid request key")
		}
		if _, duplicate := seen[key]; duplicate {
			return nil, errors.New("probe: duplicate request key")
		}
		seen[key] = struct{}{}
		var raw json.RawMessage
		if err := decoder.Decode(&raw); err != nil {
			return nil, err
		}
	}
	tok, err = decoder.Token()
	if err != nil {
		return nil, err
	}
	if delim, ok := tok.(json.Delim); !ok || delim != '}' {
		return nil, errors.New("probe: invalid request object")
	}
	if _, err := decoder.Token(); err != io.EOF {
		return nil, errors.New("probe: trailing request data")
	}
	return seen, nil
}
