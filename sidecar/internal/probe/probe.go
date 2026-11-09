// Package probe performs an unauthenticated, read-only HTTP/3 capability
// discovery request. It never authenticates, opens a proxy stream, dials a
// backend, reads the trust store, or sends a token.
package probe

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"sync"
	"time"

	"github.com/apernet/quic-go"
	"github.com/apernet/quic-go/http3"
)

const (
	// StateEnabled means the server declares protocol 1 with enabled=true.
	StateEnabled = "enabled"
	// StateDisabled means the server declares protocol 1 with enabled=false.
	StateDisabled = "disabled"
	// StateUndeclared means the endpoint is absent (HTTP 404).
	StateUndeclared = "undeclared"
	// StateUnknown means no usable answer was obtained. It is never proof that
	// the server is absent.
	StateUnknown = "unknown"
	// StateIncompatible means the server declared a different protocol version.
	StateIncompatible = "incompatible"

	capabilityHost = "hyperconduit"
	capabilityPath = "/capability"
	protocol       = 1

	checkTimeout           = 3 * time.Second
	maxResponseHeaderBytes = 4 << 10
	maxBodyBytes           = 1024
	maxProtocol            = 999_999_999 // matches the Java-side [1-9][0-9]{0,8}
)

// Result is the only output of a capability check. State is one of the State*
// constants above.
type Result struct {
	State string
}

// Check asks address (a literal IP:port supplied by the caller's resolver) for
// its public capability declaration. DNS names are rejected rather than
// resolved. TLS certificate verification is deliberately disabled because this
// request carries no secret and is only advisory display data; the TLS
// handshake still proves possession of the server key. The caller must not use
// this result as an authenticated server identity.
func Check(ctx context.Context, address string) Result {
	ctx, cancel := context.WithTimeout(ctx, checkTimeout)
	defer cancel()

	addrPort, err := validateAddress(address)
	if err != nil {
		return Result{State: StateUnknown}
	}
	if ctx.Err() != nil {
		return Result{State: StateUnknown}
	}

	dialer := &probeDialer{address: addrPort}
	transport := &http3.Transport{
		// InsecureSkipVerify is intentional and scoped to this disposable probe
		// transport. It does not read or write the trust store, does not carry a
		// token, and cannot authorize a proxy connection.
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true}, //nolint:gosec
		// The transport sets ALPN to h3 from this config. The custom dial below
		// connects only to the already-validated literal address.
		MaxResponseHeaderBytes: maxResponseHeaderBytes,
		DisableCompression:     true,
		Dial:                   dialer.dial,
	}
	defer func() {
		// Cancel first so a dial still in progress cannot make Close wait.
		cancel()
		_ = transport.Close()
		dialer.close()
	}()

	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		"https://"+capabilityHost+capabilityPath, nil)
	if err != nil {
		return Result{State: StateUnknown}
	}
	resp, err := client.Do(req)
	if err != nil {
		return Result{State: StateUnknown}
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return Result{State: StateUndeclared}
	}
	if resp.StatusCode != http.StatusOK {
		return Result{State: StateUnknown}
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, maxBodyBytes+1))
	if err != nil || len(body) > maxBodyBytes {
		return Result{State: StateUnknown}
	}
	declared, enabled, err := parseCapability(body)
	if err != nil {
		return Result{State: StateUnknown}
	}
	if declared != protocol {
		return Result{State: StateIncompatible}
	}
	if enabled {
		return Result{State: StateEnabled}
	}
	return Result{State: StateDisabled}
}

// probeDialer owns the UDP sockets and QUIC connections created for one Check.
// The http3 transport may drop a failed client from its cache without closing
// it, so close explicitly to guarantee no socket outlives the check.
type probeDialer struct {
	address netip.AddrPort

	mu      sync.Mutex
	closed  bool
	conns   []*quic.Conn
	sockets []*net.UDPConn
}

