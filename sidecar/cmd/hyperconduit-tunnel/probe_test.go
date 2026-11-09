package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"net"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/apernet/hysteria/core/v2/server"

	"github.com/llll415/HyperConduit/sidecar/internal/probe"
)

const probeWorkerTestTimeout = 10 * time.Second

type cmdProbeBackend struct {
	mu  sync.Mutex
	tcp int
}

func (b *cmdProbeBackend) TCP(string) (net.Conn, error) {
	b.mu.Lock()
	b.tcp++
	b.mu.Unlock()
	return nil, errors.New("probe test must not dial TCP")
}

func (b *cmdProbeBackend) count() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.tcp
}

type cmdProbeAuth struct {
	mu    sync.Mutex
	calls int
}

func (a *cmdProbeAuth) Authenticate(net.Addr, string, uint64) (bool, string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.calls++
	return false, ""
}

func (a *cmdProbeAuth) count() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.calls
}

func startCmdProbeServer(t *testing.T, response []byte) (string, *cmdProbeBackend, *cmdProbeAuth) {
	t.Helper()
	fixtures := testFixturesDir()
	cert, err := tls.LoadX509KeyPair(filepath.Join(fixtures, "test.crt"), filepath.Join(fixtures, "test.key"))
	if err != nil {
		t.Fatalf("load test certificate: %v", err)
	}
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen UDP: %v", err)
	}
	backend := &cmdProbeBackend{}
	auth := &cmdProbeAuth{}
	s, err := server.NewServer(&server.Config{
		TLSConfig:          server.TLSConfig{Certificates: []tls.Certificate{cert}},
		Conn:               pc,
		Outbound:           backend,
		Authenticator:      auth,
		CapabilityResponse: response,
	})
	if err != nil {
		_ = pc.Close()
		t.Fatalf("start server: %v", err)
	}
	go func() { _ = s.Serve() }()
	t.Cleanup(func() { _ = s.Close() })
	return pc.LocalAddr().String(), backend, auth
}

type cmdProbeEvent struct {
	Version    int                `json:"version"`
	Event      string             `json:"event"`
	ID         int64              `json:"id"`
	State      string             `json:"state"`
	Capability *probe.Declaration `json:"capability"`
}

func enabledDeclaration() *probe.Declaration {
	d := probe.NewDeclaration(true, true, false, true)
	return &d
}

func nextCmdProbeEvent(t *testing.T, events <-chan []byte) cmdProbeEvent {
	t.Helper()
	select {
	case raw := <-events:
		var event cmdProbeEvent
		if err := json.Unmarshal(raw, &event); err != nil {
			t.Fatalf("invalid event %q: %v", raw, err)
		}
		return event
	case <-time.After(probeWorkerTestTimeout):
		t.Fatal("timed out waiting for probe event")
		return cmdProbeEvent{}
	}
}

func waitCmdProbeRun(t *testing.T, done <-chan error) error {
	t.Helper()
	select {
	case err := <-done:
		return err
	case <-time.After(probeWorkerTestTimeout):
		t.Fatal("probe worker did not exit")
		return nil
	}
}

func writeCmdProbeLine(t *testing.T, w io.Writer, value any) {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := w.Write(append(data, '\n')); err != nil {
		t.Fatalf("write probe input: %v", err)
	}
}

