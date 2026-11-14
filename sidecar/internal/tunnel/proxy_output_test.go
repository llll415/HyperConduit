package tunnel

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/apernet/hysteria/core/v2/observe"

	"github.com/llll415/HyperConduit/sidecar/internal/config"
)

// proxyOutputTestHeader builds the exact wire image the encoder must produce:
// 12-byte signature, 0x21 version/command, family/protocol, 16-bit length,
// addresses in network byte order, then source and destination ports. No TLVs.
func proxyOutputTestHeader(t *testing.T, family byte, source, destination []byte, sourcePort, destinationPort uint16) []byte {
	t.Helper()

	addressLen := len(source) + len(destination) + 4
	header := make([]byte, 16, 16+addressLen)
	copy(header, proxyPacketV2Signature)
	header[12] = proxyOutputVersionCommand
	header[13] = family
	binary.BigEndian.PutUint16(header[14:16], uint16(addressLen))
	header = append(header, source...)
	header = append(header, destination...)

	var ports [4]byte
	binary.BigEndian.PutUint16(ports[0:2], sourcePort)
	binary.BigEndian.PutUint16(ports[2:4], destinationPort)
	return append(header, ports[:]...)
}

func proxyOutputTestAddrBytes(t *testing.T, family byte, addr netip.Addr) []byte {
	t.Helper()

	addr = addr.Unmap()
	if family == proxyOutputFamilyTCP4 {
		if !addr.Is4() {
			t.Fatalf("test case expects an IPv4 address, got %s", addr)
		}
		b := addr.As4()
		return b[:]
	}
	b := addr.As16()
	return b[:]
}

func TestEncodeProxyV2HeaderFamilies(t *testing.T) {
	tests := []struct {
		name        string
		source      string
		destination string
		family      byte
		wantSource  netip.Addr
		wantTarget  netip.Addr
	}{
		{
			name:        "ipv4",
			source:      "198.51.100.23:51000",
			destination: "10.1.2.3:25565",
			family:      proxyOutputFamilyTCP4,
			wantSource:  netip.MustParseAddr("198.51.100.23"),
			wantTarget:  netip.MustParseAddr("10.1.2.3"),
		},
		{
			name:        "ipv6",
			source:      "[2001:db8::1]:51000",
			destination: "[2001:db8::2]:25565",
			family:      proxyOutputFamilyTCP6,
			wantSource:  netip.MustParseAddr("2001:db8::1"),
			wantTarget:  netip.MustParseAddr("2001:db8::2"),
		},
		{
			name:        "ipv4 source with ipv6 target",
			source:      "198.51.100.23:51000",
			destination: "[2001:db8::2]:25565",
			family:      proxyOutputFamilyTCP6,
			wantSource:  netip.MustParseAddr("::ffff:198.51.100.23"),
			wantTarget:  netip.MustParseAddr("2001:db8::2"),
		},
		{
			name:        "ipv6 source with ipv4 target",
			source:      "[2001:db8::1]:51000",
			destination: "10.1.2.3:25565",
			family:      proxyOutputFamilyTCP6,
			wantSource:  netip.MustParseAddr("2001:db8::1"),
			wantTarget:  netip.MustParseAddr("::ffff:10.1.2.3"),
		},
		{
			name:        "ipv4 mapped source collapses to tcp4",
			source:      "[::ffff:198.51.100.23]:51000",
			destination: "10.1.2.3:25565",
			family:      proxyOutputFamilyTCP4,
			wantSource:  netip.MustParseAddr("198.51.100.23"),
			wantTarget:  netip.MustParseAddr("10.1.2.3"),
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			source := netip.MustParseAddrPort(tt.source)
			destination := netip.MustParseAddrPort(tt.destination)

			header, err := encodeProxyV2Header(source, destination)
			if err != nil {
				t.Fatalf("encodeProxyV2Header: %v", err)
			}
			want := proxyOutputTestHeader(t, tt.family,
				proxyOutputTestAddrBytes(t, tt.family, tt.wantSource),
				proxyOutputTestAddrBytes(t, tt.family, tt.wantTarget),
				source.Port(), destination.Port())
			if !bytes.Equal(header, want) {
				t.Fatalf("header = %x, want %x", header, want)
			}
			// The declared length must cover exactly the address block.
			if got, wantLen := binary.BigEndian.Uint16(header[14:16]), uint16(len(header)-16); got != wantLen {
				t.Fatalf("length field = %d, want %d", got, wantLen)
			}
		})
	}
}

