package server

import (
	"context"
	crand "crypto/rand"
	"crypto/tls"
	"errors"
	"io"
	"math/rand"
	"net"
	"net/http"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/apernet/quic-go"
	"github.com/apernet/quic-go/http3"
	"github.com/apernet/quic-go/quicvarint"

	coreerrors "github.com/apernet/hysteria/core/v2/errors"
	"github.com/apernet/hysteria/core/v2/internal/congestion"
	"github.com/apernet/hysteria/core/v2/internal/protocol"
	"github.com/apernet/hysteria/core/v2/internal/utils"
	"github.com/apernet/hysteria/core/v2/observe"
)

const (
	closeErrCodeOK                  = 0x100 // HTTP3 ErrCodeNoError
	closeErrCodeTrafficLimitReached = 0x107 // HTTP3 ErrCodeExcessiveLoad

	// Capability discovery uses a fixed authority so it is independent of the
	// real server address and cannot be confused with an authenticated request.
	capabilityHost = "hyperconduit"
	capabilityPath = "/capability"
)

type Server interface {
	Serve() error
	Close() error
}

func convertToStdTLSConfig(config *Config) *tls.Config {
	var clientAuth tls.ClientAuthType
	if config.TLSConfig.ClientCAs != nil {
		clientAuth = tls.RequireAndVerifyClientCert
	} else {
		clientAuth = tls.NoClientCert
	}
	return http3.ConfigureTLSConfig(&tls.Config{
		Certificates:                config.TLSConfig.Certificates,
		GetCertificate:              config.TLSConfig.GetCertificate,
		ClientCAs:                   config.TLSConfig.ClientCAs,
		ClientAuth:                  clientAuth,
		EncryptedClientHelloKeys:    config.TLSConfig.ECHKeys,
		GetEncryptedClientHelloKeys: config.TLSConfig.GetECHKeys,
	})
}

func NewServer(config *Config) (Server, error) {
	if err := config.fill(); err != nil {
		return nil, err
	}
	// The response is fixed for the lifetime of the server. Copy it so a caller
	// mutating its slice cannot race with capability requests.
	if len(config.CapabilityResponse) > 0 {
		config.CapabilityResponse = append([]byte(nil), config.CapabilityResponse...)
	}
	tlsConfig := convertToStdTLSConfig(config)
	quicConfig := &quic.Config{
		InitialStreamReceiveWindow:     config.QUICConfig.InitialStreamReceiveWindow,
		MaxStreamReceiveWindow:         config.QUICConfig.MaxStreamReceiveWindow,
		InitialConnectionReceiveWindow: config.QUICConfig.InitialConnectionReceiveWindow,
		MaxConnectionReceiveWindow:     config.QUICConfig.MaxConnectionReceiveWindow,
		MaxIdleTimeout:                 config.QUICConfig.MaxIdleTimeout,
		MaxIncomingStreams:             config.QUICConfig.MaxIncomingStreams,
		DisablePathMTUDiscovery:        config.QUICConfig.DisablePathMTUDiscovery,
		EnableDatagrams:                false,
		DisablePathManager:             true,
	}
	if config.RequestTimeout > 0 {
		quicConfig.HandshakeIdleTimeout = config.RequestTimeout
	}
	tr := &quic.Transport{
		Conn:       config.Conn,
		DisableGSO: config.QUICConfig.DisableGSO,
	}
	if config.MaxConnections > 0 {
		slots := make(chan struct{}, config.MaxConnections)
		tr.ConnContext = func(ctx context.Context, _ *quic.ClientInfo) (context.Context, error) {
			select {
			case slots <- struct{}{}:
				context.AfterFunc(ctx, func() { <-slots })
				return ctx, nil
			default:
				if config.ErrorObserver != nil {
					config.ErrorObserver(coreerrors.LimitError{})
				}
				return nil, coreerrors.LimitError{}
			}
		}
	}
	// A nil key means quic-go never sends stateless resets. Clients then have to
	// wait for the idle timeout to notice the server is gone, so keep them on
	// unless the user explicitly asks otherwise.
	if !config.QUICConfig.DisableStatelessReset {
		srk := config.StatelessResetKey
		if srk == nil {
			var k quic.StatelessResetKey
			if _, err := crand.Read(k[:]); err != nil {
				return nil, err
			}
			srk = &k
		}
		tr.StatelessResetKey = srk
	}
	listener, err := tr.Listen(tlsConfig, quicConfig)
	if err != nil {
		err = errors.Join(err, tr.Close(), config.Conn.Close())
		if config.Cleanup != nil {
			err = errors.Join(err, config.Cleanup.Close())
		}
		return nil, err
	}
	return &serverImpl{
		config:   config,
		tr:       tr,
		listener: listener,
		conns:    make(map[*quic.Conn]struct{}),
	}, nil
}

