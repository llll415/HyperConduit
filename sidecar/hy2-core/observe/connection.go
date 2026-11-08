// Package observe exposes read-only snapshots of QUIC connections.
//
// The types here only observe an existing transport. They never control it,
// close it, or expose authentication material. Callers remain responsible for
// the lifetime of the underlying *quic.Conn.
package observe

import (
	"context"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/apernet/quic-go"
)

const (
	stateEstablished = "established"
	stateClosed      = "closed"
)

// connectionIDCounter is process-wide so IDs remain unique across all
// observed client and server connections. IDs are sequential, not random.
var connectionIDCounter atomic.Uint64

// Congestion describes the local sender's congestion controller.
//
// Type is one of "bbr", "reno", or "brutal". TxBytesPerSecond is only
// meaningful for "brutal" and is expected to be zero for the other types. A
// zero value does not mean the connection is limited to zero throughput, only
// that the connection is not using a fixed-rate Brutal sender. This value
// describes the local send controller and makes no claim about the peer's
// controller.
type Congestion struct {
	Type             string `json:"type"`
	TxBytesPerSecond uint64 `json:"txBytesPerSecond"`
}

// Snapshot is an immutable point-in-time view of a connection.
//
// Byte and packet counters come from the QUIC transport layer. They include
// retransmissions and duplicate data, so they are not a measure of useful
// application payload (for example, MC protocol data). Lost counters may
// decrease because packets declared lost can subsequently be acknowledged.
type Snapshot struct {
	ID         uint64     `json:"id"`
	Role       string     `json:"role"`
	State      string     `json:"state"`
	LocalAddr  string     `json:"localAddr"`
	RemoteAddr string     `json:"remoteAddr"`
	StartedAt  time.Time  `json:"startedAt"`
	Congestion Congestion `json:"congestion"`

	MinRTTNanos       int64 `json:"minRttNanos"`
	LatestRTTNanos    int64 `json:"latestRttNanos"`
	SmoothedRTTNanos  int64 `json:"smoothedRttNanos"`
	RTTDeviationNanos int64 `json:"rttDeviationNanos"`

	BytesSent       uint64 `json:"bytesSent"`
	BytesReceived   uint64 `json:"bytesReceived"`
	PacketsSent     uint64 `json:"packetsSent"`
	PacketsReceived uint64 `json:"packetsReceived"`
	BytesLost       uint64 `json:"bytesLost"`
	PacketsLost     uint64 `json:"packetsLost"`
}

// Connection is a concurrency-safe, read-only observer for a QUIC connection.
type Connection struct {
	mu       sync.RWMutex
	id       uint64
	conn     *quic.Conn
	closed   bool
	snapshot Snapshot
}

// New creates an observer for conn. It records the connection's actual local
// and remote addresses and marks the snapshot as established. New does not
// take ownership of conn; closing the observer never closes the transport.
//
// conn is expected to be non-nil in production. A nil conn is tolerated for
// tests and produces an observer with empty addresses and zero counters.
func New(conn *quic.Conn, role string, cc Congestion) *Connection {
	return newConnection(connectionIDCounter.Add(1), conn, role, cc)
}

func newConnection(id uint64, conn *quic.Conn, role string, cc Congestion) *Connection {
	c := &Connection{
		id:   id,
		conn: conn,
		snapshot: Snapshot{
			ID:         id,
			Role:       role,
			State:      stateEstablished,
			StartedAt:  time.Now().UTC(),
			Congestion: cc,
		},
	}
	if conn != nil {
		c.snapshot.LocalAddr = addrString(conn.LocalAddr())
		c.snapshot.RemoteAddr = addrString(conn.RemoteAddr())
	}
	return c
}

// ID returns the immutable, process-wide unique ID assigned at creation.
func (c *Connection) ID() uint64 {
	return c.id
}

// Snapshot returns a value copy of the current state. It reads the latest QUIC
// statistics while holding the observer lock, so the result is internally
// consistent with respect to Close. The returned snapshot contains no pointers
// to the underlying connection.
func (c *Connection) Snapshot() Snapshot {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.snapshotLocked()
}

// Close freezes the final QUIC statistics, marks the snapshot as closed, and
// releases the reference to the underlying connection. It is idempotent and
// never closes the actual transport; it only stops observing it.
func (c *Connection) Close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return
	}
	c.snapshot = c.snapshotLocked()
	c.snapshot.State = stateClosed
	c.closed = true
	c.conn = nil
}

// snapshotLocked must be called with c.mu held for reading or writing.
func (c *Connection) snapshotLocked() Snapshot {
	s := c.snapshot
	if c.conn != nil {
		applyConnectionStats(&s, c.conn.ConnectionStats())
		if c.conn.Context().Err() != nil {
			s.State = stateClosed
		}
	}
	return s
}

func applyConnectionStats(s *Snapshot, stats quic.ConnectionStats) {
	s.MinRTTNanos = int64(stats.MinRTT)
	s.LatestRTTNanos = int64(stats.LatestRTT)
	s.SmoothedRTTNanos = int64(stats.SmoothedRTT)
	s.RTTDeviationNanos = int64(stats.MeanDeviation)
	s.BytesSent = stats.BytesSent
	s.BytesReceived = stats.BytesReceived
	s.PacketsSent = stats.PacketsSent
	s.PacketsReceived = stats.PacketsReceived
	s.BytesLost = stats.BytesLost
	s.PacketsLost = stats.PacketsLost
}

func addrString(addr net.Addr) string {
	if addr == nil {
		return ""
	}
	return addr.String()
}

// StreamContext identifies an observed QUIC stream without carrying any user
// credentials or MC handshake data. It is intended for optional contextual
// outbound logging on the server side.
type StreamContext struct {
	ConnectionID uint64
	StreamID     int64
	RemoteAddr   string
}

type streamContextKey struct{}

// WithStreamContext returns a child context carrying info. It replaces any
// previously stored StreamContext.
func WithStreamContext(ctx context.Context, info StreamContext) context.Context {
	if ctx == nil {
		ctx = context.Background()
	}
	return context.WithValue(ctx, streamContextKey{}, info)
}

// StreamFromContext extracts the StreamContext stored by WithStreamContext.
func StreamFromContext(ctx context.Context) (StreamContext, bool) {
	if ctx == nil {
		return StreamContext{}, false
	}
	info, ok := ctx.Value(streamContextKey{}).(StreamContext)
	return info, ok
}