func TestEncodeProxyV2HeaderRejectsInvalidEndpoints(t *testing.T) {
	validSource := netip.MustParseAddrPort("198.51.100.23:51000")
	validTarget := netip.MustParseAddrPort("10.1.2.3:25565")

	tests := []struct {
		name        string
		source      netip.AddrPort
		destination netip.AddrPort
	}{
		{"zero source port", netip.AddrPortFrom(netip.MustParseAddr("198.51.100.23"), 0), validTarget},
		{"zero destination port", validSource, netip.AddrPortFrom(netip.MustParseAddr("10.1.2.3"), 0)},
		{"unspecified source", netip.MustParseAddrPort("0.0.0.0:51000"), validTarget},
		{"unspecified destination", validSource, netip.MustParseAddrPort("[::]:25565")},
		{"multicast source", netip.MustParseAddrPort("239.0.0.1:51000"), validTarget},
		{"multicast destination", validSource, netip.MustParseAddrPort("[ff02::1]:25565")},
		{"zoned source", netip.MustParseAddrPort("[fe80::1%eth0]:51000"), validTarget},
		{"missing source", netip.AddrPort{}, validTarget},
		{"missing destination", validSource, netip.AddrPort{}},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			header, err := encodeProxyV2Header(tt.source, tt.destination)
			if err == nil {
				t.Fatalf("invalid endpoints were encoded as %x", header)
			}
			if len(header) != 0 {
				t.Fatalf("invalid endpoints produced %d header bytes", len(header))
			}
			if strings.Contains(err.Error(), tt.source.Addr().String()) && tt.source.Addr().IsValid() {
				t.Fatalf("error leaks the source address: %v", err)
			}
			if strings.Contains(err.Error(), tt.destination.Addr().String()) && tt.destination.Addr().IsValid() {
				t.Fatalf("error leaks the destination address: %v", err)
			}
		})
	}
}

// proxyOutputTestConn adds a controllable remote address to the recording
// connection so the header destination can be observed without real I/O.
type proxyOutputTestConn struct {
	*bridgeRecordingConn
	remote net.Addr
}

func (c *proxyOutputTestConn) RemoteAddr() net.Addr { return c.remote }

func newProxyOutputTestConn(remote net.Addr) *proxyOutputTestConn {
	return &proxyOutputTestConn{bridgeRecordingConn: newBridgeRecordingConn(), remote: remote}
}

func proxyOutputStreamContext(peer string) context.Context {
	return observe.WithStreamContext(context.Background(), observe.StreamContext{
		ConnectionID: 7,
		StreamID:     3,
		RemoteAddr:   peer,
	})
}

func TestWriteProxyV2OutputUsesRestoredPeerAndDialedTarget(t *testing.T) {
	conn := newProxyOutputTestConn(&net.TCPAddr{IP: net.IPv4(10, 1, 2, 3), Port: 25565})
	if err := writeProxyV2Output(proxyOutputStreamContext("198.51.100.23:51000"), conn); err != nil {
		t.Fatalf("writeProxyV2Output: %v", err)
	}

	want, err := encodeProxyV2Header(
		netip.MustParseAddrPort("198.51.100.23:51000"),
		netip.MustParseAddrPort("10.1.2.3:25565"),
	)
	if err != nil {
		t.Fatalf("encodeProxyV2Header: %v", err)
	}
	if got := conn.written(); !bytes.Equal(got, want) {
		t.Fatalf("written = %x, want %x", got, want)
	}
	if deadline := conn.writeDeadline(); !deadline.IsZero() {
		t.Fatalf("write deadline was not cleared: %v", deadline)
	}
}

