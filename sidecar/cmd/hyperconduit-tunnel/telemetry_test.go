package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"math"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"github.com/apernet/hysteria/core/v2/observe"

	"github.com/llll415/HyperConduit/sidecar/internal/config"
	"github.com/llll415/HyperConduit/sidecar/internal/tunnel"
)

const managedObserveCommand = `{"version":1,"command":"observe"}`
const managedShutdownCommand = `{"version":1,"command":"shutdown"}`

func TestParseManagedCommandObserve(t *testing.T) {
	tests := []struct {
		name  string
		input string
		want  managedAction
	}{
		{name: "observe", input: managedObserveCommand, want: managedObserve},
		{name: "observe with newline", input: managedObserveCommand + "\n", want: managedObserve},
		{name: "shutdown", input: managedShutdownCommand, want: managedShutdown},
		{name: "unknown", input: `{"version":1,"command":"keepalive"}`, want: managedStop},
		{name: "unknown field", input: `{"version":1,"command":"observe","token":"x"}`, want: managedStop},
		{name: "wrong version", input: `{"version":2,"command":"observe"}`, want: managedStop},
		{name: "malformed", input: "{", want: managedStop},
		{name: "empty", input: "", want: managedStop},
		{name: "blank line", input: "\n", want: managedStop},
		{name: "not an object", input: `[1]`, want: managedStop},
		{name: "duplicate version", input: `{"version":1,"version":1,"command":"shutdown"}`, want: managedStop},
		{name: "duplicate command", input: `{"version":1,"command":"shutdown","command":"observe"}`, want: managedStop},
		{name: "missing command", input: `{"version":1}`, want: managedStop},
		{name: "missing version", input: `{"command":"observe"}`, want: managedStop},
		{name: "trailing value", input: managedObserveCommand + `{}`, want: managedStop},
		{name: "trailing text", input: managedObserveCommand + `x`, want: managedStop},
		{name: "wrong type", input: `{"version":"1","command":"observe"}`, want: managedStop},
		{name: "oversized", input: `{"version":1,"command":"observe"}` + string(bytes.Repeat([]byte(" "), managedMaxCommandBytes)), want: managedStop},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := parseManagedCommand(tt.input); got != tt.want {
				t.Fatalf("parseManagedCommand() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestBuildTelemetryFiltersAndSorts(t *testing.T) {
	snapshots := []observe.Snapshot{
		{ID: 3, State: "closed", Congestion: observe.Congestion{Type: "bbr"}},
		{ID: 2, State: "established", Congestion: observe.Congestion{Type: "RENO"}, SmoothedRTTNanos: -5, BytesSent: 10, BytesReceived: 20},
		{ID: 1, State: "established", Congestion: observe.Congestion{Type: "brutal", TxBytesPerSecond: 123}},
		{ID: 4, State: "established", Congestion: observe.Congestion{Type: "unknown"}},
	}
	event := buildTelemetry(3, 4, tunnel.Status{Connections: snapshots})
	if event.Version != 1 || event.Event != "stats" || event.Sequence != 3 || event.ElapsedNanos != 4 {
		t.Fatalf("unexpected header: %+v", event)
	}
	if event.Truncated {
		t.Fatal("unexpected truncation")
	}
	if len(event.Connections) != 2 {
		t.Fatalf("connections = %d, want 2: %+v", len(event.Connections), event.Connections)
	}
	if event.Connections[0].ID != 1 || event.Connections[0].Congestion != "brutal" || event.Connections[0].TxTargetBytesPerSecond != 123 {
		t.Fatalf("unexpected first connection: %+v", event.Connections[0])
	}
	if event.Connections[1].ID != 2 || event.Connections[1].Congestion != "reno" {
		t.Fatalf("unexpected second connection: %+v", event.Connections[1])
	}
	if event.Connections[1].SmoothedRTTNanos != 0 {
		t.Fatalf("negative RTT was not clamped: %+v", event.Connections[1])
	}
}

func TestBuildTelemetryBoundsConnections(t *testing.T) {
	total := telemetryMaxConnections + 2
	snapshots := make([]observe.Snapshot, total)
	for i := range snapshots {
		// Reverse the order so sorting by ID is observable.
		snapshots[i] = observe.Snapshot{
			ID:               uint64(total - i),
			State:            "established",
			Congestion:       observe.Congestion{Type: "brutal", TxBytesPerSecond: math.MaxUint64},
			SmoothedRTTNanos: math.MaxInt64,
			BytesSent:        math.MaxUint64,
			BytesReceived:    math.MaxUint64,
		}
	}
	event := buildTelemetry(7, 8, tunnel.Status{Connections: snapshots})
	if !event.Truncated {
		t.Fatal("expected truncated stats")
	}
	if len(event.Connections) != telemetryMaxConnections {
		t.Fatalf("connections = %d, want %d", len(event.Connections), telemetryMaxConnections)
	}
	for i, connection := range event.Connections {
		if connection.ID != uint64(i+1) {
			t.Fatalf("connections are not sorted by ID at %d: %+v", i, connection)
		}
	}
	first := event.Connections[0]
	if first.TxTargetBytesPerSecond != math.MaxInt64 || first.BytesSent != math.MaxInt64 || first.BytesReceived != math.MaxInt64 {
		t.Fatalf("counters were not bounded to signed range: %+v", first)
	}
	line, err := encodeTelemetry(event)
	if err != nil {
		t.Fatalf("encodeTelemetry: %v", err)
	}
	if len(line) > telemetryMaxLineBytes {
		t.Fatalf("stats line is %d bytes, limit %d", len(line), telemetryMaxLineBytes)
	}
	assertStatsSchema(t, line)
}

func TestBuildTelemetryBindingsAreExactAndBounded(t *testing.T) {
	streamZero := int64(0)
	streamOne := int64(1)
	status := tunnel.Status{
		Connections: []observe.Snapshot{
			{ID: 5, State: "established", Congestion: observe.Congestion{Type: "bbr"}},
		},
		Bindings: []tunnel.Binding{
			{ID: 1, ConnectionID: 5, StreamID: &streamOne, LocalAddr: "127.0.0.1:40002", PeerAddr: "203.0.113.9:5555", State: "established"},
			// Opening bindings have no stream identity and must not be guessed.
			{ID: 2, LocalAddr: "127.0.0.1:40003", PeerAddr: "203.0.113.10:5556", State: "opening"},
			// Hostnames, missing peers, zero ports and zones are not exact endpoints.
			{ID: 3, ConnectionID: 5, StreamID: &streamZero, LocalAddr: "localhost:40004", PeerAddr: "203.0.113.11:5557", State: "established"},
			{ID: 4, ConnectionID: 5, StreamID: &streamZero, LocalAddr: "127.0.0.1:0", PeerAddr: "203.0.113.12:5558", State: "established"},
			{ID: 5, ConnectionID: 5, StreamID: &streamZero, LocalAddr: "127.0.0.1:40005", PeerAddr: "", State: "established"},
			// Duplicate stream identity is dropped instead of emitting an
			// ambiguous frame.
			{ID: 6, ConnectionID: 5, StreamID: &streamOne, LocalAddr: "127.0.0.1:40006", PeerAddr: "203.0.113.13:5559", State: "established"},
			// Stream 0 is valid and remains distinguishable from "no stream".
			{ID: 7, ConnectionID: 5, StreamID: &streamZero, LocalAddr: "127.0.0.1:40001", PeerAddr: "203.0.113.8:5554", State: "established"},
		},
	}
	event := buildTelemetry(1, 1, status)
	if event.Truncated {
		t.Fatal("unexpected truncation")
	}
	if len(event.Bindings) != 2 {
		t.Fatalf("bindings = %d, want 2: %+v", len(event.Bindings), event.Bindings)
	}
	if event.Bindings[0].LocalAddr != "127.0.0.1:40001" || event.Bindings[0].StreamID != 0 {
		t.Fatalf("unexpected first binding: %+v", event.Bindings[0])
	}
	if event.Bindings[1].LocalAddr != "127.0.0.1:40002" || event.Bindings[1].StreamID != 1 {
		t.Fatalf("unexpected second binding: %+v", event.Bindings[1])
	}
	if event.Bindings[0].PeerAddr != "203.0.113.8:5554" || event.Bindings[0].ConnectionID != 5 {
		t.Fatalf("binding identity was not preserved: %+v", event.Bindings[0])
	}

	// The cap keeps the lowest sorted identities and marks the frame truncated.
	for i := 0; i < telemetryMaxBindings+2; i++ {
		streamID := int64(1000 + i)
		status.Bindings = append(status.Bindings, tunnel.Binding{
			ConnectionID: 5,
			StreamID:     &streamID,
			LocalAddr:    "127.0.0.1:" + strconv.Itoa(41000+i),
			PeerAddr:     "203.0.113.1:" + strconv.Itoa(41000+i),
			State:        "established",
		})
	}
	bounded := buildTelemetry(2, 2, status)
	if !bounded.Truncated {
		t.Fatal("expected truncated bindings")
	}
	if len(bounded.Bindings) != telemetryMaxBindings {
		t.Fatalf("bindings = %d, want %d", len(bounded.Bindings), telemetryMaxBindings)
	}
	line, err := encodeTelemetry(bounded)
	if err != nil {
		t.Fatalf("encodeTelemetry: %v", err)
	}
	assertStatsSchema(t, line)
}

// statsEvent is the test-side mirror of the wire schema. Extra or missing keys
// are caught separately by assertStatsSchema.
type statsEvent struct {
	Version      int    `json:"version"`
	Event        string `json:"event"`
	Sequence     int64  `json:"sequence"`
	ElapsedNanos int64  `json:"elapsedNanos"`
	Connections  []struct {
		ID                     uint64 `json:"id"`
		Congestion             string `json:"congestion"`
		TxTargetBytesPerSecond uint64 `json:"txTargetBytesPerSecond"`
		SmoothedRTTNanos       int64  `json:"smoothedRttNanos"`
		BytesSent              uint64 `json:"bytesSent"`
		BytesReceived          uint64 `json:"bytesReceived"`
	} `json:"connections"`
	Bindings []struct {
		ConnectionID uint64 `json:"connectionId"`
		StreamID     int64  `json:"streamId"`
		LocalAddr    string `json:"localAddr"`
		PeerAddr     string `json:"peerAddr"`
	} `json:"bindings"`
	Truncated bool `json:"truncated"`
}

func assertStatsSchema(t *testing.T, line []byte) {
	t.Helper()
	var top map[string]json.RawMessage
	if err := json.Unmarshal(line, &top); err != nil {
		t.Fatalf("invalid stats event %q: %v", line, err)
	}
	assertExactKeys(t, top, "version", "event", "sequence", "elapsedNanos", "connections", "bindings", "truncated")
	if string(bytes.TrimSpace(top["connections"])) == "null" {
		t.Fatalf("connections must be an array: %s", line)
	}
	var connections []map[string]json.RawMessage
	if err := json.Unmarshal(top["connections"], &connections); err != nil {
		t.Fatalf("connections must be an array: %s: %v", line, err)
	}
	for _, connection := range connections {
		assertExactKeys(t, connection, "id", "congestion", "txTargetBytesPerSecond", "smoothedRttNanos", "bytesSent", "bytesReceived")
	}
	if string(bytes.TrimSpace(top["bindings"])) == "null" {
		t.Fatalf("bindings must be an array: %s", line)
	}
	var bindings []map[string]json.RawMessage
	if err := json.Unmarshal(top["bindings"], &bindings); err != nil {
		t.Fatalf("bindings must be an array: %s: %v", line, err)
	}
	for _, binding := range bindings {
		assertExactKeys(t, binding, "connectionId", "streamId", "localAddr", "peerAddr")
	}
}

func assertExactKeys(t *testing.T, value map[string]json.RawMessage, keys ...string) {
	t.Helper()
	if len(value) != len(keys) {
		t.Fatalf("keys = %v, want exactly %v", keysOf(value), keys)
	}
	for _, key := range keys {
		if _, ok := value[key]; !ok {
			t.Fatalf("missing key %q in %v", key, keysOf(value))
		}
	}
}

func keysOf(value map[string]json.RawMessage) []string {
	keys := make([]string, 0, len(value))
	for key := range value {
		keys = append(keys, key)
	}
	return keys
}

func readStatsEvent(t *testing.T, events <-chan []byte, done <-chan error, timeout time.Duration) statsEvent {
	t.Helper()
	select {
	case line := <-events:
		if len(line) > telemetryMaxLineBytes {
			t.Fatalf("stats line is %d bytes", len(line))
		}
		assertStatsSchema(t, line)
		var stats statsEvent
		if err := json.Unmarshal(line, &stats); err != nil {
			t.Fatalf("invalid stats event %q: %v", line, err)
		}
		if stats.Version != 1 || stats.Event != "stats" || stats.Sequence < 1 || stats.ElapsedNanos < 1 {
			t.Fatalf("invalid stats header: %s", line)
		}
		return stats
	case err := <-done:
		t.Fatalf("managed run exited before stats: %v", err)
	case <-time.After(timeout):
		t.Fatal("managed run did not emit stats")
	}
	return statsEvent{}
}

func waitForCongestion(t *testing.T, events <-chan []byte, done <-chan error, want string, timeout time.Duration) statsEvent {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for {
		remaining := time.Until(deadline)
		if remaining <= 0 {
			t.Fatalf("no %s connection observed before timeout", want)
		}
		stats := readStatsEvent(t, events, done, remaining)
		for _, connection := range stats.Connections {
			if connection.Congestion == want {
				return stats
			}
		}
	}
}

func waitManagedReadyListen(t *testing.T, events <-chan []byte, done <-chan error, mode string) string {
	t.Helper()
	select {
	case event := <-events:
		var ready struct {
			Version int    `json:"version"`
			Event   string `json:"event"`
			Mode    string `json:"mode"`
			Listen  string `json:"listen"`
		}
		if err := json.Unmarshal(event, &ready); err != nil {
			t.Fatalf("invalid ready event: %v", err)
		}
		if ready.Version != 1 || ready.Event != "ready" || ready.Mode != mode || ready.Listen == "" {
			t.Fatalf("invalid ready event: %s", event)
		}
		return ready.Listen
	case err := <-done:
		t.Fatalf("managed run exited before ready: %v", err)
	case <-time.After(managedTestTimeout):
		t.Fatal("managed run did not emit ready")
	}
	return ""
}

func managedClientConfigFor(t *testing.T, server, congestion string, mbps uint64) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "client.json")
	data, err := json.Marshal(map[string]any{
		"server":     server,
		"stateDir":   t.TempDir(),
		"congestion": congestion,
		"upMbps":     mbps,
		"downMbps":   mbps,
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func startEchoBackend(t *testing.T) string {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}(conn)
		}
	}()
	return listener.Addr().String()
}

func TestManagedServerEmitsStatsAfterObserve(t *testing.T) {
	path := managedServerConfig(t)
	pr, pw := io.Pipe()
	defer pw.Close()

	events := make(chan []byte, 8)
	var stderr bytes.Buffer
	done := make(chan error, 1)
	go func() {
		done <- runWithInput(context.Background(), []string{"server", "--config", path, "--managed"}, pr, eventWriter{events}, &stderr)
	}()
	waitManagedReady(t, events, done)

	if _, err := io.WriteString(pw, managedObserveCommand+"\n"); err != nil {
		t.Fatal(err)
	}
	stats := readStatsEvent(t, events, done, 5*time.Second)
	if stats.Truncated {
		t.Fatalf("empty server must not be truncated: %+v", stats)
	}
	if len(stats.Connections) != 0 {
		t.Fatalf("empty server reported connections: %+v", stats.Connections)
	}
	if len(stats.Bindings) != 0 {
		t.Fatalf("empty server reported bindings: %+v", stats.Bindings)
	}

	if _, err := io.WriteString(pw, managedShutdownCommand+"\n"); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("managed run returned %v", err)
		}
	case <-time.After(managedTestTimeout):
		t.Fatal("managed run did not stop after shutdown")
	}
	if stderr.Len() != 0 {
		t.Fatalf("managed run wrote an error: %q", stderr.String())
	}
}

