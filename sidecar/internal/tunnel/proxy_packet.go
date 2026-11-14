package tunnel

import (
	"bytes"
	"encoding/binary"
	"errors"
	"net"
	"net/netip"
	"strings"
	"sync"
	"time"
)

const (
	proxyPacketTTL             = 2 * time.Minute
	proxyPacketSweepInterval   = 30 * time.Second
	proxyPacketReadBufferSize  = 64 << 10
	proxyPacketMaxHeaderSize   = 4 << 10
	proxyPacketMinRoutes       = 64
	proxyPacketMaxRoutes       = 16384
	proxyPacketMaxSources      = 128
	proxyPacketReasonUntrusted = "untrusted_source"
	proxyPacketReasonMissing   = "missing_header"
	proxyPacketReasonTruncated = "truncated_header"
	proxyPacketReasonInvalid   = "invalid_header"
	proxyPacketReasonProtocol  = "unsupported_protocol"
	proxyPacketReasonAddress   = "invalid_address"
	proxyPacketReasonLimit     = "route_limit"
	proxyPacketReasonConflict  = "route_conflict"
	proxyPacketStatusClosed    = "closed"
)

var (
	proxyPacketV2Signature = []byte{
		0x0d, 0x0a, 0x0d, 0x0a, 0x00, 0x0d, 0x0a, 0x51, 0x55, 0x49, 0x54, 0x0a,
	}
	proxyPacketV1Signature = []byte("PROXY")

	errProxyPacketNoRoute = errors.New("tunnel: no route for proxy packet client")
)

type proxyRoute struct {
	client    *net.UDPAddr
	clientKey string
	peer      net.Addr
	peerKey   string
	lastSeen  time.Time
}

// proxyPacketConn consumes PROXY protocol v2 headers from trusted peers and
// presents the declared client address to its caller. FRP v0.67 and later send
// the header only on the first datagram of a flow, so a datagram without a
// header is accepted only when its real peer already has an active route.
// Older FRP versions send a header on every datagram; those headers refresh the
// same route instead of creating a second one.
type proxyPacketConn struct {
	pc      net.PacketConn
	trusted []netip.Prefix
	ttl     time.Duration
	now     func() time.Time

	maxRoutes int

	readMu sync.Mutex
	mu     sync.Mutex
	closed bool

	byClient  map[string]*proxyRoute
	byPeer    map[string]*proxyRoute
	nextSweep time.Time

	readBuf []byte
	log     proxyLogLimiter
}

var _ net.PacketConn = (*proxyPacketConn)(nil)

// newProxyPacketConn wraps pc with a bounded PROXY protocol v2 client-address
// mapping. sources accepts literal IP addresses and CIDR ranges only; an empty
// or invalid list is rejected rather than silently trusting nobody.
func newProxyPacketConn(pc net.PacketConn, sources []string, maxConnections int) (net.PacketConn, error) {
	if pc == nil {
		return nil, errors.New("tunnel: proxy packet conn requires a packet conn")
	}
	trusted, err := parseProxyPacketSources(sources)
	if err != nil {
		return nil, err
	}
	if len(trusted) == 0 {
		return nil, errors.New("tunnel: proxy packet conn requires trusted sources")
	}
	return &proxyPacketConn{
		pc:        pc,
		trusted:   trusted,
		ttl:       proxyPacketTTL,
		now:       time.Now,
		maxRoutes: proxyPacketRouteLimit(maxConnections),
		byClient:  make(map[string]*proxyRoute),
		byPeer:    make(map[string]*proxyRoute),
		readBuf:   make([]byte, proxyPacketReadBufferSize),
	}, nil
}

func proxyPacketRouteLimit(maxConnections int) int {
	if maxConnections <= 0 {
		return proxyPacketMinRoutes
	}
	if maxConnections > proxyPacketMaxRoutes/4 {
		return proxyPacketMaxRoutes
	}
	limit := maxConnections * 4
	if limit < proxyPacketMinRoutes {
		return proxyPacketMinRoutes
	}
	if limit > proxyPacketMaxRoutes {
		return proxyPacketMaxRoutes
	}
	return limit
}