func TestWriteProxyV2OutputAcceptsIPv6PeerAndTarget(t *testing.T) {
	conn := newProxyOutputTestConn(&net.TCPAddr{IP: net.ParseIP("2001:db8::2"), Port: 25565})
	if err := writeProxyV2Output(proxyOutputStreamContext("[2001:db8::1]:51000"), conn); err != nil {
		t.Fatalf("writeProxyV2Output: %v", err)
	}
	frame := conn.written()
	if len(frame) != 16+proxyOutputAddrLen6 {
		t.Fatalf("frame length = %d, want %d", len(frame), 16+proxyOutputAddrLen6)
	}
	if frame[13] != proxyOutputFamilyTCP6 {
		t.Fatalf("family/protocol = %#x, want %#x", frame[13], proxyOutputFamilyTCP6)
	}
}

func TestWriteProxyV2OutputHandlesShortWrites(t *testing.T) {
	conn := &bridgeShortWriteConn{bridgeRecordingConn: newBridgeRecordingConn(), limit: 5}
	if err := writeProxyV2Output(proxyOutputStreamContext("198.51.100.23:51000"), conn); err != nil {
		t.Fatalf("writeProxyV2Output: %v", err)
	}
	want, err := encodeProxyV2Header(
		netip.MustParseAddrPort("198.51.100.23:51000"),
		netip.MustParseAddrPort("127.0.0.1:2"),
	)
	if err != nil {
		t.Fatalf("encodeProxyV2Header: %v", err)
	}
	if got := conn.written(); !bytes.Equal(got, want) {
		t.Fatalf("written = %x, want %x", got, want)
	}
}

func TestWriteProxyV2OutputRejectsUntrustedIdentity(t *testing.T) {
	tests := []struct {
		name string
		ctx  context.Context
		peer string
	}{
		{name: "no stream context", ctx: context.Background()},
		{name: "empty peer", ctx: proxyOutputStreamContext(""), peer: ""},
		{name: "hostname peer", ctx: proxyOutputStreamContext("localhost:25565"), peer: "localhost:25565"},
		{name: "missing port", ctx: proxyOutputStreamContext("127.0.0.1"), peer: "127.0.0.1"},
		{name: "zero port", ctx: proxyOutputStreamContext("127.0.0.1:0"), peer: "127.0.0.1:0"},
		{name: "high port", ctx: proxyOutputStreamContext("127.0.0.1:65536"), peer: "127.0.0.1:65536"},
		{name: "non numeric port", ctx: proxyOutputStreamContext("127.0.0.1:http"), peer: "127.0.0.1:http"},
		{name: "signed port", ctx: proxyOutputStreamContext("127.0.0.1:+80"), peer: "127.0.0.1:+80"},
		{name: "zoned ipv6 peer", ctx: proxyOutputStreamContext("[fe80::1%eth0]:25565"), peer: "[fe80::1%eth0]:25565"},
		{name: "ipv6 without brackets", ctx: proxyOutputStreamContext("::1:25565"), peer: "::1:25565"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			conn := newProxyOutputTestConn(&net.TCPAddr{IP: net.IPv4(10, 1, 2, 3), Port: 25565})
			err := writeProxyV2Output(tt.ctx, conn)
			if err == nil {
				t.Fatal("untrusted peer identity was accepted")
			}
			if got := conn.written(); len(got) != 0 {
				t.Fatalf("wrote %d bytes for an untrusted identity", len(got))
			}
			if tt.peer != "" && strings.Contains(err.Error(), tt.peer) {
				t.Fatalf("error leaks the peer address: %v", err)
			}
			classified := classifyProxyOutputError(err)
			var coded *Error
			if !errors.As(classified, &coded) || coded.Code != CodeTarget || coded.Op != "proxy_output" {
				t.Fatalf("classification = %#v, want code %q op %q", classified, CodeTarget, "proxy_output")
			}
		})
	}
}

func TestWriteProxyV2OutputRejectsUnusableTargetAddress(t *testing.T) {
	conn := newProxyOutputTestConn(&net.UDPAddr{IP: net.IPv4(10, 1, 2, 3), Port: 25565})
	err := writeProxyV2Output(proxyOutputStreamContext("198.51.100.23:51000"), conn)
	if !errors.Is(err, errProxyOutputDestination) {
		t.Fatalf("error = %v, want errProxyOutputDestination", err)
	}
	if got := conn.written(); len(got) != 0 {
		t.Fatalf("wrote %d bytes without a usable target", len(got))
	}
}

