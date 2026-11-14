package tunnel

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/apernet/quic-go"
)

type proxyTestPacket struct {
	data []byte
	addr net.Addr
}

type proxyTestWrite struct {
	data []byte
	addr net.Addr
}

type proxyTestPacketConn struct {
	inbound chan proxyTestPacket
	local   net.Addr

	mu     sync.Mutex
	writes []proxyTestWrite
	closed bool
}

func newProxyTestPacketConn() *proxyTestPacketConn {
	return &proxyTestPacketConn{
		inbound: make(chan proxyTestPacket, 32),
		local:   &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 10000},
	}
}

func (c *proxyTestPacketConn) push(data []byte, addr net.Addr) {
	c.inbound <- proxyTestPacket{data: append([]byte(nil), data...), addr: addr}
}

func (c *proxyTestPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	packet, ok := <-c.inbound
	if !ok {
		return 0, nil, net.ErrClosed
	}
	return copy(p, packet.data), packet.addr, nil
}

func (c *proxyTestPacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return 0, net.ErrClosed
	}
	c.writes = append(c.writes, proxyTestWrite{data: append([]byte(nil), p...), addr: addr})
	return len(p), nil
}

func (c *proxyTestPacketConn) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return nil
	}
	c.closed = true
	close(c.inbound)
	return nil
}

func (c *proxyTestPacketConn) LocalAddr() net.Addr              { return c.local }
func (c *proxyTestPacketConn) SetDeadline(time.Time) error      { return nil }
func (c *proxyTestPacketConn) SetReadDeadline(time.Time) error  { return nil }
func (c *proxyTestPacketConn) SetWriteDeadline(time.Time) error { return nil }

func (c *proxyTestPacketConn) writeCount() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.writes)
}

func (c *proxyTestPacketConn) lastWrite() ([]byte, net.Addr, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.writes) == 0 {
		return nil, nil, false
	}
	write := c.writes[len(c.writes)-1]
	return append([]byte(nil), write.data...), write.addr, true
}

func testProxyV2Header(t *testing.T, source, destination *net.UDPAddr, tlvs []byte) []byte {
	t.Helper()

	var family byte
	var addresses []byte
	source4, destination4 := source.IP.To4(), destination.IP.To4()
	if source4 != nil && destination4 != nil {
		family = 0x12
		addresses = make([]byte, 12)
		copy(addresses[0:4], source4)
		copy(addresses[4:8], destination4)
		binary.BigEndian.PutUint16(addresses[8:10], uint16(source.Port))
		binary.BigEndian.PutUint16(addresses[10:12], uint16(destination.Port))
	} else {
		source16, destination16 := source.IP.To16(), destination.IP.To16()
		if source16 == nil || destination16 == nil {
			t.Fatalf("source and destination must share an address family")
		}
		family = 0x22
		addresses = make([]byte, 36)
		copy(addresses[0:16], source16)
		copy(addresses[16:32], destination16)
		binary.BigEndian.PutUint16(addresses[32:34], uint16(source.Port))
		binary.BigEndian.PutUint16(addresses[34:36], uint16(destination.Port))
	}

	header := make([]byte, 16, 16+len(addresses)+len(tlvs))
	copy(header, proxyPacketV2Signature)
	header[12] = 0x21
	header[13] = family
	binary.BigEndian.PutUint16(header[14:16], uint16(len(addresses)+len(tlvs)))
	header = append(header, addresses...)
	header = append(header, tlvs...)
	return header
}

func testProxyPeer(port int) *net.UDPAddr {
	return &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port}
}

func testProxyPlayer(ip string, port int) *net.UDPAddr {
	return &net.UDPAddr{IP: net.ParseIP(ip), Port: port}
}

func mustProxyPacketConn(t *testing.T, pc net.PacketConn, sources []string, maxConnections int) *proxyPacketConn {
	t.Helper()
	wrapped, err := newProxyPacketConn(pc, sources, maxConnections)
	if err != nil {
		t.Fatalf("newProxyPacketConn: %v", err)
	}
	conn, ok := wrapped.(*proxyPacketConn)
	if !ok {
		t.Fatalf("newProxyPacketConn returned %T", wrapped)
	}
	t.Cleanup(func() { _ = conn.Close() })
	return conn
}