func TestProbeWorkerRealCapabilityServer(t *testing.T) {
	tests := []struct {
		name     string
		response []byte
		state    string
	}{
		{name: "enabled", response: []byte(`{"protocol":2,"enabled":true,"tcpAllowed":true,"disableBrutal":false,"tokenRequired":true}`), state: probe.StateEnabled},
		{name: "disabled", response: []byte(`{"protocol":2,"enabled":false,"tcpAllowed":false,"disableBrutal":true,"tokenRequired":false}`), state: probe.StateDisabled},
		{name: "incompatible", response: []byte(`{"protocol":1,"enabled":true}`), state: probe.StateIncompatible},
		{name: "undeclared", response: nil, state: probe.StateUndeclared},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			address, backend, auth := startCmdProbeServer(t, tt.response)
			pr, pw := io.Pipe()
			defer pw.Close()
			events := make(chan []byte, 8)
			done := make(chan error, 1)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			go func() { done <- runProbeWithCheck(ctx, pr, eventWriter{events}, probe.Check) }()

			ready := nextCmdProbeEvent(t, events)
			if ready.Version != 1 || ready.Event != "ready" {
				t.Fatalf("unexpected ready event: %+v", ready)
			}
			writeCmdProbeLine(t, pw, map[string]any{
				"version": 1, "command": "probe", "id": 1, "server": address,
			})
			result := nextCmdProbeEvent(t, events)
			if result.Version != 1 || result.Event != "result" || result.ID != 1 || result.State != tt.state {
				t.Fatalf("unexpected result: %+v", result)
			}
			declared := tt.state == probe.StateEnabled || tt.state == probe.StateDisabled
			if declared != (result.Capability != nil) {
				t.Fatalf("capability presence = %v, want declared=%v: %+v",
					result.Capability != nil, declared, result)
			}
			if declared && result.Capability.Protocol != probe.Protocol {
				t.Fatalf("capability protocol = %d, want %d", result.Capability.Protocol, probe.Protocol)
			}
			if tt.state == probe.StateEnabled && !result.Capability.TCPAllowed {
				t.Fatalf("enabled result lost tcpAllowed: %+v", *result.Capability)
			}
			if auth.count() != 0 {
				t.Fatalf("probe authenticated: %d calls", auth.count())
			}
			if tcp := backend.count(); tcp != 0 {
				t.Fatalf("probe dialed backend: tcp=%d", tcp)
			}
			_ = pw.Close()
			if err := waitCmdProbeRun(t, done); err != nil {
				t.Fatalf("probe worker returned %v", err)
			}
		})
	}
}

func TestProbeWorkerConcurrencyBoundsAndReuse(t *testing.T) {
	var current, maximum, started atomic.Int32
	release := make(chan struct{})
	firstStarted := make(chan int64, 64)
	check := func(ctx context.Context, server string) probe.Result {
		id, _ := strconv.ParseInt(server, 10, 64)
		started.Add(1)
		n := current.Add(1)
		for {
			old := maximum.Load()
			if n <= old || maximum.CompareAndSwap(old, n) {
				break
			}
		}
		firstStarted <- id
		defer current.Add(-1)
		select {
		case <-release:
			return probe.Result{State: probe.StateEnabled, Capability: enabledDeclaration()}
		case <-ctx.Done():
			return probe.Result{State: probe.StateUnknown}
		}
	}

	pr, pw := io.Pipe()
	defer pw.Close()
	events := make(chan []byte, 128)
	done := make(chan error, 1)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { done <- runProbeWithCheck(ctx, pr, eventWriter{events}, check) }()

	if ready := nextCmdProbeEvent(t, events); ready.Event != "ready" {
		t.Fatalf("unexpected ready event: %+v", ready)
	}
	for id := int64(1); id <= 4; id++ {
		writeCmdProbeLine(t, pw, map[string]any{
			"version": 1, "command": "probe", "id": id, "server": strconv.FormatInt(id, 10),
		})
	}
	for id := int64(1); id <= 4; id++ {
		select {
		case got := <-firstStarted:
			if got < 1 || got > 4 {
				t.Fatalf("unexpected started id %d", got)
			}
		case <-time.After(probeWorkerTestTimeout):
			t.Fatal("workers did not start")
		}
	}
	if maximum.Load() != 4 {
		t.Fatalf("maximum concurrency = %d, want 4", maximum.Load())
	}

	// Four workers are blocked and the queue holds 32. The 37th task must be
	// rejected immediately as unknown without blocking the input loop.
	for id := int64(5); id <= 36; id++ {
		writeCmdProbeLine(t, pw, map[string]any{
			"version": 1, "command": "probe", "id": id, "server": strconv.FormatInt(id, 10),
		})
	}
	writeCmdProbeLine(t, pw, map[string]any{
		"version": 1, "command": "probe", "id": 37, "server": "37",
	})
	overflow := nextCmdProbeEvent(t, events)
	if overflow.ID != 37 || overflow.State != probe.StateUnknown {
		t.Fatalf("overflow result = %+v, want id=37 state=unknown", overflow)
	}

	close(release)
	seen := make(map[int64]string)
	for len(seen) < 36 {
		event := nextCmdProbeEvent(t, events)
		if event.Event != "result" {
			t.Fatalf("unexpected event: %+v", event)
		}
		if event.ID == 37 {
			t.Fatalf("overflow task ran: %+v", event)
		}
		seen[event.ID] = event.State
	}
	for id := int64(1); id <= 36; id++ {
		if seen[id] != probe.StateEnabled {
			t.Fatalf("id %d state = %q, want enabled", id, seen[id])
		}
	}
	if maximum.Load() > 4 {
		t.Fatalf("maximum concurrency = %d, want at most 4", maximum.Load())
	}

	// Workers are reused after the first batch.
	for id := int64(38); id <= 41; id++ {
		writeCmdProbeLine(t, pw, map[string]any{
			"version": 1, "command": "probe", "id": id, "server": strconv.FormatInt(id, 10),
		})
	}
	reused := make(map[int64]string)
	for len(reused) < 4 {
		event := nextCmdProbeEvent(t, events)
		if event.Event != "result" {
			t.Fatalf("unexpected event: %+v", event)
		}
		reused[event.ID] = event.State
	}
	for id := int64(38); id <= 41; id++ {
		if reused[id] != probe.StateEnabled {
			t.Fatalf("reuse id %d state = %q, want enabled", id, reused[id])
		}
	}
	if started.Load() != 40 {
		t.Fatalf("started tasks = %d, want 40", started.Load())
	}
	_ = pw.Close()
	if err := waitCmdProbeRun(t, done); err != nil {
		t.Fatalf("probe worker returned %v", err)
	}
}