type serverImpl struct {
	config    *Config
	tr        *quic.Transport
	listener  *quic.Listener
	mu        sync.Mutex
	closing   bool
	conns     map[*quic.Conn]struct{}
	closeOnce sync.Once
	closeErr  error
}

func (s *serverImpl) Serve() error {
	var clients sync.WaitGroup
	defer clients.Wait()
	for {
		conn, err := s.listener.Accept(context.Background())
		if err != nil {
			_ = s.Close()
			return err
		}
		s.mu.Lock()
		if s.closing {
			s.mu.Unlock()
			_ = conn.CloseWithError(closeErrCodeOK, "server stopping")
			continue
		}
		s.conns[conn] = struct{}{}
		s.mu.Unlock()
		clients.Add(1)
		go func() {
			defer clients.Done()
			defer func() {
				s.mu.Lock()
				delete(s.conns, conn)
				s.mu.Unlock()
			}()
			s.handleClient(conn)
		}()
	}
}

func (s *serverImpl) Close() error {
	s.closeOnce.Do(func() { s.closeErr = s.close() })
	return s.closeErr
}

func (s *serverImpl) close() error {
	s.mu.Lock()
	s.closing = true
	conns := make([]*quic.Conn, 0, len(s.conns))
	for conn := range s.conns {
		conns = append(conns, conn)
	}
	s.mu.Unlock()
	for _, conn := range conns {
		_ = conn.CloseWithError(closeErrCodeOK, "server stopping")
	}
	err := errors.Join(s.listener.Close(), s.tr.Close(), s.config.Conn.Close())
	if s.config.Cleanup != nil {
		err = errors.Join(err, s.config.Cleanup.Close())
	}
	return err
}

func (s *serverImpl) handleClient(conn *quic.Conn) {
	handler := newH3sHandler(s.config, conn)
	if s.config.RequestTimeout > 0 {
		handler.authTimer = time.AfterFunc(s.config.RequestTimeout, func() {
			if !handler.authenticated.Load() {
				if s.config.ErrorObserver != nil {
					s.config.ErrorObserver(context.DeadlineExceeded)
				}
				_ = conn.CloseWithError(closeErrCodeOK, "authentication timed out")
			}
		})
		defer handler.authTimer.Stop()
	}
	h3s := http3.Server{
		Handler:          handler,
		StreamDispatcher: handler.ProxyStreamHijacker,
		MaxHeaderBytes:   16 * 1024,
	}
	err := h3s.ServeQUICConn(conn)
	_ = conn.CloseWithError(closeErrCodeOK, "")
	handler.streamMutex.Lock()
	handler.closing = true
	handler.streamMutex.Unlock()
	handler.streams.Wait()
	handler.authMutex.Lock()
	defer handler.authMutex.Unlock()
	if handler.observation != nil {
		handler.observation.Close()
		if observer := s.config.ConnectionObserver; observer != nil {
			observer(handler.observation, false)
		}
	}
	// If the client is authenticated, we need to log the disconnect event
	if handler.authenticated.Load() {
		if tl := s.config.TrafficLogger; tl != nil {
			tl.LogOnlineState(handler.authID, false)
		}
		if el := s.config.EventLogger; el != nil {
			el.Disconnect(conn.RemoteAddr(), handler.authID, err)
		}
	}
	_ = conn.CloseWithError(closeErrCodeOK, "")
}

type h3sHandler struct {
	config *Config
	conn   *quic.Conn

	authenticated atomic.Bool
	// capabilityServed bounds unauthenticated capability requests to one per
	// connection, so a single QUIC connection cannot be used as a free request
	// amplifier. The probe client uses a fresh connection per check.
	capabilityServed atomic.Bool
	authMutex        sync.Mutex
	authID           string
	connID           uint32 // a random id for dump streams
	authTimer        *time.Timer
	streams          sync.WaitGroup
	streamMutex      sync.Mutex
	closing          bool
	observation      *observe.Connection
}

func newH3sHandler(config *Config, conn *quic.Conn) *h3sHandler {
	return &h3sHandler{
		config: config,
		conn:   conn,
		connID: rand.Uint32(),
	}
}