type proxyReadResult struct {
	n    int
	addr net.Addr
	err  error
}

func readProxyPacket(t *testing.T, c net.PacketConn, buf []byte) (int, net.Addr, error) {
	t.Helper()
	results := make(chan proxyReadResult, 1)
	go func() {
		n, addr, err := c.ReadFrom(buf)
		results <- proxyReadResult{n: n, addr: addr, err: err}
	}()
	select {
	case result := <-results:
		return result.n, result.addr, result.err
	case <-time.After(2 * time.Second):
		t.Fatal("ReadFrom timed out")
		return 0, nil, nil
	}
}

func assertProxyRead(t *testing.T, c net.PacketConn, wantAddr *net.UDPAddr, wantPayload []byte) {
	t.Helper()
	buf := make([]byte, proxyPacketReadBufferSize)
	n, addr, err := readProxyPacket(t, c, buf)
	if err != nil {
		t.Fatalf("ReadFrom: %v", err)
	}
	if addr == nil || addr.String() != wantAddr.String() {
		t.Fatalf("client address = %v, want %v", addr, wantAddr)
	}
	if !bytes.Equal(buf[:n], wantPayload) {
		t.Fatalf("payload = %q, want %q", buf[:n], wantPayload)
	}
}

func assertProxyBadHeaderDropped(t *testing.T, c *proxyPacketConn, pc *proxyTestPacketConn, bad []byte, peer, player, destination *net.UDPAddr) {
	t.Helper()
	pc.push(bad, peer)
	valid := testProxyV2Header(t, player, destination, nil)
	pc.push(append(valid, []byte("ok")...), peer)
	assertProxyRead(t, c, player, []byte("ok"))
}

func TestNewProxyPacketConnValidationAndLimits(t *testing.T) {
	if _, err := newProxyPacketConn(nil, []string{"127.0.0.1"}, 1); err == nil {
		t.Fatal("nil PacketConn was accepted")
	}

	pc := newProxyTestPacketConn()
	defer pc.Close()
	for _, sources := range [][]string{
		nil,
		{},
		{"localhost"},
		{"127.0.0.1/33"},
		{"0.0.0.0/0"},
		{"::/0"},
		{"::ffff:127.0.0.1"},
	} {
		if _, err := newProxyPacketConn(pc, sources, 1); err == nil {
			t.Fatalf("sources %v were accepted", sources)
		}
	}

	tooMany := make([]string, proxyPacketMaxSources+1)
	for i := range tooMany {
		tooMany[i] = "127.0.0.1"
	}
	if _, err := newProxyPacketConn(pc, tooMany, 1); err == nil {
		t.Fatal("too many trusted sources were accepted")
	}

	limits := []struct {
		maxConnections int
		want           int
	}{
		{-1, proxyPacketMinRoutes},
		{0, proxyPacketMinRoutes},
		{1, proxyPacketMinRoutes},
		{16, proxyPacketMinRoutes},
		{17, 68},
		{4096, proxyPacketMaxRoutes},
		{100000, proxyPacketMaxRoutes},
	}
	for _, tt := range limits {
		pc := newProxyTestPacketConn()
		wrapped, err := newProxyPacketConn(pc, []string{"127.0.0.1/32"}, tt.maxConnections)
		if err != nil {
			t.Fatalf("maxConnections %d: %v", tt.maxConnections, err)
		}
		conn := wrapped.(*proxyPacketConn)
		if conn.maxRoutes != tt.want {
			t.Fatalf("maxConnections %d: maxRoutes = %d, want %d", tt.maxConnections, conn.maxRoutes, tt.want)
		}
		_ = conn.Close()
	}
}