func TestManagedObserveIsIdempotentAndEOFStops(t *testing.T) {
	path := managedServerConfig(t)
	pr, pw := io.Pipe()
	defer pw.Close()

	events := make(chan []byte, 16)
	var stderr bytes.Buffer
	done := make(chan error, 1)
	go func() {
		done <- runWithInput(context.Background(), []string{"server", "--config", path, "--managed"}, pr, eventWriter{events}, &stderr)
	}()
	waitManagedReady(t, events, done)

	// Repeated observe commands must not stop the process or start a second
	// subscription. A single ticker therefore produces consecutive sequences.
	for i := 0; i < 3; i++ {
		if _, err := io.WriteString(pw, managedObserveCommand+"\n"); err != nil {
			t.Fatal(err)
		}
	}
	first := readStatsEvent(t, events, done, 5*time.Second)
	second := readStatsEvent(t, events, done, 5*time.Second)
	if first.Sequence != 1 || second.Sequence != 2 {
		t.Fatalf("sequences = %d,%d, want 1,2 (observe must be idempotent)", first.Sequence, second.Sequence)
	}

	if err := pw.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("managed run returned %v after EOF", err)
		}
	case <-time.After(managedTestTimeout):
		t.Fatal("managed run did not stop after EOF")
	}
	if stderr.Len() != 0 {
		t.Fatalf("managed run wrote an error: %q", stderr.String())
	}
}

