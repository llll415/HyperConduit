package tunnel

import (
	"errors"
	"sort"
	"sync"

	"github.com/apernet/hysteria/core/v2/observe"
)

const (
	bindingStateOpening     = "opening"
	bindingStateEstablished = "established"
)

// Binding is the observed state of one local TCP connection and its mapping to
// a remote stream. StreamID is a pointer because stream 0 is valid and must be
// distinguishable from "not established yet".
type Binding struct {
	ID           uint64 `json:"id"`
	ConnectionID uint64 `json:"connectionId"`
	StreamID     *int64 `json:"streamId,omitempty"`
	LocalAddr    string `json:"localAddr"`
	RemoteAddr   string `json:"remoteAddr"`
	PeerAddr     string `json:"peerAddr,omitempty"` // QUIC peer; distinct from the local TCP socket addresses.
	State        string `json:"state"`
}

// Status is a point-in-time, immutable view of active connections and bindings
// plus cumulative error counts. It deliberately contains no closed history and
// no credentials or raw error messages.
type Status struct {
	Connections []observe.Snapshot   `json:"connections"`
	Bindings    []Binding            `json:"bindings"`
	Errors      map[ErrorCode]uint64 `json:"errors"`
}

// statusStore keeps only active connections and bindings. All access is
// serialized by mu; binding IDs are monotonic for the lifetime of the store.
type statusStore struct {
	mu          sync.Mutex
	connections map[uint64]*observe.Connection
	bindings    map[uint64]Binding
	errors      map[ErrorCode]uint64
	nextBinding uint64
}

func newStatusStore() *statusStore {
	return &statusStore{
		connections: make(map[uint64]*observe.Connection),
		bindings:    make(map[uint64]Binding),
		errors:      make(map[ErrorCode]uint64),
	}
}

// connection adds or removes an observed connection. Closed connections are
// removed rather than retained as history.
func (s *statusStore) connection(c *observe.Connection, online bool) {
	if c == nil {
		return
	}
	id := c.ID()
	s.mu.Lock()
	defer s.mu.Unlock()
	if online {
		s.connections[id] = c
		return
	}
	delete(s.connections, id)
}

// addBinding records a new local-to-remote binding in the opening state and
// returns its stable, monotonic ID.
func (s *statusStore) addBinding(local, remote string) uint64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.nextBinding++
	id := s.nextBinding
	s.bindings[id] = Binding{
		ID:         id,
		LocalAddr:  local,
		RemoteAddr: remote,
		State:      bindingStateOpening,
	}
	return id
}

// establish associates a binding with its observed QUIC connection and stream.
// The stream ID is copied so the returned snapshot never aliases caller data.
// Unknown binding IDs are ignored: the binding may already have been removed.
func (s *statusStore) establish(id uint64, info observe.StreamContext) {
	streamID := info.StreamID
	s.mu.Lock()
	defer s.mu.Unlock()
	b, ok := s.bindings[id]
	if !ok {
		return
	}
	b.ConnectionID = info.ConnectionID
	b.PeerAddr = info.RemoteAddr
	b.StreamID = &streamID
	b.State = bindingStateEstablished
	s.bindings[id] = b
}

// removeBinding forgets a binding once it is no longer active.
func (s *statusStore) removeBinding(id uint64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.bindings, id)
}

// record increments the counter for err's structured code. Errors without a
// recognized *Error code are counted as transport errors, so the key set can
// never grow beyond the fixed whitelist. Raw error text is never retained.
func (s *statusStore) record(err error) {
	if err == nil {
		return
	}
	code := CodeTransport
	var coded *Error
	if errors.As(err, &coded) && coded != nil {
		code = knownErrorCode(coded.Code)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.errors[code]++
}

// knownErrorCode restricts counters to the fixed set of codes. Anything else is
// folded into CodeTransport instead of creating an arbitrary map key.
func knownErrorCode(code ErrorCode) ErrorCode {
	switch code {
	case CodeConfig, CodeTLS, CodeIdentity, CodeStorage, CodeAuthentication, CodeTimeout,
		CodeCanceled, CodeLimit, CodeTarget, CodeTransport:
		return code
	default:
		return CodeTransport
	}
}

// snapshot returns a deep copy of the active state. Connection snapshots and
// binding stream IDs are copied, the error map is copied, and both slices are
// always non-nil so JSON encodes them as [] rather than null. Results are sorted
// by ID for stable output.
func (s *statusStore) snapshot() Status {
	s.mu.Lock()
	conns := make([]*observe.Connection, 0, len(s.connections))
	for _, c := range s.connections {
		conns = append(conns, c)
	}
	bindings := make([]Binding, 0, len(s.bindings))
	for _, b := range s.bindings {
		bindings = append(bindings, cloneBinding(b))
	}
	errs := make(map[ErrorCode]uint64, len(s.errors))
	for code, count := range s.errors {
		errs[code] = count
	}
	s.mu.Unlock()

	snapshots := make([]observe.Snapshot, 0, len(conns))
	for _, c := range conns {
		snapshots = append(snapshots, c.Snapshot())
	}
	sort.Slice(snapshots, func(i, j int) bool { return snapshots[i].ID < snapshots[j].ID })
	sort.Slice(bindings, func(i, j int) bool { return bindings[i].ID < bindings[j].ID })

	return Status{Connections: snapshots, Bindings: bindings, Errors: errs}
}

func cloneBinding(b Binding) Binding {
	if b.StreamID != nil {
		streamID := *b.StreamID
		b.StreamID = &streamID
	}
	return b
}