func TestProxyPacketConnSourceCanonicalization(t *testing.T) {
	tests := []struct {
		name    string
		source  string
		wantErr bool
	}{
		{"slash zero with non-zero host", "1.2.3.4/0", true},
		{"slash zero v6", "::1/0", true},
		{"masked unspecified v4 /8", "0.0.0.1/8", true},
		{"masked unspecified v4 /1", "1.2.3.4/1", true},
		{"masked unspecified v6", "::1/64", true},
		{"valid v4 mask", "10.0.0.1/8", false},
		{"valid v4 mask starting high", "128.0.0.1/1", false},
		{"valid v6 mask", "2001:db8::1/32", false},
		{"single v4 address", "127.0.0.1", false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pc := newProxyTestPacketConn()
			wrapped, err := newProxyPacketConn(pc, []string{tt.source}, 1)
			if tt.wantErr {
				if err == nil {
					_ = wrapped.Close()
					t.Fatalf("source %q was accepted", tt.source)
				}
				_ = pc.Close()
				return
			}
			if err != nil {
				_ = pc.Close()
				t.Fatalf("source %q: %v", tt.source, err)
			}
			_ = wrapped.Close()
		})
	}
}

func TestProxyPacketConnValidHeaderAndReply(t *testing.T) {
	pc := newProxyTestPacketConn()
	c := mustProxyPacketConn(t, pc, []string{"127.0.0.1/32"}, 8)

	peer := testProxyPeer(40000)
	player := testProxyPlayer("198.51.100.7", 12345)
	destination := testProxyPeer(25565)
	header := testProxyV2Header(t, player, destination, nil)

	pc.push(append(append([]byte(nil), header...), []byte("hello")...), peer)
	assertProxyRead(t, c, player, []byte("hello"))

	// A header-only datagram is valid and yields a zero-length payload.
	pc.push(header, peer)
	buf := make([]byte, 8)
	n, addr, err := readProxyPacket(t, c, buf)
	if err != nil {
		t.Fatalf("ReadFrom header-only: %v", err)
	}
	if n != 0 || addr.String() != player.String() {
		t.Fatalf("header-only read = (%d, %v), want (0, %v)", n, addr, player)
	}

	if _, err := c.WriteTo([]byte("world"), addr); err != nil {
		t.Fatalf("WriteTo: %v", err)
	}
	data, writeAddr, ok := pc.lastWrite()
	if !ok {
		t.Fatal("WriteTo did not reach the underlying PacketConn")
	}
	if writeAddr.String() != peer.String() {
		t.Fatalf("reply peer = %v, want %v", writeAddr, peer)
	}
	if !bytes.Equal(data, []byte("world")) {
		t.Fatalf("reply payload included extra bytes: %q", data)
	}
}

func TestProxyPacketConnFirstHeaderThenBare(t *testing.T) {
	pc := newProxyTestPacketConn()
	c := mustProxyPacketConn(t, pc, []string{"127.0.0.1/32"}, 8)

	peer := testProxyPeer(40001)
	player := testProxyPlayer("198.51.100.8", 12346)
	destination := testProxyPeer(25565)
	header := testProxyV2Header(t, player, destination, nil)

	pc.push(append(append([]byte(nil), header...), []byte("first")...), peer)
	assertProxyRead(t, c, player, []byte("first"))

	pc.push([]byte("second"), peer)
	assertProxyRead(t, c, player, []byte("second"))

	// A bare datagram from an unknown peer must not create a route.
	pc.push([]byte("unknown"), testProxyPeer(40002))
	pc.push(append(append([]byte(nil), header...), []byte("third")...), peer)
	assertProxyRead(t, c, player, []byte("third"))
}

func TestProxyPacketConnOldPerPacketHeadersRefresh(t *testing.T) {
	pc := newProxyTestPacketConn()
	c := mustProxyPacketConn(t, pc, []string{"127.0.0.1/32"}, 8)
	now := time.Unix(1700000000, 0)
	c.now = func() time.Time { return now }

	peer := testProxyPeer(40003)
	player := testProxyPlayer("198.51.100.9", 12347)
	destination := testProxyPeer(25565)
	header := testProxyV2Header(t, player, destination, nil)

	pc.push(append(append([]byte(nil), header...), []byte("one")...), peer)
	assertProxyRead(t, c, player, []byte("one"))

	now = now.Add(time.Minute)
	pc.push(append(append([]byte(nil), header...), []byte("two")...), peer)
	assertProxyRead(t, c, player, []byte("two"))

	c.mu.Lock()
	route := c.byClient[proxyPacketUDPAddrKey(player)]
	c.mu.Unlock()
	if route == nil {
		t.Fatal("route disappeared after a repeated header")
	}
	if !route.lastSeen.Equal(now) {
		t.Fatalf("route lastSeen = %v, want %v", route.lastSeen, now)
	}
}

