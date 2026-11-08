package tunnel

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/apernet/hysteria/core/v2/observe"
)

func bridgeTestContext(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	return ctx
}

func bridgeTestToken() string {
	return strings.Repeat("0123456789abcdef", 4)
}

func bridgeValidInfo() observe.StreamContext {
	return observe.StreamContext{
		ConnectionID: 42,
		StreamID:     7,
		RemoteAddr:   "198.51.100.23:25565",
	}
}

func TestWriteBridgePrefaceRoundTrip(t *testing.T) {
	ctx := bridgeTestContext(t)
	writer, reader := net.Pipe()
	defer writer.Close()
	defer reader.Close()

	token := bridgeTestToken()
	info := bridgeValidInfo()
	errCh := make(chan error, 1)
	go func() { errCh <- writeBridgePreface(ctx, writer, token, info) }()

	header := make([]byte, len(bridgeMagic)+4)
	if _, err := io.ReadFull(reader, header); err != nil {
		t.Fatalf("read preface header: %v", err)
	}
	if got := string(header[:len(bridgeMagic)]); got != bridgeMagic {
		t.Fatalf("magic = %q, want %q", got, bridgeMagic)
	}
	length := binary.BigEndian.Uint32(header[len(bridgeMagic):])
	if length == 0 || length > bridgeMaxJSONSize {
		t.Fatalf("metadata length = %d, want 1..%d", length, bridgeMaxJSONSize)
	}
	payload := make([]byte, length)
	if _, err := io.ReadFull(reader, payload); err != nil {
		t.Fatalf("read preface metadata: %v", err)
	}

	var got bridgeMetadata
	if err := json.Unmarshal(payload, &got); err != nil {
		t.Fatalf("decode metadata: %v", err)
	}
	if got.Token != token {
		t.Fatal("metadata token mismatch")
	}
	if got.Peer != info.RemoteAddr {
		t.Fatalf("metadata peer = %q, want %q", got.Peer, info.RemoteAddr)
	}
	if got.ConnectionID != info.ConnectionID {
		t.Fatalf("metadata connectionId = %d, want %d", got.ConnectionID, info.ConnectionID)
	}
	if got.StreamID != info.StreamID {
		t.Fatalf("metadata streamId = %d, want %d", got.StreamID, info.StreamID)
	}
	if err := <-errCh; err != nil {
		t.Fatalf("writeBridgePreface: %v", err)
	}
}

func TestWriteBridgePrefaceEmptyTokenWritesNothing(t *testing.T) {
	infos := []observe.StreamContext{
		bridgeValidInfo(),
		{ConnectionID: 0, StreamID: -1},
	}
	for i, info := range infos {
		t.Run(fmt.Sprintf("case_%d", i), func(t *testing.T) {
			conn := newBridgeRecordingConn()
			if err := writeBridgePreface(context.Background(), conn, "", info); err != nil {
				t.Fatalf("writeBridgePreface: %v", err)
			}
			if got := conn.written(); len(got) != 0 {
				t.Fatalf("wrote %d bytes without a token", len(got))
			}
			if deadline := conn.writeDeadline(); !deadline.IsZero() {
				t.Fatalf("write deadline was set without a token: %v", deadline)
			}
		})
	}
}

func TestWriteBridgePrefaceRejectsInvalidTokenWithoutLeaking(t *testing.T) {
	tokens := []string{
		strings.Repeat("0", 63),
		strings.Repeat("0", 65),
		strings.Repeat("z", 64),
		strings.Repeat("0", 62) + "0g",
	}
	for i, token := range tokens {
		t.Run(fmt.Sprintf("case_%d", i), func(t *testing.T) {
			conn := newBridgeRecordingConn()
			err := writeBridgePreface(context.Background(), conn, token, bridgeValidInfo())
			if err == nil {
				t.Fatal("invalid token was accepted")
			}
			if strings.Contains(err.Error(), token) {
				t.Fatal("invalid token appeared in the error")
			}
			if got := conn.written(); len(got) != 0 {
				t.Fatalf("wrote %d bytes for an invalid token", len(got))
			}
		})
	}
}

