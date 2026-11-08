package tunnel

import (
	"bytes"
	"encoding/json"
	"errors"
	"reflect"
	"sync"
	"testing"

	"github.com/apernet/hysteria/core/v2/observe"
)

// unwrapOnly wraps an error for errors.As tests without formatting the inner
// error (which would require the Error implementation to handle a nil cause).
type unwrapOnly struct{ err error }

func (w unwrapOnly) Error() string { return "wrapped" }
func (w unwrapOnly) Unwrap() error { return w.err }

func lookupBinding(status Status, id uint64) (Binding, bool) {
	for _, b := range status.Bindings {
		if b.ID == id {
			return b, true
		}
	}
	return Binding{}, false
}

func mustBinding(t *testing.T, status Status, id uint64) Binding {
	t.Helper()
	b, ok := lookupBinding(status, id)
	if !ok {
		t.Fatalf("binding %d not found in %+v", id, status.Bindings)
	}
	return b
}

func TestStatusStoreEmptySnapshot(t *testing.T) {
	store := newStatusStore()
	status := store.snapshot()

	if status.Connections == nil {
		t.Fatal("Connections is nil, want empty slice")
	}
	if status.Bindings == nil {
		t.Fatal("Bindings is nil, want empty slice")
	}
	if status.Errors == nil {
		t.Fatal("Errors is nil, want empty map")
	}
	if len(status.Connections) != 0 || len(status.Bindings) != 0 || len(status.Errors) != 0 {
		t.Fatalf("new store is not empty: %+v", status)
	}
}

func TestStatusStoreConnectionsTrackOnlyActive(t *testing.T) {
	store := newStatusStore()
	a := observe.New(nil, "client", observe.Congestion{Type: "bbr"})
	b := observe.New(nil, "server", observe.Congestion{Type: "reno"})

	store.connection(a, true)
	store.connection(b, true)

	status := store.snapshot()
	if len(status.Connections) != 2 {
		t.Fatalf("got %d connections, want 2", len(status.Connections))
	}
	if status.Connections[0].ID >= status.Connections[1].ID {
		t.Fatalf("connections are not sorted by ID: %d, %d", status.Connections[0].ID, status.Connections[1].ID)
	}
	if status.Connections[0].ID != a.ID() || status.Connections[1].ID != b.ID() {
		t.Fatalf("connection identity mismatch: got %d/%d, want %d/%d",
			status.Connections[0].ID, status.Connections[1].ID, a.ID(), b.ID())
	}

	store.connection(a, false)
	status = store.snapshot()
	if len(status.Connections) != 1 || status.Connections[0].ID != b.ID() {
		t.Fatalf("closed connection was retained or wrong one removed: %+v", status.Connections)
	}

	store.connection(b, false)
	status = store.snapshot()
	if len(status.Connections) != 0 {
		t.Fatalf("closed connections retained as history: %+v", status.Connections)
	}
	if status.Connections == nil {
		t.Fatal("Connections became nil after removals")
	}

	// Removing an unknown/nil connection must be a harmless no-op.
	store.connection(nil, true)
	store.connection(nil, false)
	store.connection(a, false)
	if got := len(store.snapshot().Connections); got != 0 {
		t.Fatalf("unexpected connections after no-op removals: %d", got)
	}
}