func TestProxyPacketConnConflictsDoNotOverwrite(t *testing.T) {
	pc := newProxyTestPacketConn()
	c := mustProxyPacketConn(t, pc, []string{"127.0.0.1/32"}, 8)

	peerA, peerB := testProxyPeer(40004), testProxyPeer(40005)
	playerA := testProxyPlayer("198.51.100.10", 12348)
	playerB := testProxyPlayer("198.51.100.11", 12349)
	destination := testProxyPeer(25565)
	headerA := testProxyV2Header(t, playerA, destination, nil)
	headerB := testProxyV2Header(t, playerB, destination, nil)

	pc.push(append(append([]byte(nil), headerA...), []byte("a")...), peerA)
	assertProxyRead(t, c, playerA, []byte("a"))

	// Same claimed client from a different peer must not replace peerA.
	pc.push(append(append([]byte(nil), headerA...), []byte("hijack")...), peerB)
	pc.push([]byte("still-a"), peerA)
	assertProxyRead(t, c, playerA, []byte("still-a"))

	if _, err := c.WriteTo([]byte("reply-a"), playerA); err != nil {
		t.Fatalf("WriteTo(playerA): %v", err)
	}
	_, writeAddr, _ := pc.lastWrite()
	if writeAddr.String() != peerA.String() {
		t.Fatalf("playerA reply peer = %v, want %v", writeAddr, peerA)
	}

	// Same peer with a different claimed client must not replace peerA either.
	pc.push(append(append([]byte(nil), headerB...), []byte("b")...), peerA)
	pc.push([]byte("still-a-2"), peerA)
	assertProxyRead(t, c, playerA, []byte("still-a-2"))

	before := pc.writeCount()
	if _, err := c.WriteTo([]byte("reply-b"), playerB); !errors.Is(err, errProxyPacketNoRoute) {
		t.Fatalf("WriteTo(playerB) error = %v, want errProxyPacketNoRoute", err)
	}
	if pc.writeCount() != before {
		t.Fatal("WriteTo(playerB) reached the underlying PacketConn")
	}
}

func TestProxyPacketConnRejectsInvalidHeaders(t *testing.T) {
	peer := testProxyPeer(40006)
	player := testProxyPlayer("198.51.100.12", 12350)
	destination := testProxyPeer(25565)

	local := testProxyV2Header(t, player, destination, nil)
	local[12] = 0x20
	unspec := testProxyV2Header(t, player, destination, nil)
	unspec[13] = 0x00
	tcp4 := testProxyV2Header(t, player, destination, nil)
	tcp4[13] = 0x11
	unix := testProxyV2Header(t, player, destination, nil)
	unix[13] = 0x31
	zeroLength := testProxyV2Header(t, player, destination, nil)
	binary.BigEndian.PutUint16(zeroLength[14:16], 0)
	truncated := testProxyV2Header(t, player, destination, nil)[:20]
	badTLV := testProxyV2Header(t, player, destination, []byte{0xe0, 0x00, 0x05, 0x01})
	shortTLV := testProxyV2Header(t, player, destination, []byte{0xe0, 0x00})

	tests := []struct {
		name string
		bad  []byte
	}{
		{"v1", []byte("PROXY TCP4 192.0.2.1 198.51.100.1 1234 25565\r\n")},
		{"local", local},
		{"unspec", unspec},
		{"tcp4", tcp4},
		{"unix", unix},
		{"zero length", zeroLength},
		{"truncated signature", append([]byte(nil), proxyPacketV2Signature[:8]...)},
		{"truncated header", truncated},
		{"short TLV", shortTLV},
		{"TLV overrun", badTLV},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pc := newProxyTestPacketConn()
			c := mustProxyPacketConn(t, pc, []string{"127.0.0.1/32"}, 8)
			assertProxyBadHeaderDropped(t, c, pc, tt.bad, peer, player, destination)
		})
	}
}