func TestWriteBridgePrefaceRejectsUntrustedIdentity(t *testing.T) {
	valid := bridgeValidInfo()
	cases := []struct {
		name string
		info observe.StreamContext
	}{
		{"zero_connection", observe.StreamContext{ConnectionID: 0, StreamID: 0, RemoteAddr: valid.RemoteAddr}},
		{"negative_stream", observe.StreamContext{ConnectionID: 1, StreamID: -1, RemoteAddr: valid.RemoteAddr}},
		{"empty_peer", observe.StreamContext{ConnectionID: 1, StreamID: 0}},
		{"dns_peer", observe.StreamContext{ConnectionID: 1, StreamID: 0, RemoteAddr: "localhost:25565"}},
		{"missing_port", observe.StreamContext{ConnectionID: 1, StreamID: 0, RemoteAddr: "127.0.0.1"}},
		{"zero_port", observe.StreamContext{ConnectionID: 1, StreamID: 0, RemoteAddr: "127.0.0.1:0"}},
		{"high_port", observe.StreamContext{ConnectionID: 1, StreamID: 0, RemoteAddr: "127.0.0.1:65536"}},
		{"non_numeric_port", observe.StreamContext{ConnectionID: 1, StreamID: 0, RemoteAddr: "127.0.0.1:http"}},
		{"signed_port", observe.StreamContext{ConnectionID: 1, StreamID: 0, RemoteAddr: "127.0.0.1:+80"}},
		{"ipv6_missing_brackets", observe.StreamContext{ConnectionID: 1, StreamID: 0, RemoteAddr: "::1:25565"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			conn := newBridgeRecordingConn()
			err := writeBridgePreface(context.Background(), conn, bridgeTestToken(), tc.info)
			if err == nil {
				t.Fatal("untrusted identity was accepted")
			}
			if got := conn.written(); len(got) != 0 {
				t.Fatalf("wrote %d bytes for an untrusted identity", len(got))
			}
			if tc.info.RemoteAddr != "" && strings.Contains(err.Error(), tc.info.RemoteAddr) {
				t.Fatal("peer address appeared in the error")
			}
		})
	}
}

func TestWriteBridgePrefaceAcceptsLiteralIPv6Peer(t *testing.T) {
	conn := newBridgeRecordingConn()
	info := observe.StreamContext{ConnectionID: 1, StreamID: 0, RemoteAddr: "[2001:db8::1]:25565"}
	if err := writeBridgePreface(context.Background(), conn, bridgeTestToken(), info); err != nil {
		t.Fatalf("writeBridgePreface: %v", err)
	}
	if got := conn.written(); len(got) == 0 {
		t.Fatal("no preface written for a literal IPv6 peer")
	}
}

func TestWriteBridgePrefaceClearsWriteDeadline(t *testing.T) {
	conn := newBridgeRecordingConn()
	if err := writeBridgePreface(context.Background(), conn, bridgeTestToken(), bridgeValidInfo()); err != nil {
		t.Fatalf("writeBridgePreface: %v", err)
	}
	if deadline := conn.writeDeadline(); !deadline.IsZero() {
		t.Fatalf("write deadline was not cleared: %v", deadline)
	}
}

func TestWriteBridgePrefaceHandlesShortWrites(t *testing.T) {
	conn := &bridgeShortWriteConn{bridgeRecordingConn: newBridgeRecordingConn(), limit: 3}
	if err := writeBridgePreface(context.Background(), conn, bridgeTestToken(), bridgeValidInfo()); err != nil {
		t.Fatalf("writeBridgePreface: %v", err)
	}
	frame := conn.written()
	if len(frame) < len(bridgeMagic)+4 {
		t.Fatalf("frame length = %d, want at least %d", len(frame), len(bridgeMagic)+4)
	}
	if got := string(frame[:len(bridgeMagic)]); got != bridgeMagic {
		t.Fatalf("magic = %q, want %q", got, bridgeMagic)
	}
	length := binary.BigEndian.Uint32(frame[len(bridgeMagic) : len(bridgeMagic)+4])
	if int(length) != len(frame)-len(bridgeMagic)-4 {
		t.Fatalf("length prefix = %d, body = %d", length, len(frame)-len(bridgeMagic)-4)
	}
	var meta bridgeMetadata
	if err := json.Unmarshal(frame[len(bridgeMagic)+4:], &meta); err != nil {
		t.Fatalf("decode metadata: %v", err)
	}
	if meta.Token != bridgeTestToken() {
		t.Fatal("metadata token mismatch")
	}
}