func (h *h3sHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// Capability discovery is intentionally readable before authentication. It
	// only writes the fixed configured document: it never authenticates the
	// connection, reads a token/identity, opens a TCP stream, or dials a backend.
	if r.Method == http.MethodGet && r.Host == capabilityHost &&
		r.URL.Path == capabilityPath && r.URL.RawQuery == "" && !r.URL.ForceQuery {
		if !h.capabilityServed.CompareAndSwap(false, true) {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		h.handleCapability(w)
		return
	}
	if r.Method == http.MethodPost && r.Host == protocol.URLHost && r.URL.Path == protocol.URLPath {
		h.authMutex.Lock()
		defer h.authMutex.Unlock()
		if h.conn.Context().Err() != nil {
			return
		}
		if h.authenticated.Load() {
			// Already authenticated
			protocol.AuthResponseToHeader(w.Header(), protocol.AuthResponse{
				UDPEnabled: false,
				Rx:         h.config.BandwidthConfig.MaxRx,
				RxAuto:     h.config.IgnoreClientBandwidth,
			})
			w.WriteHeader(protocol.StatusAuthOK)
			return
		}
		authReq := protocol.AuthRequestFromHeader(r.Header)
		actualTx := authReq.Rx
		ok, id := h.config.Authenticator.Authenticate(h.conn.RemoteAddr(), authReq.Auth, actualTx)
		if ok {
			// Set authenticated flag
			h.authID = id
			if h.config.IgnoreClientBandwidth {
				// Ignore client bandwidth and use the configured congestion controller.
				congestion.UseConfigured(h.conn, h.config.CongestionConfig.Type, h.config.CongestionConfig.BBRProfile)
				actualTx = 0
			} else {
				// actualTx = min(serverTx, clientRx)
				if h.config.BandwidthConfig.MaxTx > 0 && actualTx > h.config.BandwidthConfig.MaxTx {
					// We have a maxTx limit and the client is asking for more than that,
					// return and use the limit instead
					actualTx = h.config.BandwidthConfig.MaxTx
				}
				if actualTx > 0 {
					congestion.UseBrutal(h.conn, actualTx, h.config.BandwidthConfig.DisableLossCompensation)
				} else {
					// Client doesn't know its own bandwidth, use the configured congestion controller.
					congestion.UseConfigured(h.conn, h.config.CongestionConfig.Type, h.config.CongestionConfig.BBRProfile)
				}
			}
			// Auth OK, send response
			protocol.AuthResponseToHeader(w.Header(), protocol.AuthResponse{
				UDPEnabled: false,
				Rx:         h.config.BandwidthConfig.MaxRx,
				RxAuto:     h.config.IgnoreClientBandwidth,
			})
			cc := observe.Congestion{Type: h.config.CongestionConfig.Type}
			if actualTx > 0 {
				cc = observe.Congestion{Type: "brutal", TxBytesPerSecond: actualTx}
			}
			h.observation = observe.New(h.conn, "server", cc)
			if observer := h.config.ConnectionObserver; observer != nil {
				observer(h.observation, true)
			}
			h.authenticated.Store(true)
			if h.authTimer != nil {
				h.authTimer.Stop()
			}
			w.WriteHeader(protocol.StatusAuthOK)
			// Call event logger
			if tl := h.config.TrafficLogger; tl != nil {
				tl.LogOnlineState(id, true)
			}
			if el := h.config.EventLogger; el != nil {
				el.Connect(h.conn.RemoteAddr(), id, actualTx)
			}
		} else {
			// Authentication failures have a fixed, non-configurable response.
			h.notFound(w, r)
		}
	} else {
		// There are no generic HTTP or masquerade routes.
		h.notFound(w, r)
	}
}

func (h *h3sHandler) ProxyStreamHijacker(ft http3.FrameType, stream *quic.Stream, err error) (bool, error) {
	if err != nil || !h.authenticated.Load() {
		return false, nil
	}

	switch ft {
	case protocol.FrameTypeTCPRequest:
		// StreamDispatcher only peeks the frame type. Consume it so ReadTCPRequest
		// starts at address length, matching pre-upgrade StreamHijacker behavior.
		if _, err := quicvarint.Read(quicvarint.NewReader(stream)); err != nil {
			return false, err
		}
		// Wraps the stream with QStream, which handles Close() properly
		qStream := &utils.QStream{Stream: stream}
		h.streamMutex.Lock()
		if h.closing {
			h.streamMutex.Unlock()
			_ = qStream.Close()
			return true, nil
		}
		h.streams.Add(1)
		h.streamMutex.Unlock()
		go func() {
			defer h.streams.Done()
			h.handleTCPRequest(qStream)
		}()
		return true, nil
	default:
		return false, nil
	}
}

func (h *h3sHandler) handleTCPRequest(stream *utils.QStream) {
	defer stream.Close()
	if h.config.RequestTimeout > 0 {
		_ = stream.SetDeadline(time.Now().Add(h.config.RequestTimeout))
	}
	trafficLogger := h.config.TrafficLogger
	streamStats := &StreamStats{
		AuthID:      h.authID,
		ConnID:      h.connID,
		InitialTime: time.Now(),
	}
	if h.observation != nil {
		streamStats.ConnectionID = h.observation.ID()
	}
	streamStats.State.Store(StreamStateInitial)
	streamStats.LastActiveTime.Store(time.Now())
	defer func() {
		streamStats.State.Store(StreamStateClosed)
	}()
	if trafficLogger != nil {
		trafficLogger.TraceStream(stream, streamStats)
		defer trafficLogger.UntraceStream(stream)
	}

	// Read request
	reqAddr, err := protocol.ReadTCPRequest(stream)
	if err != nil {
		if h.config.ErrorObserver != nil && h.conn.Context().Err() == nil && !errors.Is(err, io.EOF) && !errors.Is(err, context.Canceled) {
			h.config.ErrorObserver(err)
		}
		_ = stream.Close()
		return
	}
	streamStats.ReqAddr.Store(reqAddr)
	// Log the event
	if h.config.EventLogger != nil {
		h.config.EventLogger.TCPRequest(h.conn.RemoteAddr(), h.authID, reqAddr)
	}
	// Dial target
	streamStats.State.Store(StreamStateConnecting)
	var tConn net.Conn
	if outbound, ok := h.config.Outbound.(ContextOutbound); ok {
		ctx := observe.WithStreamContext(h.conn.Context(), observe.StreamContext{
			ConnectionID: streamStats.ConnectionID, StreamID: int64(stream.StreamID()), RemoteAddr: h.conn.RemoteAddr().String(),
		})
		ctx, cancel := context.WithCancel(ctx)
		stop := context.AfterFunc(stream.Context(), cancel)
		timeoutCancel := func() {}
		if h.config.RequestTimeout > 0 {
			ctx, timeoutCancel = context.WithTimeout(ctx, h.config.RequestTimeout)
		}
		tConn, err = outbound.TCPContext(ctx, reqAddr)
		stop()
		timeoutCancel()
		cancel()
	} else {
		tConn, err = h.config.Outbound.TCP(reqAddr)
	}
	if err != nil {
		_ = protocol.WriteTCPResponse(stream, false, "target unavailable")
		_ = stream.Close()
		// Log the error
		if h.config.EventLogger != nil {
			h.config.EventLogger.TCPError(h.conn.RemoteAddr(), h.authID, reqAddr, err)
		}
		return
	}
	defer tConn.Close()
	if err := protocol.WriteTCPResponse(stream, true, "Connected"); err != nil {
		return
	}
	_ = stream.SetDeadline(time.Time{})
	streamStats.State.Store(StreamStateEstablished)
	// Start proxying
	if trafficLogger != nil {
		err = copyTwoWayEx(h.authID, stream, tConn, trafficLogger, streamStats)
	} else {
		// Use the fast path if no traffic logger is set
		err = copyTwoWay(stream, tConn)
	}
	if h.config.EventLogger != nil {
		h.config.EventLogger.TCPError(h.conn.RemoteAddr(), h.authID, reqAddr, err)
	}
	// Cleanup
	_ = tConn.Close()
	_ = stream.Close()
	// Disconnect the client if TrafficLogger requested
	if err == errDisconnect {
		_ = h.conn.CloseWithError(closeErrCodeTrafficLimitReached, "")
	}
}

// handleCapability serves the fixed public capability document. An unset or
// empty document is reported as 404 so probes classify the endpoint as
// undeclared rather than mistaking an unrelated page for a declaration.
func (h *h3sHandler) handleCapability(w http.ResponseWriter) {
	body := h.config.CapabilityResponse
	if len(body) == 0 {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Content-Length", strconv.Itoa(len(body)))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(body)
}

// notFound is the only response for unrecognized routes and failed auth.
func (h *h3sHandler) notFound(w http.ResponseWriter, r *http.Request) {
	http.NotFound(w, r)
}