func TestStatusStoreBindingLifecycle(t *testing.T) {
	store := newStatusStore()
	first := store.addBinding("127.0.0.1:30001", "minecraft:0")
	second := store.addBinding("127.0.0.1:30002", "minecraft:0")
	if first == 0 || second != first+1 {
		t.Fatalf("binding IDs are not monotonic: first=%d second=%d", first, second)
	}

	status := store.snapshot()
	if len(status.Bindings) != 2 {
		t.Fatalf("got %d bindings, want 2", len(status.Bindings))
	}
	for _, b := range status.Bindings {
		if b.State != bindingStateOpening {
			t.Fatalf("binding %d state = %q, want %q", b.ID, b.State, bindingStateOpening)
		}
		if b.ConnectionID != 0 || b.StreamID != nil {
			t.Fatalf("opening binding %d already has identity: %+v", b.ID, b)
		}
	}
	if got := mustBinding(t, status, first); got.LocalAddr != "127.0.0.1:30001" || got.RemoteAddr != "minecraft:0" {
		t.Fatalf("binding addresses were not preserved: %+v", got)
	}

	store.establish(first, observe.StreamContext{ConnectionID: 42, StreamID: 0})
	status = store.snapshot()
	established := mustBinding(t, status, first)
	if established.State != bindingStateEstablished {
		t.Fatalf("state = %q, want %q", established.State, bindingStateEstablished)
	}
	if established.ConnectionID != 42 {
		t.Fatalf("ConnectionID = %d, want 42", established.ConnectionID)
	}
	if established.StreamID == nil {
		t.Fatal("StreamID is nil after establishing stream 0")
	}
	if *established.StreamID != 0 {
		t.Fatalf("StreamID = %d, want 0", *established.StreamID)
	}
	if still := mustBinding(t, status, second); still.State != bindingStateOpening || still.ConnectionID != 0 {
		t.Fatalf("establish modified a different binding: %+v", still)
	}

	store.removeBinding(first)
	status = store.snapshot()
	if len(status.Bindings) != 1 {
		t.Fatalf("got %d bindings after removal, want 1", len(status.Bindings))
	}
	if _, ok := lookupBinding(status, first); ok {
		t.Fatal("removed binding is still present")
	}

	// Unknown IDs are ignored by both operations.
	store.establish(9999, observe.StreamContext{ConnectionID: 1, StreamID: 1})
	store.removeBinding(9999)
	if got := len(store.snapshot().Bindings); got != 1 {
		t.Fatalf("unknown binding operations changed state: %d bindings", got)
	}
}

func TestStatusStoreBindingIdentityIsolation(t *testing.T) {
	store := newStatusStore()
	zero := store.addBinding("local-a", "remote-a")
	other := store.addBinding("local-b", "remote-b")

	store.establish(zero, observe.StreamContext{ConnectionID: 10, StreamID: 0})
	store.establish(other, observe.StreamContext{ConnectionID: 20, StreamID: 0})

	status := store.snapshot()
	a := mustBinding(t, status, zero)
	b := mustBinding(t, status, other)
	if a.ConnectionID != 10 || b.ConnectionID != 20 {
		t.Fatalf("connection identities crossed: a=%d b=%d", a.ConnectionID, b.ConnectionID)
	}
	if a.LocalAddr != "local-a" || a.RemoteAddr != "remote-a" || b.LocalAddr != "local-b" || b.RemoteAddr != "remote-b" {
		t.Fatalf("binding addresses crossed: a=%+v b=%+v", a, b)
	}
	if a.StreamID == nil || b.StreamID == nil {
		t.Fatal("stream IDs are nil")
	}
	if a.StreamID == b.StreamID {
		t.Fatal("bindings share a StreamID pointer")
	}
	if *a.StreamID != 0 || *b.StreamID != 0 {
		t.Fatalf("stream IDs = %d/%d, want 0/0", *a.StreamID, *b.StreamID)
	}

	// Mutating one snapshot's stream ID must not affect the other binding or a
	// later snapshot.
	*a.StreamID = 123
	if got := mustBinding(t, store.snapshot(), other); got.StreamID == nil || *got.StreamID != 0 {
		t.Fatalf("mutating one binding affected another: %+v", got)
	}
}

func TestStatusStoreSnapshotIsDeepCopy(t *testing.T) {
	store := newStatusStore()
	conn := observe.New(nil, "client", observe.Congestion{Type: "bbr"})
	store.connection(conn, true)
	id := store.addBinding("local", "remote")
	store.establish(id, observe.StreamContext{ConnectionID: conn.ID(), StreamID: 7})
	store.record(&Error{Code: CodeTimeout})

	first := store.snapshot()
	if len(first.Connections) != 1 || len(first.Bindings) != 1 {
		t.Fatalf("unexpected snapshot: %+v", first)
	}
	first.Connections[0].ID = 999999
	first.Bindings[0].State = "mutated"
	first.Bindings[0].LocalAddr = "mutated"
	*first.Bindings[0].StreamID = 12345
	first.Errors[CodeTimeout] = 999
	first.Errors[CodeConfig] = 1

	second := store.snapshot()
	if second.Connections[0].ID != conn.ID() {
		t.Fatalf("connection snapshot was not copied: %d", second.Connections[0].ID)
	}
	b := second.Bindings[0]
	if b.State != bindingStateEstablished || b.LocalAddr != "local" {
		t.Fatalf("binding was not copied: %+v", b)
	}
	if b.StreamID == nil || *b.StreamID != 7 {
		t.Fatalf("StreamID was not copied: %+v", b.StreamID)
	}
	if b.StreamID == first.Bindings[0].StreamID {
		t.Fatal("snapshots share a StreamID pointer")
	}
	if second.Errors[CodeTimeout] != 1 {
		t.Fatalf("error map was not copied: %+v", second.Errors)
	}
	if _, ok := second.Errors[CodeConfig]; ok {
		t.Fatalf("mutating a snapshot added an error key to the store: %+v", second.Errors)
	}
}

