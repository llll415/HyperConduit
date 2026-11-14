package tunnel

import (
	"context"
	"encoding/binary"
	"errors"
	"net"
	"net/netip"
	"strconv"

	"github.com/apernet/hysteria/core/v2/observe"
)

// PROXY protocol v2 header fields used for the server-to-target preface. Only
// the PROXY command over TCP is ever emitted: LOCAL and UDP are meaningless for
// a forwarded Minecraft session.
const (
	// proxyOutputVersionCommand is version 2 (high nibble) with command PROXY
	// (low nibble), the standard 0x21 byte.
	proxyOutputVersionCommand = 0x21
	// proxyOutputFamilyTCP4 is AF_INET over STREAM; TCP6 is AF_INET6 over
	// STREAM.
	proxyOutputFamilyTCP4 = 0x11
	proxyOutputFamilyTCP6 = 0x21
	// Address block sizes: source address, destination address, source port,
	// destination port. No TLVs are emitted.
	proxyOutputAddrLen4 = 4 + 4 + 2 + 2
	proxyOutputAddrLen6 = 16 + 16 + 2 + 2
)

var (
	errProxyOutputNilConn     = errors.New("proxy protocol output: nil connection")
	errProxyOutputIdentity    = errors.New("proxy protocol output: stream identity unavailable")
	errProxyOutputSource      = errors.New("proxy protocol output: invalid peer address")
	errProxyOutputDestination = errors.New("proxy protocol output: invalid target address")
)

// writeProxyV2Output writes the PROXY protocol v2 header that tells the fixed
// target listener (for example a Velocity proxy) the real player address of
// this tunneled session. It must complete before any game bytes reach the
// connection; on error the caller closes the connection instead of falling back
// to a headerless one.
//
// The source is the peer the QUIC server already restored for this stream. That
// value comes from the authenticated transport, not from the request line, so a
// client can neither choose it nor address another destination. The destination
// is the connection's actual remote address, which is always the configured
// Target.
func writeProxyV2Output(ctx context.Context, conn net.Conn) error {
	if conn == nil {
		return errProxyOutputNilConn
	}
	info, ok := observe.StreamFromContext(ctx)
	if !ok {
		return errProxyOutputIdentity
	}
	source, err := proxyOutputPeer(info.RemoteAddr)
	if err != nil {
		return err
	}
	destination, ok := proxyOutputAddrPort(conn.RemoteAddr())
	if !ok {
		return errProxyOutputDestination
	}
	header, err := encodeProxyV2Header(source, destination)
	if err != nil {
		return err
	}
	return writePrefaceFrame(ctx, conn, header)
}

// classifyProxyOutputError maps a proxy output failure onto the fixed error
// codes. Address and identity problems are target failures; I/O problems keep
// their transport classification, including timeout and cancellation.
func classifyProxyOutputError(err error) error {
	var classified *Error
	if errors.As(err, &classified) && classified != nil {
		return classified
	}
	if errors.Is(err, errProxyOutputNilConn) ||
		errors.Is(err, errProxyOutputIdentity) ||
		errors.Is(err, errProxyOutputSource) ||
		errors.Is(err, errProxyOutputDestination) {
		return &Error{Code: CodeTarget, Op: "proxy_output", Err: err}
	}
	return classifyError("proxy_output", err)
}

// encodeProxyV2Header builds a complete PROXY protocol v2 header (signature,
// version/command, family/protocol, length, addresses, ports, no TLVs).
//
// Addresses of the same family are encoded as TCP4 or TCP6. A mixed pair is
// encoded as TCP6 with the IPv4 side written in its IPv4-mapped IPv6 form: the
// protocol allows only one family per header, and that is the single lossless
// representation of such a pair.
func encodeProxyV2Header(source, destination netip.AddrPort) ([]byte, error) {
	src, err := proxyOutputAddress(source, errProxyOutputSource)
	if err != nil {
		return nil, err
	}
	dst, err := proxyOutputAddress(destination, errProxyOutputDestination)
	if err != nil {
		return nil, err
	}

	header := make([]byte, 16, 16+proxyOutputAddrLen6)
	copy(header, proxyPacketV2Signature)
	header[12] = proxyOutputVersionCommand

	if src.Is4() && dst.Is4() {
		header[13] = proxyOutputFamilyTCP4
		binary.BigEndian.PutUint16(header[14:16], proxyOutputAddrLen4)
		src4, dst4 := src.As4(), dst.As4()
		header = append(header, src4[:]...)
		header = append(header, dst4[:]...)
	} else {
		header[13] = proxyOutputFamilyTCP6
		binary.BigEndian.PutUint16(header[14:16], proxyOutputAddrLen6)
		// As16 returns the IPv4-mapped form for IPv4 addresses.
		src16, dst16 := src.As16(), dst.As16()
		header = append(header, src16[:]...)
		header = append(header, dst16[:]...)
	}

	var ports [4]byte
	binary.BigEndian.PutUint16(ports[0:2], source.Port())
	binary.BigEndian.PutUint16(ports[2:4], destination.Port())
	return append(header, ports[:]...), nil
}

// proxyOutputAddress validates one endpoint and returns it in unmapped form.
// Unspecified, multicast, zoned, and port-zero endpoints are rejected because
// none of them can be a real forwarded session, and encoding them would mislead
// the downstream listener.
func proxyOutputAddress(addrPort netip.AddrPort, invalid error) (netip.Addr, error) {
	addr := addrPort.Addr()
	if !addr.IsValid() || addr.Zone() != "" || addrPort.Port() == 0 {
		return netip.Addr{}, invalid
	}
	addr = addr.Unmap()
	if addr.IsUnspecified() || addr.IsMulticast() {
		return netip.Addr{}, invalid
	}
	return addr, nil
}

// proxyOutputPeer converts the server-observed peer string into an address and
// port. Only literal IP:port forms are accepted: names are never resolved and
// IPv6 zones are rejected, so a client cannot influence the header through DNS
// or a scope identifier.
func proxyOutputPeer(peer string) (netip.AddrPort, error) {
	host, port, err := net.SplitHostPort(peer)
	if err != nil {
		return netip.AddrPort{}, errProxyOutputSource
	}
	addr, err := netip.ParseAddr(host)
	if err != nil || addr.Zone() != "" || !validLiteralPort(port) {
		return netip.AddrPort{}, errProxyOutputSource
	}
	n, err := strconv.Atoi(port)
	if err != nil {
		return netip.AddrPort{}, errProxyOutputSource
	}
	return netip.AddrPortFrom(addr, uint16(n)), nil
}

// proxyOutputAddrPort converts the dialed connection's remote address into an
// address and port. It is always the fixed target, never a client request.
func proxyOutputAddrPort(addr net.Addr) (netip.AddrPort, bool) {
	tcp, ok := addr.(*net.TCPAddr)
	if !ok || tcp == nil || tcp.Port <= 0 || tcp.Port > 65535 {
		return netip.AddrPort{}, false
	}
	ip, ok := netip.AddrFromSlice(tcp.IP)
	if !ok {
		return netip.AddrPort{}, false
	}
	return netip.AddrPortFrom(ip.Unmap(), uint16(tcp.Port)), true
}