func TestWriteProxyV2OutputRejectsNilConn(t *testing.T) {
	if err := writeProxyV2Output(proxyOutputStreamContext("198.51.100.23:51000"), nil); !errors.Is(err, errProxyOutputNilConn) {
		t.Fatalf("error = %v, want errProxyOutputNilConn", err)
	}
}

// proxyOutputSignalConn signals when the first write starts and reports a fixed
// remote address, so a blocked preface write can be cancelled without sleeping.
type proxyOutputSignalConn struct {
	net.Conn
	remote  net.Addr
	once    sync.Once
	started chan struct{}
}

func (c *proxyOutputSignalConn) RemoteAddr() net.Addr { return c.remote }

func (c *proxyOutputSignalConn) Write(p []byte) (int, error) {
	c.once.Do(func() { close(c.started) })
	return c.Conn.Write(p)
}

func TestWriteProxyV2OutputCancelUnblocksBlockedWrite(t *testing.T) {
	writer, reader := net.Pipe()
	defer writer.Close()
	defer reader.Close()
	signal := &proxyOutputSignalConn{
		Conn:    writer,
		remote:  &net.TCPAddr{IP: net.IPv4(10, 1, 2, 3), Port: 25565},
		started: make(chan struct{}),
	}

	ctx, cancel := context.WithCancel(proxyOutputStreamContext("198.51.100.23:51000"))
	defer cancel()
	errCh := make(chan error, 1)
	go func() { errCh <- writeProxyV2Output(ctx, signal) }()

	select {
	case <-signal.started:
	case <-time.After(2 * time.Second):
		t.Fatal("proxy output write never started")
	}
	cancel()
	select {
	case err := <-errCh:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("error = %v, want context.Canceled", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("canceled proxy output write did not return")
	}
}

func TestFixedOutboundProxyOutputRequiresStreamContext(t *testing.T) {
	ctx := newTestContext(t)
	echo := newEchoServer(t)
	set := newConnSet(1)
	defer set.close()

	o := &fixedOutbound{ctx: ctx, target: echo.Addr(), timeout: connDeadline, conns: set, proxyOutput: true}
	conn, err := o.TCP(serviceAddress)
	if err == nil {
		_ = conn.Close()
		t.Fatal("outbound wrote a headerless connection without a stream identity")
	}
	var coded *Error
	if !errors.As(err, &coded) || coded.Code != CodeTarget {
		t.Fatalf("error = %v, want a %q classification", err, CodeTarget)
	}
	// A failed preface must not leak the connection slot or reach the backend.
	if !set.reserve() {
		t.Fatal("failed proxy output leaked a connection slot")
	}
	<-set.limit
}

// proxyOutputTarget stands in for a Velocity proxy: it parses the PROXY
// protocol v2 header of every accepted connection and then echoes the payload.
// A connection that carries game data before the header fails to parse, so the
// echo never returns.
type proxyOutputTarget struct {
	ln      net.Listener
	headers chan []byte
}

func newProxyOutputTarget(t *testing.T) *proxyOutputTarget {
	t.Helper()

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen proxy output target: %v", err)
	}
	target := &proxyOutputTarget{ln: ln, headers: make(chan []byte, 4)}
	go target.serve()
	t.Cleanup(func() { _ = ln.Close() })
	return target
}

func (t *proxyOutputTarget) Addr() string { return t.ln.Addr().String() }

func (t *proxyOutputTarget) serve() {
	for {
		conn, err := t.ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(ioDeadline))
			header, err := readProxyOutputHeader(conn)
			if err != nil {
				t.headers <- nil
				return
			}
			t.headers <- header
			buf := make([]byte, 32*1024)
			for {
				n, err := conn.Read(buf)
				if n > 0 {
					if _, werr := conn.Write(buf[:n]); werr != nil {
						return
					}
				}
				if err != nil {
					return
				}
			}
		}()
	}
}