func TestProbeWorkerCancel(t *testing.T) {
	started := make(chan struct{})
	canceled := make(chan struct{})
	var once sync.Once
	check := func(ctx context.Context, server string) probe.Result {
		if server == "1" {
			once.Do(func() { close(started) })
			<-ctx.Done()
			close(canceled)
			return probe.Result{State: probe.StateUnknown}
		}
		return probe.Result{State: probe.StateEnabled, Capability: enabledDeclaration()}
	}

	pr, pw := io.Pipe()
	defer pw.Close()
	events := make(chan []byte, 16)
	done := make(chan error, 1)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { done <- runProbeWithCheck(ctx, pr, eventWriter{events}, check) }()

	if ready := nextCmdProbeEvent(t, events); ready.Event != "ready" {
		t.Fatalf("unexpected ready event: %+v", ready)
	}
	writeCmdProbeLine(t, pw, map[string]any{
		"version": 1, "command": "probe", "id": 1, "server": "1",
	})
	select {
	case <-started:
	case <-time.After(probeWorkerTestTimeout):
		t.Fatal("probe did not start")
	}
	writeCmdProbeLine(t, pw, map[string]any{
		"version": 1, "command": "cancel", "id": 1,
	})
	select {
	case <-canceled:
	case <-time.After(probeWorkerTestTimeout):
		t.Fatal("cancel did not cancel the probe context")
	}

	// The canceled task may or may not emit a result. The worker must still be
	// available for the next request.
	writeCmdProbeLine(t, pw, map[string]any{
		"version": 1, "command": "probe", "id": 2, "server": "2",
	})
	for {
		event := nextCmdProbeEvent(t, events)
		if event.ID == 2 {
			if event.State != probe.StateEnabled {
				t.Fatalf("result = %+v, want enabled", event)
			}
			break
		}
	}
	_ = pw.Close()
	if err := waitCmdProbeRun(t, done); err != nil {
		t.Fatalf("probe worker returned %v", err)
	}
}