func TestProxyPacketConnRejectsInvalidAddresses(t *testing.T) {
	validSource := testProxyPlayer("198.51.100.13", 12351)
	validDestination := testProxyPeer(25565)
	validSource6 := &net.UDPAddr{IP: net.ParseIP("2001:db8::1"), Port: 12351}
	validDestination6 := &net.UDPAddr{IP: net.ParseIP("2001:db8::2"), Port: 25565}

	tests := []struct {
		name        string
		source      *net.UDPAddr
		destination *net.UDPAddr
	}{
		{"unspecified source v4", &net.UDPAddr{IP: net.IPv4zero, Port: 1234}, validDestination},
		{"multicast source v4", &net.UDPAddr{IP: net.IPv4(224, 0, 0, 1), Port: 1234}, validDestination},
		{"zero source port", &net.UDPAddr{IP: net.ParseIP("198.51.100.14"), Port: 0}, validDestination},
		{"unspecified destination v4", validSource, &net.UDPAddr{IP: net.IPv4zero, Port: 25565}},
		{"multicast destination v4", validSource, &net.UDPAddr{IP: net.IPv4(224, 0, 0, 1), Port: 25565}},
		{"zero destination port", validSource, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0}},
		{"unspecified source v6", &net.UDPAddr{IP: net.ParseIP("::"), Port: 1234}, validDestination6},
		{"multicast source v6", &net.UDPAddr{IP: net.ParseIP("ff02::1"), Port: 1234}, validDestination6},
		{"unspecified destination v6", validSource6, &net.UDPAddr{IP: net.ParseIP("::"), Port: 25565}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pc := newProxyTestPacketConn()
			c := mustProxyPacketConn(t, pc, []string{"127.0.0.1/32"}, 8)
			bad := testProxyV2Header(t, tt.source, tt.destination, nil)
			assertProxyBadHeaderDropped(t, c, pc, bad, testProxyPeer(40007), validSource, validDestination)
		})
	}
}

func TestProxyPacketConnTLVValidation(t *testing.T) {
	peer := testProxyPeer(40008)
	player := testProxyPlayer("198.51.100.15", 12352)
	destination := testProxyPeer(25565)

	t.Run("unknown TLV skipped", func(t *testing.T) {
		pc := newProxyTestPacketConn()
		c := mustProxyPacketConn(t, pc, []string{"127.0.0.1/32"}, 8)
		header := testProxyV2Header(t, player, destination, []byte{0xe0, 0x00, 0x03, 'a', 'b', 'c'})
		pc.push(append(header, []byte("ok")...), peer)
		assertProxyRead(t, c, player, []byte("ok"))
	})

	t.Run("zero-length TLV skipped", func(t *testing.T) {
		pc := newProxyTestPacketConn()
		c := mustProxyPacketConn(t, pc, []string{"127.0.0.1/32"}, 8)
		header := testProxyV2Header(t, player, destination, []byte{0x01, 0x00, 0x00})
		pc.push(append(header, []byte("ok")...), peer)
		assertProxyRead(t, c, player, []byte("ok"))
	})

	t.Run("malformed TLV dropped", func(t *testing.T) {
		pc := newProxyTestPacketConn()
		c := mustProxyPacketConn(t, pc, []string{"127.0.0.1/32"}, 8)
		bad := testProxyV2Header(t, player, destination, []byte{0xe0, 0x00, 0x05, 0x01})
		assertProxyBadHeaderDropped(t, c, pc, bad, peer, player, destination)
	})
}

func TestProxyPacketConnUntrustedSourceCannotMap(t *testing.T) {
	pc := newProxyTestPacketConn()
	c := mustProxyPacketConn(t, pc, []string{"127.0.0.1/32"}, 8)

	trustedPeer := testProxyPeer(40009)
	untrustedPeer := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 2), Port: 40010}
	player := testProxyPlayer("198.51.100.16", 12353)
	destination := testProxyPeer(25565)
	header := testProxyV2Header(t, player, destination, nil)

	pc.push(append(append([]byte(nil), header...), []byte("evil")...), untrustedPeer)
	pc.push(append(append([]byte(nil), header...), []byte("good")...), trustedPeer)
	assertProxyRead(t, c, player, []byte("good"))

	if _, err := c.WriteTo([]byte("reply"), player); err != nil {
		t.Fatalf("WriteTo: %v", err)
	}
	_, writeAddr, _ := pc.lastWrite()
	if writeAddr.String() != trustedPeer.String() {
		t.Fatalf("reply peer = %v, want trusted peer %v", writeAddr, trustedPeer)
	}

	// Bare datagrams from an untrusted source are rejected even after a route
	// exists for the same claimed client on a trusted peer.
	pc.push([]byte("evil-bare"), untrustedPeer)
	pc.push([]byte("good-bare"), trustedPeer)
	assertProxyRead(t, c, player, []byte("good-bare"))
}