func TestWriteBridgePrefaceCancelUnblocksBlockedWrite(t *testing.T) {
	writer, reader := net.Pipe()
	defer writer.Close()
	defer reader.Close()
	signal := &bridgeSignalConn{Conn: writer, started: make(chan struct{})}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	errCh := make(chan error, 1)
	go func() { errCh <- writeBridgePreface(ctx, signal, bridgeTestToken(), bridgeValidInfo()) }()

	select {
	case <-signal.started:
	case <-time.After(2 * time.Second):
		t.Fatal("preface write never started")
	}
	start := time.Now()
	cancel()
	select {
	case err := <-errCh:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("error = %v, want context.Canceled", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("canceled preface write did not return")
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("cancellation took %v", elapsed)
	}
}

func TestWriteBridgePrefaceDeadlineUnblocksBlockedWrite(t *testing.T) {
	writer, reader := net.Pipe()
	defer writer.Close()
	defer reader.Close()
	signal := &bridgeSignalConn{Conn: writer, started: make(chan struct{})}

	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	errCh := make(chan error, 1)
	go func() { errCh <- writeBridgePreface(ctx, signal, bridgeTestToken(), bridgeValidInfo()) }()

	select {
	case <-signal.started:
	case <-time.After(2 * time.Second):
		t.Fatal("preface write never started")
	}
	start := time.Now()
	select {
	case err := <-errCh:
		if !errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("error = %v, want context.DeadlineExceeded", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("deadline did not unblock the preface write")
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("deadline unblock took %v", elapsed)
	}
}

// bridgeRecordingConn captures every write and records the current write
// deadline without performing any I/O.
type bridgeRecordingConn struct {
	mu       sync.Mutex
	buf      bytes.Buffer
	deadline time.Time
	closed   bool
}

func newBridgeRecordingConn() *bridgeRecordingConn {
	return &bridgeRecordingConn{}
}

func (c *bridgeRecordingConn) Read([]byte) (int, error) { return 0, io.EOF }

func (c *bridgeRecordingConn) Write(p []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return 0, net.ErrClosed
	}
	return c.buf.Write(p)
}

func (c *bridgeRecordingConn) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.closed = true
	return nil
}

func (c *bridgeRecordingConn) LocalAddr() net.Addr {
	return &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 1}
}

func (c *bridgeRecordingConn) RemoteAddr() net.Addr {
	return &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 2}
}

func (c *bridgeRecordingConn) SetDeadline(t time.Time) error {
	return c.SetWriteDeadline(t)
}

func (c *bridgeRecordingConn) SetReadDeadline(time.Time) error { return nil }

func (c *bridgeRecordingConn) SetWriteDeadline(t time.Time) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.deadline = t
	return nil
}

func (c *bridgeRecordingConn) written() []byte {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]byte(nil), c.buf.Bytes()...)
}

func (c *bridgeRecordingConn) writeDeadline() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.deadline
}

// bridgeShortWriteConn deliberately returns short writes with a nil error so
// the preface writer must loop instead of assuming a single Write call.
type bridgeShortWriteConn struct {
	*bridgeRecordingConn
	limit int
}

func (c *bridgeShortWriteConn) Write(p []byte) (int, error) {
	if len(p) > c.limit {
		p = p[:c.limit]
	}
	return c.bridgeRecordingConn.Write(p)
}

// bridgeSignalConn signals when the first write starts, letting tests cancel a
// genuinely blocked net.Pipe write without sleeping.
type bridgeSignalConn struct {
	net.Conn
	once    sync.Once
	started chan struct{}
}

func (c *bridgeSignalConn) Write(p []byte) (int, error) {
	c.once.Do(func() { close(c.started) })
	return c.Conn.Write(p)
}