func (d *probeDialer) dial(ctx context.Context, _ string, tlsCfg *tls.Config, cfg *quic.Config) (*quic.Conn, error) {
	// The address is already a parsed literal; constructing UDPAddr directly
	// guarantees this path cannot resolve a hostname.
	udpAddr := net.UDPAddrFromAddrPort(d.address)
	network := "udp4"
	bind := &net.UDPAddr{IP: net.IPv4zero}
	if udpAddr.IP.To4() == nil {
		network = "udp6"
		bind = &net.UDPAddr{IP: net.IPv6unspecified}
	}
	socket, err := net.ListenUDP(network, bind)
	if err != nil {
		return nil, err
	}
	conn, err := quic.DialEarly(ctx, socket, udpAddr, tlsCfg, cfg)
	if err != nil {
		_ = socket.Close()
		return nil, err
	}
	d.mu.Lock()
	if d.closed {
		d.mu.Unlock()
		_ = conn.CloseWithError(0, "")
		_ = socket.Close()
		return nil, context.Canceled
	}
	d.conns = append(d.conns, conn)
	d.sockets = append(d.sockets, socket)
	d.mu.Unlock()
	return conn, nil
}

func (d *probeDialer) close() {
	d.mu.Lock()
	d.closed = true
	conns := d.conns
	sockets := d.sockets
	d.conns = nil
	d.sockets = nil
	d.mu.Unlock()
	for _, conn := range conns {
		_ = conn.CloseWithError(0, "")
	}
	for _, socket := range sockets {
		_ = socket.Close()
	}
}

// validateAddress accepts only a literal IP and a numeric port. The main Java
// resolver owns DNS; this process must never perform its own lookup.
func validateAddress(address string) (netip.AddrPort, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil || host == "" {
		return netip.AddrPort{}, errors.New("probe: address must be ip:port")
	}
	addr, err := netip.ParseAddr(host)
	if err != nil {
		return netip.AddrPort{}, errors.New("probe: address must be a literal IP")
	}
	if addr.IsUnspecified() {
		return netip.AddrPort{}, errors.New("probe: address must not be unspecified")
	}
	p, err := strconv.Atoi(port)
	if err != nil || p < 1 || p > 65535 {
		return netip.AddrPort{}, errors.New("probe: invalid port")
	}
	return netip.AddrPortFrom(addr, uint16(p)), nil
}

// parseCapability enforces exactly two fields, no duplicate keys, no trailing
// data, a positive integer protocol, and a boolean enabled value.
func parseCapability(data []byte) (protocolVersion int, enabled bool, err error) {
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()

	tok, err := dec.Token()
	if err != nil {
		return 0, false, err
	}
	if delim, ok := tok.(json.Delim); !ok || delim != '{' {
		return 0, false, errors.New("probe: capability is not an object")
	}

	seen := make(map[string]struct{}, 2)
	var haveProtocol, haveEnabled bool
	for dec.More() {
		keyToken, err := dec.Token()
		if err != nil {
			return 0, false, err
		}
		key, ok := keyToken.(string)
		if !ok {
			return 0, false, errors.New("probe: invalid capability key")
		}
		if _, duplicate := seen[key]; duplicate {
			return 0, false, errors.New("probe: duplicate capability key")
		}
		seen[key] = struct{}{}

		var raw json.RawMessage
		if err := dec.Decode(&raw); err != nil {
			return 0, false, err
		}
		switch key {
		case "protocol":
			var value *int
			if err := json.Unmarshal(raw, &value); err != nil || value == nil {
				return 0, false, errors.New("probe: invalid protocol")
			}
			protocolVersion = *value
			haveProtocol = true
		case "enabled":
			var value *bool
			if err := json.Unmarshal(raw, &value); err != nil || value == nil {
				return 0, false, errors.New("probe: invalid enabled")
			}
			enabled = *value
			haveEnabled = true
		default:
			return 0, false, errors.New("probe: unknown capability field")
		}
	}

	tok, err = dec.Token()
	if err != nil {
		return 0, false, err
	}
	if delim, ok := tok.(json.Delim); !ok || delim != '}' {
		return 0, false, errors.New("probe: invalid capability object")
	}
	if _, err := dec.Token(); err != io.EOF {
		return 0, false, errors.New("probe: trailing capability data")
	}
	if !haveProtocol || !haveEnabled {
		return 0, false, errors.New("probe: missing capability field")
	}
	if protocolVersion < 1 || protocolVersion > maxProtocol {
		return 0, false, errors.New("probe: protocol out of range")
	}
	return protocolVersion, enabled, nil
}