func parseProxyPacketSources(sources []string) ([]netip.Prefix, error) {
	if len(sources) == 0 {
		return nil, nil
	}
	if len(sources) > proxyPacketMaxSources {
		return nil, errors.New("tunnel: too many trusted proxy sources")
	}
	trusted := make([]netip.Prefix, 0, len(sources))
	for _, raw := range sources {
		if raw == "" || strings.TrimSpace(raw) != raw {
			return nil, errors.New("tunnel: trusted proxy source must be a literal IP or CIDR")
		}
		var prefix netip.Prefix
		if strings.Contains(raw, "/") {
			parsed, err := netip.ParsePrefix(raw)
			if err != nil {
				return nil, errors.New("tunnel: invalid trusted proxy CIDR")
			}
			// The canonical rule is mask first, then validate. This rejects
			// /0 (including non-zero host forms such as 1.2.3.4/0) and any
			// prefix whose masked address is unspecified, while keeping the
			// canonical output stable across repeated Normalize calls.
			prefix = parsed.Masked()
			if err := validateProxyPacketSourceAddr(prefix.Addr()); err != nil {
				return nil, err
			}
		} else {
			addr, err := netip.ParseAddr(raw)
			if err != nil {
				return nil, errors.New("tunnel: invalid trusted proxy address")
			}
			if err := validateProxyPacketSourceAddr(addr); err != nil {
				return nil, err
			}
			prefix = netip.PrefixFrom(addr, addr.BitLen())
		}
		trusted = append(trusted, prefix)
	}
	return trusted, nil
}

func validateProxyPacketSourceAddr(addr netip.Addr) error {
	if addr.Zone() != "" || addr.IsUnspecified() || addr.Is4In6() {
		return errors.New("tunnel: trusted proxy source must be a concrete IP or CIDR")
	}
	return nil
}

func (c *proxyPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	// net.PacketConn permits concurrent readers. Serialize them so the shared
	// 64 KiB read buffer can never be reused while another reader parses it.
	c.readMu.Lock()
	defer c.readMu.Unlock()

	for {
		c.mu.Lock()
		closed := c.closed
		c.mu.Unlock()
		if closed {
			return 0, nil, net.ErrClosed
		}

		n, peer, err := c.pc.ReadFrom(c.readBuf)
		if err != nil {
			return 0, nil, err
		}
		if n < 0 {
			n = 0
		}
		if n > len(c.readBuf) {
			n = len(c.readBuf)
		}
		client, payload := c.acceptDatagram(c.readBuf[:n], peer, c.currentTime())
		if client == nil {
			continue
		}
		// The caller must never be able to mutate an address stored in the
		// route table through the returned pointer.
		return copy(p, payload), cloneProxyPacketUDPAddr(client), nil
	}
}

func (c *proxyPacketConn) acceptDatagram(data []byte, peer net.Addr, now time.Time) (*net.UDPAddr, []byte) {
	client, payload, present, reason := parseProxyPacketHeader(data)
	peerString := proxyPacketAddrString(peer)

	if !c.isTrusted(peer) {
		c.log.rejected(proxyPacketReasonUntrusted, peerString, proxyPacketClientString(client), len(data))
		return nil, nil
	}

	if present {
		if reason != "" {
			c.log.rejected(reason, peerString, proxyPacketClientString(client), len(data))
			return nil, nil
		}
		if client == nil {
			c.log.rejected(proxyPacketReasonInvalid, peerString, "", len(data))
			return nil, nil
		}
		c.mu.Lock()
		if c.closed {
			c.mu.Unlock()
			return nil, nil
		}
		c.expireLocked(now)
		status := c.registerLocked(client, peer, now)
		c.mu.Unlock()
		if status == proxyPacketStatusClosed {
			return nil, nil
		}
		if status != "" {
			c.log.rejected(status, peerString, proxyPacketClientString(client), len(data))
			return nil, nil
		}
		return client, payload
	}

	// FRP v0.67+ sends only the first datagram of a flow with a header. A bare
	// datagram is accepted only when this real peer already owns a live route.
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil, nil
	}
	c.expireLocked(now)
	route, ok := c.lookupPeerLocked(peer, now)
	c.mu.Unlock()
	if !ok {
		c.log.rejected(proxyPacketReasonMissing, peerString, "", len(data))
		return nil, nil
	}
	return route.client, data
}

func (c *proxyPacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	now := c.currentTime()
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return 0, net.ErrClosed
	}
	c.expireLocked(now)
	key := proxyPacketAddrKey(addr)
	route, ok := c.byClient[key]
	if !ok {
		c.mu.Unlock()
		return 0, errProxyPacketNoRoute
	}
	if c.expired(route, now) {
		c.deleteLocked(route)
		c.mu.Unlock()
		return 0, errProxyPacketNoRoute
	}
	route.lastSeen = now
	peer := route.peer
	c.mu.Unlock()

	// Replies are always bare. The caller's address is a synthetic player
	// address, never a real destination on the wire.
	return c.pc.WriteTo(p, peer)
}

