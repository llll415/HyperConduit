package tunnel

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"errors"
	"net"
	"sync"
	"time"

	"github.com/apernet/hysteria/core/v2/observe"
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

type statusAuthenticator struct {
	tokenAuthenticator
	status *statusStore
}

func (a statusAuthenticator) Authenticate(addr net.Addr, token string, tx uint64) (bool, string) {
	ok, id := a.tokenAuthenticator.Authenticate(addr, token, tx)
	if !ok {
		a.status.record(&Error{Code: CodeAuthentication, Op: "authenticate"})
	}
	return ok, id
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
	ctx         context.Context
	target      string
	timeout     time.Duration
	conns       *connSet
	status      *statusStore
	bridgeToken string
}

func (o *fixedOutbound) TCP(request string) (net.Conn, error) {
	return o.TCPContext(o.ctx, request)
}

func (o *fixedOutbound) TCPContext(ctx context.Context, request string) (net.Conn, error) {
	conn, err := o.dial(ctx, request)
	if err != nil && o.status != nil {
		o.status.record(err)
	}
	return conn, err
}

func (o *fixedOutbound) dial(ctx context.Context, request string) (net.Conn, error) {
	if request != serviceAddress {
		return nil, &Error{Code: CodeTarget, Op: "backend", Err: errTargetDenied}
	}
	if !o.conns.reserve() {
		return nil, &Error{Code: CodeLimit, Op: "backend", Err: errors.New("connection limit")}
	}
	// Also stop pending dials when the endpoint (not just this stream) closes.
	ctx, cancel := context.WithCancel(ctx)
	stop := context.AfterFunc(o.ctx, cancel)
	defer func() { stop(); cancel() }()
	d := net.Dialer{Timeout: o.timeout, KeepAlive: 30 * time.Second}
	c, err := d.DialContext(ctx, "tcp", o.target)
	if err != nil {
		<-o.conns.limit
		if code := classifyCode(err); code == CodeTimeout || code == CodeCanceled {
			return nil, classifyError("backend", err)
		}
		return nil, &Error{Code: CodeTarget, Op: "backend", Err: err}
	}
	owned, ok := o.conns.add(c)
	if !ok {
		return nil, net.ErrClosed
	}
	if o.bridgeToken != "" {
		info, present := observe.StreamFromContext(ctx)
		if !present {
			_ = owned.Close()
			return nil, &Error{Code: CodeTarget, Op: "bridge"}
		}
		if err := writeBridgePreface(ctx, owned, o.bridgeToken, info); err != nil {
			_ = owned.Close()
			return nil, classifyError("bridge", err)
		}
	}
	if o.status != nil {
		id := o.status.addBinding(c.LocalAddr().String(), c.RemoteAddr().String())
		if info, ok := observe.StreamFromContext(ctx); ok {
			o.status.establish(id, info)
		}
		return &boundConn{Conn: owned, closed: func() { o.status.removeBinding(id) }}, nil
	}
	return owned, nil
}

type boundConn struct {
	net.Conn
	once   sync.Once
	closed func()
}

func (c *boundConn) Close() error {
	err := c.Conn.Close()
	c.once.Do(c.closed)
	return err
}