// readProxyOutputHeader consumes exactly one PROXY protocol v2 TCP header.
func readProxyOutputHeader(conn net.Conn) ([]byte, error) {
	header := make([]byte, 16)
	if _, err := io.ReadFull(conn, header); err != nil {
		return nil, err
	}
	if !bytes.Equal(header[:12], proxyPacketV2Signature) {
		return nil, errors.New("proxy output target: bad signature")
	}
	if header[12] != proxyOutputVersionCommand {
		return nil, errors.New("proxy output target: unexpected version or command")
	}
	length := int(binary.BigEndian.Uint16(header[14:16]))
	if length != proxyOutputAddrLen4 && length != proxyOutputAddrLen6 {
		return nil, errors.New("proxy output target: unexpected address length")
	}
	body := make([]byte, length)
	if _, err := io.ReadFull(conn, body); err != nil {
		return nil, err
	}
	return append(header, body...), nil
}

func startProxyOutputServer(t *testing.T, ctx context.Context, target string, id *testIdentity, enabled bool) *Endpoint {
	t.Helper()

	e, err := StartServer(ctx, config.Config{
		Listen:                "127.0.0.1:0",
		Target:                target,
		CertFile:              id.certFile,
		KeyFile:               id.keyFile,
		ProxyProtocolV2Output: enabled,
	})
	if err != nil {
		t.Fatalf("StartServer: %v", err)
	}
	t.Cleanup(func() { _ = e.Close() })
	return e
}

func TestServerProxyProtocolV2OutputToFixedTarget(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")
	target := newProxyOutputTarget(t)
	s := startProxyOutputServer(t, ctx, target.Addr(), id, true)
	c := startClient(t, ctx, s.Addr().String(), id.certFile, "", "")

	conn := dialLocal(t, c)
	payload := []byte("velocity session payload")
	assertEcho(t, conn, payload)

	var header []byte
	select {
	case header = <-target.headers:
	case <-time.After(ioDeadline):
		t.Fatal("target never received a header")
	}
	if header == nil {
		t.Fatal("target did not receive a valid PROXY protocol v2 header")
	}
	if header[13] != proxyOutputFamilyTCP4 {
		t.Fatalf("family/protocol = %#x, want %#x", header[13], proxyOutputFamilyTCP4)
	}

	sourceIP := netip.AddrFrom4([4]byte{header[16], header[17], header[18], header[19]})
	sourcePort := binary.BigEndian.Uint16(header[24:26])
	destinationIP := netip.AddrFrom4([4]byte{header[20], header[21], header[22], header[23]})
	destinationPort := binary.BigEndian.Uint16(header[26:28])

	if !sourceIP.IsLoopback() {
		t.Fatalf("header source = %s, want the restored loopback peer", sourceIP)
	}
	if sourcePort == 0 {
		t.Fatal("header source port = 0")
	}
	targetAddr := netip.MustParseAddrPort(target.Addr())
	if got := netip.AddrPortFrom(destinationIP, destinationPort); got != targetAddr {
		t.Fatalf("header destination = %s, want the fixed target %s", got, targetAddr)
	}
}

func TestServerProxyProtocolV2OutputDisabledByDefault(t *testing.T) {
	ctx := newTestContext(t)
	id := newTestIdentity(t, "localhost")

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen recording target: %v", err)
	}
	defer ln.Close()
	first := make(chan []byte, 1)
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(ioDeadline))
		buf := make([]byte, 32*1024)
		n, err := conn.Read(buf)
		if n > 0 {
			first <- append([]byte(nil), buf[:n]...)
			_, _ = conn.Write(buf[:n])
		} else if err != nil {
			first <- nil
			return
		}
		for {
			n, err := conn.Read(buf)
			if n > 0 {
				if _, werr := conn.Write(buf[:n]); werr != nil {
					return
				}
			}
			if err != nil {
				return
			}
		}
	}()

	s := startProxyOutputServer(t, ctx, ln.Addr().String(), id, false)
	c := startClient(t, ctx, s.Addr().String(), id.certFile, "", "")
	conn := dialLocal(t, c)

	payload := []byte("plain session")
	assertEcho(t, conn, payload)

	var head []byte
	select {
	case head = <-first:
	case <-time.After(ioDeadline):
		t.Fatal("target never received data")
	}
	if head == nil {
		t.Fatal("target received no data")
	}
	if bytes.HasPrefix(head, proxyPacketV2Signature) {
		t.Fatalf("default configuration emitted a PROXY protocol v2 header: %x", head)
	}
	if !bytes.HasPrefix(payload, head) {
		t.Fatalf("first target bytes = %x, want a prefix of the payload", head)
	}
}