func TestProxyPacketConnHeaderPrefixIsNotBare(t *testing.T) {
	pc := newProxyTestPacketConn()
	c := mustProxyPacketConn(t, pc, []string{"127.0.0.1/32"}, 8)

	peer := testProxyPeer(40011)
	player := testProxyPlayer("198.51.100.17", 12354)
	destination := testProxyPeer(25565)
	header := testProxyV2Header(t, player, destination, nil)

	pc.push(append(append([]byte(nil), header...), []byte("first")...), peer)
	assertProxyRead(t, c, player, []byte("first"))

	pc.push(append([]byte(nil), proxyPacketV2Signature[:8]...), peer)
	pc.push([]byte("ok"), peer)
	assertProxyRead(t, c, player, []byte("ok"))

	pc.push(append(append([]byte(nil), proxyPacketV2Signature...), 0x00, 0x00, 0x00), peer)
	pc.push([]byte("ok2"), peer)
	assertProxyRead(t, c, player, []byte("ok2"))
}

func TestProxyPacketConnTTLAndWriteToNoRoute(t *testing.T) {
	pc := newProxyTestPacketConn()
	c := mustProxyPacketConn(t, pc, []string{"127.0.0.1/32"}, 8)
	now := time.Unix(1700000000, 0)
	c.now = func() time.Time { return now }

	peer := testProxyPeer(40012)
	player := testProxyPlayer("198.51.100.18", 12355)
	destination := testProxyPeer(25565)
	header := testProxyV2Header(t, player, destination, nil)

	pc.push(append(append([]byte(nil), header...), []byte("one")...), peer)
	assertProxyRead(t, c, player, []byte("one"))

	now = now.Add(proxyPacketTTL + time.Second)
	before := pc.writeCount()
	if _, err := c.WriteTo([]byte("expired"), player); !errors.Is(err, errProxyPacketNoRoute) {
		t.Fatalf("WriteTo after TTL error = %v, want errProxyPacketNoRoute", err)
	}
	if pc.writeCount() != before {
		t.Fatal("expired WriteTo reached the underlying PacketConn")
	}

	// A bare packet after expiry is dropped; a fresh header recreates the route.
	pc.push([]byte("bare-after-expiry"), peer)
	pc.push(append(append([]byte(nil), header...), []byte("two")...), peer)
	assertProxyRead(t, c, player, []byte("two"))
}

func TestProxyPacketConnRouteLimit(t *testing.T) {
	pc := newProxyTestPacketConn()
	c := mustProxyPacketConn(t, pc, []string{"127.0.0.1/32"}, 8)
	c.maxRoutes = 1

	peerA, peerB := testProxyPeer(40013), testProxyPeer(40014)
	playerA := testProxyPlayer("198.51.100.19", 12356)
	playerB := testProxyPlayer("198.51.100.20", 12357)
	destination := testProxyPeer(25565)
	headerA := testProxyV2Header(t, playerA, destination, nil)
	headerB := testProxyV2Header(t, playerB, destination, nil)

	pc.push(append(append([]byte(nil), headerA...), []byte("a")...), peerA)
	assertProxyRead(t, c, playerA, []byte("a"))

	pc.push(append(append([]byte(nil), headerB...), []byte("b")...), peerB)
	pc.push([]byte("still-a"), peerA)
	assertProxyRead(t, c, playerA, []byte("still-a"))

	before := pc.writeCount()
	if _, err := c.WriteTo([]byte("reply-b"), playerB); !errors.Is(err, errProxyPacketNoRoute) {
		t.Fatalf("WriteTo(playerB) error = %v, want errProxyPacketNoRoute", err)
	}
	if pc.writeCount() != before {
		t.Fatal("WriteTo(playerB) reached the underlying PacketConn")
	}
}

