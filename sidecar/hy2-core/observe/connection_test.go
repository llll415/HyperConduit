package observe

import (
	"context"
	"encoding/json"
	"sync"
	"testing"
	"time"

	"github.com/apernet/quic-go"
)

func TestNewAssignsUniqueIDs(t *testing.T) {
	const count = 100

	seen := make(map[uint64]struct{}, count)
	var previous uint64
	for i := 0; i < count; i++ {
		c := New(nil, "client", Congestion{Type: "bbr"})
		id := c.ID()
		if id == 0 {
			t.Fatalf("connection %d got zero ID", i)
		}
		if id <= previous {
			t.Fatalf("connection %d ID %d is not greater than previous ID %d", i, id, previous)
		}
		if _, exists := seen[id]; exists {
			t.Fatalf("connection %d got duplicate ID %d", i, id)
		}
		seen[id] = struct{}{}
		previous = id

		if got := c.Snapshot().ID; got != id {
			t.Fatalf("snapshot ID %d does not match ID() %d", got, id)
		}
	}
}

func TestNewRecordsInitialSnapshot(t *testing.T) {
	cc := Congestion{Type: "brutal", TxBytesPerSecond: 12_500_000}
	c := New(nil, "server", cc)

	s := c.Snapshot()
	if s.ID != c.ID() {
		t.Fatalf("ID = %d, want %d", s.ID, c.ID())
	}
	if s.Role != "server" {
		t.Fatalf("Role = %q, want server", s.Role)
	}
	if s.State != stateEstablished {
		t.Fatalf("State = %q, want %q", s.State, stateEstablished)
	}
	if s.StartedAt.IsZero() {
		t.Fatal("StartedAt is zero")
	}
	if s.Congestion != cc {
		t.Fatalf("Congestion = %+v, want %+v", s.Congestion, cc)
	}
	if s.LocalAddr != "" || s.RemoteAddr != "" {
		t.Fatalf("addresses = %q/%q, want empty for nil conn", s.LocalAddr, s.RemoteAddr)
	}
	if s.MinRTTNanos != 0 || s.LatestRTTNanos != 0 || s.SmoothedRTTNanos != 0 || s.RTTDeviationNanos != 0 {
		t.Fatalf("unexpected RTT values: %+v", s)
	}
	if s.BytesSent != 0 || s.BytesReceived != 0 || s.PacketsSent != 0 ||
		s.PacketsReceived != 0 || s.BytesLost != 0 || s.PacketsLost != 0 {
		t.Fatalf("unexpected counter values: %+v", s)
	}
}

func TestApplyConnectionStats(t *testing.T) {
	var s Snapshot
	applyConnectionStats(&s, quic.ConnectionStats{
		MinRTT:          time.Millisecond,
		LatestRTT:       2 * time.Millisecond,
		SmoothedRTT:     3 * time.Millisecond,
		MeanDeviation:   4 * time.Millisecond,
		BytesSent:       5,
		PacketsSent:     6,
		BytesReceived:   7,
		PacketsReceived: 8,
		BytesLost:       9,
		PacketsLost:     10,
	})

	want := Snapshot{
		MinRTTNanos:       int64(time.Millisecond),
		LatestRTTNanos:    int64(2 * time.Millisecond),
		SmoothedRTTNanos:  int64(3 * time.Millisecond),
		RTTDeviationNanos: int64(4 * time.Millisecond),
		BytesSent:         5,
		PacketsSent:       6,
		BytesReceived:     7,
		PacketsReceived:   8,
		BytesLost:         9,
		PacketsLost:       10,
	}
	if s != want {
		t.Fatalf("snapshot stats = %+v, want %+v", s, want)
	}
}

func TestSnapshotReturnsCopy(t *testing.T) {
	c := New(nil, "client", Congestion{Type: "reno"})

	s := c.Snapshot()
	s.State = "tampered"
	s.Role = "tampered"
	s.Congestion.Type = "tampered"
	s.BytesSent = 999

	got := c.Snapshot()
	if got.State != stateEstablished || got.Role != "client" {
		t.Fatalf("snapshot was mutated through returned value: %+v", got)
	}
	if got.Congestion.Type != "reno" {
		t.Fatalf("Congestion.Type = %q, want reno", got.Congestion.Type)
	}
	if got.BytesSent != 0 {
		t.Fatalf("BytesSent = %d, want 0", got.BytesSent)
	}
}

func TestCloseFreezesSnapshotAndIsIdempotent(t *testing.T) {
	c := New(nil, "client", Congestion{Type: "bbr"})
	before := c.Snapshot()

	c.Close()
	closed := c.Snapshot()
	if closed.State != stateClosed {
		t.Fatalf("State = %q, want %q", closed.State, stateClosed)
	}
	if closed.ID != before.ID || closed.Role != before.Role ||
		!closed.StartedAt.Equal(before.StartedAt) || closed.Congestion != before.Congestion {
		t.Fatalf("Close changed immutable fields: before=%+v after=%+v", before, closed)
	}
	if closed.MinRTTNanos != before.MinRTTNanos ||
		closed.LatestRTTNanos != before.LatestRTTNanos ||
		closed.SmoothedRTTNanos != before.SmoothedRTTNanos ||
		closed.RTTDeviationNanos != before.RTTDeviationNanos ||
		closed.BytesSent != before.BytesSent ||
		closed.BytesReceived != before.BytesReceived ||
		closed.PacketsSent != before.PacketsSent ||
		closed.PacketsReceived != before.PacketsReceived ||
		closed.BytesLost != before.BytesLost ||
		closed.PacketsLost != before.PacketsLost {
		t.Fatalf("Close changed frozen stats: before=%+v after=%+v", before, closed)
	}

	c.Close()
	again := c.Snapshot()
	if again != closed {
		t.Fatalf("second Close changed snapshot: first=%+v second=%+v", closed, again)
	}
	if c.ID() != before.ID {
		t.Fatalf("ID changed after Close: %d -> %d", before.ID, c.ID())
	}
}

