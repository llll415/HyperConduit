package tunnel

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"errors"
	"net"
	"sync"
	"time"

	"github.com/apernet/hysteria/core/v2/server"
)

// The protocol still carries an address, but it is only a service identifier.
// It is never resolved or dialed. Only the server administrator chooses Target.
const serviceAddress = "minecraft:0"

var errTargetDenied = errors.New("only the configured Minecraft service is available")

type tokenAuthenticator struct{ expected string }

func (a tokenAuthenticator) Authenticate(_ net.Addr, token string, _ uint64) (bool, string) {
	if a.expected == "" {
		return true, "minecraft"
	}
	expected, actual := sha256.Sum256([]byte(a.expected)), sha256.Sum256([]byte(token))
	return subtle.ConstantTimeCompare(expected[:], actual[:]) == 1, "minecraft"
}

// connSet bounds and owns established and pending backend/local connections.
type connSet struct {
	mu     sync.Mutex
	closed bool
	conns  map[*ownedConn]struct{}
	limit  chan struct{}
}

func newConnSet(max int) *connSet {
	return &connSet{conns: make(map[*ownedConn]struct{}), limit: make(chan struct{}, max)}
}

func (s *connSet) reserve() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return false
	}
	select {
	case s.limit <- struct{}{}:
		return true
	default:
		return false
	}
}

func (s *connSet) add(c net.Conn) (*ownedConn, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		_ = c.Close()
		<-s.limit
		return nil, false
	}
	owned := &ownedConn{Conn: c, owner: s}
	s.conns[owned] = struct{}{}
	return owned, true
}

func (s *connSet) close() {
	s.mu.Lock()
	s.closed = true
	conns := make([]*ownedConn, 0, len(s.conns))
	for c := range s.conns {
		conns = append(conns, c)
	}
	s.mu.Unlock()
	for _, c := range conns {
		_ = c.Close()
	}
}

type ownedConn struct {
	net.Conn
	owner *connSet
	once  sync.Once
}

func (c *ownedConn) Close() error {
	var err error
	c.once.Do(func() {
		err = c.Conn.Close()
		c.owner.mu.Lock()
		delete(c.owner.conns, c)
		c.owner.mu.Unlock()
		<-c.owner.limit
	})
	return err
}

type fixedOutbound struct {
	ctx     context.Context
	target  string
	timeout time.Duration
	conns   *connSet
}

func (o *fixedOutbound) TCP(request string) (net.Conn, error) {
	if request != serviceAddress {
		return nil, errTargetDenied
	}
	if !o.conns.reserve() {
		return nil, errors.New("Minecraft connection limit reached")
	}
	d := net.Dialer{Timeout: o.timeout, KeepAlive: 30 * time.Second}
	c, err := d.DialContext(o.ctx, "tcp", o.target)
	if err != nil {
		<-o.conns.limit
		// Do not disclose private backend addresses in protocol responses.
		return nil, errors.New("Minecraft backend unavailable")
	}
	owned, ok := o.conns.add(c)
	if !ok {
		return nil, net.ErrClosed
	}
	return owned, nil
}

func (*fixedOutbound) UDP(string) (server.UDPConn, error) { return nil, errTargetDenied }
func (*fixedOutbound) CheckUDP(string) error              { return errTargetDenied }