func (c *proxyPacketConn) Close() error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	c.byClient = make(map[string]*proxyRoute)
	c.byPeer = make(map[string]*proxyRoute)
	c.mu.Unlock()
	return c.pc.Close()
}

func (c *proxyPacketConn) LocalAddr() net.Addr                { return c.pc.LocalAddr() }
func (c *proxyPacketConn) SetDeadline(t time.Time) error      { return c.pc.SetDeadline(t) }
func (c *proxyPacketConn) SetReadDeadline(t time.Time) error  { return c.pc.SetReadDeadline(t) }
func (c *proxyPacketConn) SetWriteDeadline(t time.Time) error { return c.pc.SetWriteDeadline(t) }

func (c *proxyPacketConn) currentTime() time.Time {
	if c.now != nil {
		return c.now()
	}
	return time.Now()
}

func (c *proxyPacketConn) isTrusted(addr net.Addr) bool {
	ip, ok := proxyPacketAddrIP(addr)
	if !ok {
		return false
	}
	for _, prefix := range c.trusted {
		if prefix.Contains(ip) {
			return true
		}
	}
	return false
}

func (c *proxyPacketConn) registerLocked(client *net.UDPAddr, peer net.Addr, now time.Time) string {
	if c.closed {
		return proxyPacketStatusClosed
	}
	clientKey := proxyPacketUDPAddrKey(client)
	peerKey := proxyPacketAddrKey(peer)

	if route, ok := c.byClient[clientKey]; ok {
		if c.expired(route, now) {
			c.deleteLocked(route)
		} else {
			if route.peerKey != peerKey {
				return proxyPacketReasonConflict
			}
			route.lastSeen = now
			return ""
		}
	}
	if route, ok := c.byPeer[peerKey]; ok {
		if c.expired(route, now) {
			c.deleteLocked(route)
		} else {
			if route.clientKey != clientKey {
				return proxyPacketReasonConflict
			}
			route.lastSeen = now
			return ""
		}
	}
	if len(c.byClient) >= c.maxRoutes {
		return proxyPacketReasonLimit
	}

	route := &proxyRoute{
		client:    cloneProxyPacketUDPAddr(client),
		clientKey: clientKey,
		peer:      peer,
		peerKey:   peerKey,
		lastSeen:  now,
	}
	c.byClient[clientKey] = route
	c.byPeer[peerKey] = route
	return ""
}

func (c *proxyPacketConn) lookupPeerLocked(peer net.Addr, now time.Time) (*proxyRoute, bool) {
	route, ok := c.byPeer[proxyPacketAddrKey(peer)]
	if !ok {
		return nil, false
	}
	if c.expired(route, now) {
		c.deleteLocked(route)
		return nil, false
	}
	route.lastSeen = now
	return route, true
}

func (c *proxyPacketConn) expired(route *proxyRoute, now time.Time) bool {
	return now.Sub(route.lastSeen) > c.ttl
}

func (c *proxyPacketConn) deleteLocked(route *proxyRoute) {
	delete(c.byClient, route.clientKey)
	if c.byPeer[route.peerKey] == route {
		delete(c.byPeer, route.peerKey)
	}
}

func (c *proxyPacketConn) expireLocked(now time.Time) {
	if !c.nextSweep.IsZero() && now.Before(c.nextSweep) {
		return
	}
	c.nextSweep = now.Add(proxyPacketSweepInterval)
	for _, route := range c.byClient {
		if c.expired(route, now) {
			c.deleteLocked(route)
		}
	}
}

func parseProxyPacketHeader(data []byte) (*net.UDPAddr, []byte, bool, string) {
	if len(data) == 0 {
		return nil, nil, false, ""
	}
	if len(data) < len(proxyPacketV2Signature) {
		if bytes.HasPrefix(proxyPacketV2Signature, data) {
			return nil, nil, true, proxyPacketReasonTruncated
		}
		if bytes.HasPrefix(data, proxyPacketV1Signature) {
			return nil, nil, true, proxyPacketReasonProtocol
		}
		return nil, nil, false, ""
	}
	if bytes.HasPrefix(data, proxyPacketV2Signature) {
		return parseProxyPacketV2(data)
	}
	if bytes.HasPrefix(data, proxyPacketV1Signature) {
		return nil, nil, true, proxyPacketReasonProtocol
	}
	return nil, nil, false, ""
}