func TestStatusStoreRecordErrorClassification(t *testing.T) {
	store := newStatusStore()
	store.record(nil)

	for _, code := range []ErrorCode{
		CodeConfig, CodeTLS, CodeAuthentication, CodeTimeout,
		CodeCanceled, CodeLimit, CodeTarget, CodeTransport,
	} {
		store.record(&Error{Code: code})
	}
	// Wrapped structured errors are still classified by code.
	store.record(unwrapOnly{err: &Error{Code: CodeTarget}})
	// Unstructured errors and unknown codes fall back to transport.
	store.record(errors.New("raw detail that must not be retained"))
	store.record(&Error{Code: ErrorCode("not-a-known-code")})

	want := map[ErrorCode]uint64{
		CodeConfig:         1,
		CodeTLS:            1,
		CodeAuthentication: 1,
		CodeTimeout:        1,
		CodeCanceled:       1,
		CodeLimit:          1,
		CodeTarget:         2,
		CodeTransport:      3,
	}
	if got := store.snapshot().Errors; !reflect.DeepEqual(got, want) {
		t.Fatalf("error counts = %+v, want %+v", got, want)
	}

	encoded, err := json.Marshal(store.snapshot())
	if err != nil {
		t.Fatalf("marshal status: %v", err)
	}
	if bytes.Contains(encoded, []byte("raw detail")) {
		t.Fatalf("raw error text leaked into status: %s", encoded)
	}
}

func TestStatusStoreConcurrentLifecycle(t *testing.T) {
	store := newStatusStore()
	const (
		workers = 100
		cycles  = 100
	)
	ids := make(chan uint64, workers*cycles)
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < cycles; j++ {
				conn := observe.New(nil, "client", observe.Congestion{Type: "bbr"})
				store.connection(conn, true)
				id := store.addBinding("local", "remote")
				ids <- id
				store.establish(id, observe.StreamContext{ConnectionID: conn.ID(), StreamID: int64(j)})
				store.record(&Error{Code: CodeTimeout})

				status := store.snapshot()
				if b, ok := lookupBinding(status, id); !ok {
					t.Errorf("binding %d disappeared while active", id)
				} else if b.State != bindingStateEstablished || b.StreamID == nil || *b.StreamID != int64(j) {
					t.Errorf("binding %d has inconsistent state: %+v", id, b)
				} else if b.ConnectionID != conn.ID() {
					t.Errorf("binding %d connection = %d, want %d", id, b.ConnectionID, conn.ID())
				}

				store.removeBinding(id)
				store.connection(conn, false)
				conn.Close()
			}
		}()
	}
	wg.Wait()
	close(ids)

	seen := make(map[uint64]struct{}, workers*cycles)
	for id := range ids {
		if id == 0 {
			t.Fatal("binding ID 0 was issued")
		}
		if _, ok := seen[id]; ok {
			t.Fatalf("binding ID %d was reused", id)
		}
		seen[id] = struct{}{}
	}
	if len(seen) != workers*cycles {
		t.Fatalf("got %d unique binding IDs, want %d", len(seen), workers*cycles)
	}

	final := store.snapshot()
	if len(final.Connections) != 0 {
		t.Fatalf("%d connections remained active", len(final.Connections))
	}
	if len(final.Bindings) != 0 {
		t.Fatalf("%d bindings remained active", len(final.Bindings))
	}
	if final.Connections == nil || final.Bindings == nil || final.Errors == nil {
		t.Fatalf("final snapshot contains nil containers: %+v", final)
	}
	if final.Errors[CodeTimeout] != workers*cycles {
		t.Fatalf("timeout errors = %d, want %d", final.Errors[CodeTimeout], workers*cycles)
	}
}