func TestConcurrentSnapshotAndClose(t *testing.T) {
	c := New(nil, "client", Congestion{Type: "brutal", TxBytesPerSecond: 1_000_000})
	id := c.ID()

	var wg sync.WaitGroup
	for i := 0; i < 32; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				s := c.Snapshot()
				if s.ID != id {
					t.Errorf("snapshot ID = %d, want %d", s.ID, id)
					return
				}
				if s.State != stateEstablished && s.State != stateClosed {
					t.Errorf("unexpected state %q", s.State)
					return
				}
			}
		}()
	}

	wg.Add(1)
	go func() {
		defer wg.Done()
		c.Close()
	}()
	wg.Wait()

	if got := c.Snapshot().State; got != stateClosed {
		t.Fatalf("State = %q after concurrent Close, want %q", got, stateClosed)
	}
}

func TestSnapshotJSONFieldNames(t *testing.T) {
	s := Snapshot{
		ID:         7,
		Role:       "client",
		State:      stateEstablished,
		LocalAddr:  "127.0.0.1:1000",
		RemoteAddr: "127.0.0.1:2000",
		StartedAt:  time.Unix(1_700_000_000, 0).UTC(),
		Congestion: Congestion{Type: "brutal", TxBytesPerSecond: 42},
	}

	raw, err := json.Marshal(s)
	if err != nil {
		t.Fatalf("Marshal: %v", err)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(raw, &fields); err != nil {
		t.Fatalf("Unmarshal: %v", err)
	}

	want := []string{
		"id", "role", "state", "localAddr", "remoteAddr", "startedAt",
		"congestion", "minRttNanos", "latestRttNanos", "smoothedRttNanos",
		"rttDeviationNanos", "bytesSent", "bytesReceived", "packetsSent",
		"packetsReceived", "bytesLost", "packetsLost",
	}
	if len(fields) != len(want) {
		t.Fatalf("got %d JSON fields, want %d: %s", len(fields), len(want), raw)
	}
	for _, name := range want {
		if _, ok := fields[name]; !ok {
			t.Fatalf("missing JSON field %q in %s", name, raw)
		}
	}
	for name := range fields {
		if name == "" || name[0] < 'a' || name[0] > 'z' {
			t.Fatalf("JSON field %q is not camelCase", name)
		}
	}

	var cc map[string]json.RawMessage
	if err := json.Unmarshal(fields["congestion"], &cc); err != nil {
		t.Fatalf("unmarshal congestion: %v", err)
	}
	if len(cc) != 2 {
		t.Fatalf("congestion has %d fields, want 2: %s", len(cc), fields["congestion"])
	}
	if _, ok := cc["type"]; !ok {
		t.Fatalf("congestion missing type: %s", fields["congestion"])
	}
	if _, ok := cc["txBytesPerSecond"]; !ok {
		t.Fatalf("congestion missing txBytesPerSecond: %s", fields["congestion"])
	}
}

func TestStreamContextRoundTrip(t *testing.T) {
	if _, ok := StreamFromContext(context.Background()); ok {
		t.Fatal("empty context unexpectedly contains StreamContext")
	}

	info := StreamContext{
		ConnectionID: 42,
		StreamID:     7,
		RemoteAddr:   "127.0.0.1:12345",
	}
	ctx := WithStreamContext(context.Background(), info)
	got, ok := StreamFromContext(ctx)
	if !ok {
		t.Fatal("StreamFromContext did not find stored value")
	}
	if got != info {
		t.Fatalf("StreamContext = %+v, want %+v", got, info)
	}

	replacement := StreamContext{ConnectionID: 43, StreamID: 8}
	ctx = WithStreamContext(ctx, replacement)
	got, ok = StreamFromContext(ctx)
	if !ok || got != replacement {
		t.Fatalf("StreamContext = %+v, ok=%v, want %+v", got, ok, replacement)
	}

	if _, ok := StreamFromContext(context.Background()); ok {
		t.Fatal("StreamContext leaked into background context")
	}
}

func TestStreamContextNilContext(t *testing.T) {
	ctx := WithStreamContext(nil, StreamContext{ConnectionID: 1, StreamID: 2})
	got, ok := StreamFromContext(ctx)
	if !ok {
		t.Fatal("StreamFromContext did not find value in context created from nil")
	}
	if got.ConnectionID != 1 || got.StreamID != 2 {
		t.Fatalf("StreamContext = %+v", got)
	}

	if _, ok := StreamFromContext(nil); ok {
		t.Fatal("nil context unexpectedly contains StreamContext")
	}
}