func TestProbeWorkerDuplicateIDIsProtocolError(t *testing.T) {
	started := make(chan struct{})
	var once sync.Once
	var calls atomic.Int32
	check := func(ctx context.Context, _ string) probe.Result {
		calls.Add(1)
		once.Do(func() { close(started) })
		<-ctx.Done()
		return probe.Result{State: probe.StateUnknown}
	}

	pr, pw := io.Pipe()
	defer pw.Close()
	events := make(chan []byte, 16)
	done := make(chan error, 1)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { done <- runProbeWithCheck(ctx, pr, eventWriter{events}, check) }()

	if ready := nextCmdProbeEvent(t, events); ready.Event != "ready" {
		t.Fatalf("unexpected ready event: %+v", ready)
	}
	writeCmdProbeLine(t, pw, map[string]any{
		"version": 1, "command": "probe", "id": 1, "server": "1",
	})
	select {
	case <-started:
	case <-time.After(probeWorkerTestTimeout):
		t.Fatal("probe did not start")
	}
	writeCmdProbeLine(t, pw, map[string]any{
		"version": 1, "command": "probe", "id": 1, "server": "2",
	})
	err := waitCmdProbeRun(t, done)
	if !errors.Is(err, errInvalidProbeInput) {
		t.Fatalf("duplicate id error = %v, want %v", err, errInvalidProbeInput)
	}
	if calls.Load() != 1 {
		t.Fatalf("probe check calls = %d, want 1", calls.Load())
	}
}

func TestProbeWorkerShutdownAndEOF(t *testing.T) {
	tests := []struct {
		name    string
		control func(*io.PipeWriter) error
	}{
		{
			name: "shutdown",
			control: func(pw *io.PipeWriter) error {
				_, err := io.WriteString(pw, `{"version":1,"command":"shutdown"}`+"\n")
				return err
			},
		},
		{
			name:    "stdin EOF",
			control: func(pw *io.PipeWriter) error { return pw.Close() },
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			started := make(chan struct{})
			var once sync.Once
			check := func(ctx context.Context, _ string) probe.Result {
				once.Do(func() { close(started) })
				<-ctx.Done()
				return probe.Result{State: probe.StateUnknown}
			}

			pr, pw := io.Pipe()
			defer pw.Close()
			events := make(chan []byte, 8)
			done := make(chan error, 1)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			go func() { done <- runProbeWithCheck(ctx, pr, eventWriter{events}, check) }()

			if ready := nextCmdProbeEvent(t, events); ready.Event != "ready" {
				t.Fatalf("unexpected ready event: %+v", ready)
			}
			writeCmdProbeLine(t, pw, map[string]any{
				"version": 1, "command": "probe", "id": 1, "server": "127.0.0.1:1",
			})
			select {
			case <-started:
			case <-time.After(probeWorkerTestTimeout):
				t.Fatal("probe did not start")
			}
			if err := tt.control(pw); err != nil {
				t.Fatalf("write control input: %v", err)
			}
			if err := waitCmdProbeRun(t, done); err != nil {
				t.Fatalf("probe worker returned %v", err)
			}
		})
	}
}

func TestProbeModeRejectsExtraArguments(t *testing.T) {
	var stdout, stderr bytes.Buffer
	err := runWithInput(context.Background(), []string{"probe", "--config", "x"},
		strings.NewReader(""), &stdout, &stderr)
	if err == nil {
		t.Fatal("probe mode accepted extra arguments")
	}
	if stdout.Len() != 0 {
		t.Fatalf("probe mode emitted output before rejecting arguments: %q", stdout.String())
	}
}

func TestProbeWorkerRejectsInvalidInputWithoutEcho(t *testing.T) {
	tests := []string{
		"SECRET-RAW not json",
		`{"version":1,"command":"probe","id":1,"server":"x","extra":"SECRET-RAW"}`,
		`{"version":1,"command":"probe","id":1,"server":"x","id":2}`,
		`{"version":1,"command":"unknown"}`,
		`{"version":1,"command":"shutdown","id":1}`,
		`{"version":1,"command":"shutdown","id":null}`,
	}
	for _, input := range tests {
		t.Run(input, func(t *testing.T) {
			pr, pw := io.Pipe()
			defer pw.Close()
			events := make(chan []byte, 4)
			done := make(chan error, 1)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			go func() { done <- runProbeWithCheck(ctx, pr, eventWriter{events}, probe.Check) }()

			if ready := nextCmdProbeEvent(t, events); ready.Event != "ready" {
				t.Fatalf("unexpected ready event: %+v", ready)
			}
			if _, err := io.WriteString(pw, input+"\n"); err != nil {
				t.Fatalf("write invalid input: %v", err)
			}
			err := waitCmdProbeRun(t, done)
			if err == nil {
				t.Fatal("invalid input was accepted")
			}
			if strings.Contains(err.Error(), "SECRET-RAW") {
				t.Fatalf("error echoed raw input: %v", err)
			}
		})
	}
}