func TestManagedObserveRealCongestionMatrix(t *testing.T) {
	cases := []struct {
		name       string
		congestion string
		mbps       uint64
		want       string
	}{
		{name: "bbr", congestion: "bbr", want: "bbr"},
		{name: "reno", congestion: "reno", want: "reno"},
		{name: "brutal", congestion: "bbr", mbps: 10, want: "brutal"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			fixtures := testFixturesDir()
			echo := startEchoBackend(t)

			// A real server peer uses the shared fixtures; the managed client is
			// the endpoint under test, so stats report its local sender CC.
			server, err := tunnel.StartServer(ctx, config.Config{
				Listen:     "127.0.0.1:0",
				Target:     echo,
				CertFile:   filepath.Join(fixtures, "test.crt"),
				KeyFile:    filepath.Join(fixtures, "test.key"),
				Congestion: tc.congestion,
				UpMbps:     tc.mbps,
				DownMbps:   tc.mbps,
			})
			if err != nil {
				t.Fatalf("StartServer: %v", err)
			}
			defer server.Close()

			path := managedClientConfigFor(t, server.Addr().String(), tc.congestion, tc.mbps)
			pr, pw := io.Pipe()
			defer pw.Close()
			events := make(chan []byte, 16)
			var stderr bytes.Buffer
			done := make(chan error, 1)
			go func() {
				done <- runWithInput(ctx, []string{"client", "--config", path, "--managed"}, pr, eventWriter{events}, &stderr)
			}()
			listen := waitManagedReadyListen(t, events, done, "client")

			conn, err := net.DialTimeout("tcp", listen, 3*time.Second)
			if err != nil {
				t.Fatalf("dial client listener: %v", err)
			}
			if _, err := conn.Write([]byte("telemetry")); err != nil {
				_ = conn.Close()
				t.Fatalf("write through tunnel: %v", err)
			}
			reply := make([]byte, len("telemetry"))
			if _, err := io.ReadFull(conn, reply); err != nil {
				_ = conn.Close()
				t.Fatalf("read through tunnel: %v", err)
			}
			_ = conn.Close()

			if _, err := io.WriteString(pw, managedObserveCommand+"\n"); err != nil {
				t.Fatal(err)
			}
			stats := waitForCongestion(t, events, done, tc.want, 10*time.Second)
			if stats.ElapsedNanos < 1 || stats.Sequence < 1 {
				t.Fatalf("invalid stats header: %+v", stats)
			}

			if _, err := io.WriteString(pw, managedShutdownCommand+"\n"); err != nil {
				t.Fatal(err)
			}
			select {
			case err := <-done:
				if err != nil {
					t.Fatalf("managed run returned %v", err)
				}
			case <-time.After(managedTestTimeout):
				t.Fatal("managed run did not stop after shutdown")
			}
			if stderr.Len() != 0 {
				t.Fatalf("managed run wrote an error: %q", stderr.String())
			}
		})
	}
}