func parseProxyPacketV2(data []byte) (*net.UDPAddr, []byte, bool, string) {
	const present = true
	if len(data) < 16 {
		return nil, nil, present, proxyPacketReasonTruncated
	}
	versionCommand := data[12]
	if versionCommand>>4 != 2 {
		return nil, nil, present, proxyPacketReasonProtocol
	}
	if versionCommand&0x0f != 0x01 {
		// LOCAL and unassigned commands are not accepted.
		return nil, nil, present, proxyPacketReasonProtocol
	}

	familyProtocol := data[13]
	var addressLen int
	switch familyProtocol {
	case 0x12: // UDP over IPv4
		addressLen = 12
	case 0x22: // UDP over IPv6
		addressLen = 36
	default:
		return nil, nil, present, proxyPacketReasonProtocol
	}

	length := int(binary.BigEndian.Uint16(data[14:16]))
	if length < addressLen || length > proxyPacketMaxHeaderSize {
		return nil, nil, present, proxyPacketReasonInvalid
	}
	total := 16 + length
	if total > len(data) {
		return nil, nil, present, proxyPacketReasonTruncated
	}

	var source, destination *net.UDPAddr
	var ok bool
	if familyProtocol == 0x12 {
		source, ok = proxyPacketUDPAddr(
			netip.AddrFrom4([4]byte{data[16], data[17], data[18], data[19]}),
			binary.BigEndian.Uint16(data[24:26]),
		)
		if !ok {
			return nil, nil, present, proxyPacketReasonAddress
		}
		destination, ok = proxyPacketUDPAddr(
			netip.AddrFrom4([4]byte{data[20], data[21], data[22], data[23]}),
			binary.BigEndian.Uint16(data[26:28]),
		)
	} else {
		var sourceIP, destinationIP [16]byte
		copy(sourceIP[:], data[16:32])
		copy(destinationIP[:], data[32:48])
		source, ok = proxyPacketUDPAddr(
			netip.AddrFrom16(sourceIP),
			binary.BigEndian.Uint16(data[48:50]),
		)
		if !ok {
			return nil, nil, present, proxyPacketReasonAddress
		}
		destination, ok = proxyPacketUDPAddr(
			netip.AddrFrom16(destinationIP),
			binary.BigEndian.Uint16(data[50:52]),
		)
	}
	if !ok {
		return nil, nil, present, proxyPacketReasonAddress
	}
	_ = destination

	for offset := 16 + addressLen; offset < total; {
		if total-offset < 3 {
			return source, nil, present, proxyPacketReasonInvalid
		}
		valueLen := int(binary.BigEndian.Uint16(data[offset+1 : offset+3]))
		if valueLen > total-(offset+3) {
			return source, nil, present, proxyPacketReasonInvalid
		}
		offset += 3 + valueLen
	}

	return source, data[total:], present, ""
}

func proxyPacketUDPAddr(ip netip.Addr, port uint16) (*net.UDPAddr, bool) {
	if !ip.IsValid() {
		return nil, false
	}
	ip = ip.Unmap()
	if ip.IsUnspecified() || ip.IsMulticast() || port == 0 {
		return nil, false
	}
	return &net.UDPAddr{IP: net.IP(ip.AsSlice()), Port: int(port)}, true
}

func proxyPacketAddrIP(addr net.Addr) (netip.Addr, bool) {
	switch value := addr.(type) {
	case *net.UDPAddr:
		ip, ok := netip.AddrFromSlice(value.IP)
		if !ok {
			return netip.Addr{}, false
		}
		return ip.Unmap(), true
	case *net.TCPAddr:
		ip, ok := netip.AddrFromSlice(value.IP)
		if !ok {
			return netip.Addr{}, false
		}
		return ip.Unmap(), true
	default:
		return netip.Addr{}, false
	}
}

func proxyPacketAddrKey(addr net.Addr) string {
	if addr == nil {
		return ""
	}
	if udp, ok := addr.(*net.UDPAddr); ok {
		return proxyPacketUDPAddrKey(udp)
	}
	return addr.String()
}

func proxyPacketUDPAddrKey(addr *net.UDPAddr) string {
	if addr == nil {
		return ""
	}
	ip, ok := netip.AddrFromSlice(addr.IP)
	if !ok {
		return addr.String()
	}
	return netip.AddrPortFrom(ip.Unmap(), uint16(addr.Port)).String()
}

func proxyPacketAddrString(addr net.Addr) string {
	if addr == nil {
		return ""
	}
	return addr.String()
}

func proxyPacketClientString(client *net.UDPAddr) string {
	if client == nil {
		return ""
	}
	return client.String()
}

func cloneProxyPacketUDPAddr(addr *net.UDPAddr) *net.UDPAddr {
	if addr == nil {
		return nil
	}
	return &net.UDPAddr{
		IP:   append(net.IP(nil), addr.IP...),
		Port: addr.Port,
		Zone: addr.Zone,
	}
}