func TestProxyPacketConnReturnedClientIsCopy(t *testing.T) {
	pc := newProxyTestPacketConn()
	c := mustProxyPacketConn(t, pc, []string{"127.0.0.1/32"}, 8)

	peer := testProxyPeer(40016)
	player := testProxyPlayer("198.51.100.24", 12361)
	destination := testProxyPeer(25565)
	header := testProxyV2Header(t, player, destination, nil)

	pc.push(append(append([]byte(nil), header...), []byte("one")...), peer)
	buf := make([]byte, 8)
	n, addr, err := readProxyPacket(t, c, buf)
	if err != nil {
		t.Fatalf("ReadFrom: %v", err)
	}
	if n != 3 || addr.String() != player.String() {
		t.Fatalf("ReadFrom = (%d, %v), want (3, %v)", n, addr, player)
	}
	returned, ok := addr.(*net.UDPAddr)
	if !ok {
		t.Fatalf("returned address is %T", addr)
	}
	returned.IP[0] ^= 0xff
	returned.Port++

	pc.push([]byte("bare"), peer)
	assertProxyRead(t, c, player, []byte("bare"))

	if _, err := c.WriteTo([]byte("reply"), player); err != nil {
		t.Fatalf("WriteTo original player: %v", err)
	}
}

func TestProxyPacketConnCallerBufferTruncation(t *testing.T) {
	pc := newProxyTestPacketConn()
	c := mustProxyPacketConn(t, pc, []string{"127.0.0.1/32"}, 8)

	peer := testProxyPeer(40015)
	player := testProxyPlayer("198.51.100.21", 12358)
	destination := testProxyPeer(25565)
	header := testProxyV2Header(t, player, destination, nil)
	payload := bytes.Repeat([]byte("x"), 100)
	pc.push(append(append([]byte(nil), header...), payload...), peer)

	buf := make([]byte, 8)
	n, addr, err := readProxyPacket(t, c, buf)
	if err != nil {
		t.Fatalf("ReadFrom: %v", err)
	}
	if n != len(buf) {
		t.Fatalf("ReadFrom returned %d bytes, want %d", n, len(buf))
	}
	if addr.String() != player.String() {
		t.Fatalf("client address = %v, want %v", addr, player)
	}
	if !bytes.Equal(buf, payload[:len(buf)]) {
		t.Fatalf("truncated payload = %q, want %q", buf, payload[:len(buf)])
	}
}

func TestProxyPacketConnRealUDP(t *testing.T) {
	serverPC, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("ListenPacket: %v", err)
	}
	c := mustProxyPacketConn(t, serverPC, []string{"127.0.0.1/32"}, 8)

	serverAddr, ok := serverPC.LocalAddr().(*net.UDPAddr)
	if !ok {
		t.Fatalf("server local address is %T", serverPC.LocalAddr())
	}
	client, err := net.DialUDP("udp", nil, serverAddr)
	if err != nil {
		t.Fatalf("DialUDP: %v", err)
	}
	defer client.Close()

	deadline := time.Now().Add(5 * time.Second)
	if err := client.SetDeadline(deadline); err != nil {
		t.Fatalf("client deadline: %v", err)
	}
	if err := c.SetReadDeadline(deadline); err != nil {
		t.Fatalf("read deadline: %v", err)
	}

	player := testProxyPlayer("198.51.100.22", 12359)
	header := testProxyV2Header(t, player, serverAddr, nil)
	if _, err := client.Write(append(header, []byte("real")...)); err != nil {
		t.Fatalf("client write: %v", err)
	}

	buf := make([]byte, 64)
	n, addr, err := c.ReadFrom(buf)
	if err != nil {
		t.Fatalf("ReadFrom: %v", err)
	}
	if addr.String() != player.String() || string(buf[:n]) != "real" {
		t.Fatalf("read = (%v, %q), want (%v, %q)", addr, buf[:n], player, "real")
	}

	if _, err := c.WriteTo([]byte("reply"), addr); err != nil {
		t.Fatalf("WriteTo: %v", err)
	}
	n, err = client.Read(buf)
	if err != nil {
		t.Fatalf("client read: %v", err)
	}
	if string(buf[:n]) != "reply" {
		t.Fatalf("client read = %q, want %q", buf[:n], "reply")
	}
}

type headerOncePacketConn struct {
	net.PacketConn
	header []byte
	once   sync.Once
}

func (c *headerOncePacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	var first []byte
	c.once.Do(func() {
		first = make([]byte, 0, len(c.header)+len(p))
		first = append(first, c.header...)
		first = append(first, p...)
	})
	if first == nil {
		return c.PacketConn.WriteTo(p, addr)
	}
	n, err := c.PacketConn.WriteTo(first, addr)
	if n < len(c.header) {
		return 0, err
	}
	return n - len(c.header), err
}

func TestProxyPacketConnQUICHandshake(t *testing.T) {
	ctx := newTestContext(t)
	identity := newTestIdentity(t, "localhost")
	certificate, err := tls.LoadX509KeyPair(identity.certFile, identity.keyFile)
	if err != nil {
		t.Fatalf("load certificate: %v", err)
	}

	serverPC, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("ListenPacket: %v", err)
	}
	serverConn := mustProxyPacketConn(t, serverPC, []string{"127.0.0.1/32"}, 8)
	serverAddr, ok := serverPC.LocalAddr().(*net.UDPAddr)
	if !ok {
		t.Fatalf("server local address is %T", serverPC.LocalAddr())
	}

	const alpn = "proxy-packet-test"
	transport := &quic.Transport{Conn: serverConn, DisableGSO: true}
	listener, err := transport.Listen(&tls.Config{
		Certificates: []tls.Certificate{certificate},
		NextProtos:   []string{alpn},
	}, &quic.Config{HandshakeIdleTimeout: 5 * time.Second})
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	t.Cleanup(func() {
		_ = listener.Close()
		_ = transport.Close()
	})

	clientPC, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("client ListenPacket: %v", err)
	}
	t.Cleanup(func() { _ = clientPC.Close() })

	player := testProxyPlayer("198.51.100.23", 12360)
	clientConn := &headerOncePacketConn{
		PacketConn: clientPC,
		header:     testProxyV2Header(t, player, serverAddr, nil),
	}

	dialCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	conn, err := quic.Dial(dialCtx, clientConn, serverAddr, &tls.Config{
		ServerName: "localhost",
		RootCAs:    identity.pool,
		NextProtos: []string{alpn},
	}, &quic.Config{HandshakeIdleTimeout: 5 * time.Second})
	if err != nil {
		t.Fatalf("QUIC dial: %v", err)
	}
	t.Cleanup(func() { _ = conn.CloseWithError(0, "") })

	accepted, err := listener.Accept(dialCtx)
	if err != nil {
		t.Fatalf("QUIC accept: %v", err)
	}
	t.Cleanup(func() { _ = accepted.CloseWithError(0, "") })

	stream, err := conn.OpenStream()
	if err != nil {
		t.Fatalf("open stream: %v", err)
	}
	defer stream.Close()

	// quic-go announces a stream to the peer only once data is written, so the
	// client must send before the server can accept the stream.
	deadline := time.Now().Add(5 * time.Second)
	if err := stream.SetDeadline(deadline); err != nil {
		t.Fatalf("client stream deadline: %v", err)
	}
	if _, err := stream.Write([]byte("ping")); err != nil {
		t.Fatalf("client stream write: %v", err)
	}

	serverStream, err := accepted.AcceptStream(dialCtx)
	if err != nil {
		t.Fatalf("accept stream: %v", err)
	}
	defer serverStream.Close()
	if err := serverStream.SetDeadline(deadline); err != nil {
		t.Fatalf("server stream deadline: %v", err)
	}
	buf := make([]byte, 4)
	if _, err := io.ReadFull(serverStream, buf); err != nil {
		t.Fatalf("server stream read: %v", err)
	}
	if string(buf) != "ping" {
		t.Fatalf("server stream read = %q, want %q", buf, "ping")
	}
	if _, err := serverStream.Write([]byte("pong")); err != nil {
		t.Fatalf("server stream write: %v", err)
	}
	if _, err := io.ReadFull(stream, buf); err != nil {
		t.Fatalf("client stream read: %v", err)
	}
	if string(buf) != "pong" {
		t.Fatalf("client stream read = %q, want %q", buf, "pong")
	}
}
