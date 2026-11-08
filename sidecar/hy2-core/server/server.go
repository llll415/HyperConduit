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
	tlsConfig := convertToStdTLSConfig(config)
	quicConfig := &quic.Config{
		InitialStreamReceiveWindow:     config.QUICConfig.InitialStreamReceiveWindow,
		MaxStreamReceiveWindow:         config.QUICConfig.MaxStreamReceiveWindow,
		InitialConnectionReceiveWindow: config.QUICConfig.InitialConnectionReceiveWindow,
		MaxConnectionReceiveWindow:     config.QUICConfig.MaxConnectionReceiveWindow,
		MaxIdleTimeout:                 config.QUICConfig.MaxIdleTimeout,
		MaxIncomingStreams:             config.QUICConfig.MaxIncomingStreams,
		DisablePathMTUDiscovery:        config.QUICConfig.DisablePathMTUDiscovery,
		EnableDatagrams:                true,
		MaxDatagramFrameSize:           protocol.MaxDatagramFrameSize,
		AssumePeerMaxDatagramFrameSize: protocol.MaxDatagramFrameSize,
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
	authMutex     sync.Mutex
	authID        string
	connID        uint32 // a random id for dump streams
	authTimer     *time.Timer
	streams       sync.WaitGroup
	streamMutex   sync.Mutex
	closing       bool
	observation   *observe.Connection

	udpSM *udpSessionManager // Only set after authentication
}

func newH3sHandler(config *Config, conn *quic.Conn) *h3sHandler {
	return &h3sHandler{
		config: config,
		conn:   conn,
		connID: rand.Uint32(),
	}
}

func (h *h3sHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost && r.Host == protocol.URLHost && r.URL.Path == protocol.URLPath {
		h.authMutex.Lock()
		defer h.authMutex.Unlock()
		if h.conn.Context().Err() != nil {
			return
		}
		if h.authenticated.Load() {
			// Already authenticated
			protocol.AuthResponseToHeader(w.Header(), protocol.AuthResponse{
				UDPEnabled: !h.config.DisableUDP,
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
				UDPEnabled: !h.config.DisableUDP,
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
			// Initialize UDP session manager (if UDP is enabled)
			// We use sync.Once to make sure that only one goroutine is started,
			// as ServeHTTP may be called by multiple goroutines simultaneously
			if !h.config.DisableUDP {
				go func() {
					sm := newUDPSessionManager(
						&udpIOImpl{h.conn, id, h.config.TrafficLogger, h.config.RequestHook, h.config.Outbound},
						&udpEventLoggerImpl{h.conn, id, h.config.EventLogger},
						h.config.UDPIdleTimeout,
					)
					h.udpSM = sm
					go sm.Run()
				}()
			}
		} else {
			// Auth failed, pretend to be a normal HTTP server
			h.masqHandler(w, r)
		}
	} else {
		// Not an auth request, pretend to be a normal HTTP server
		h.masqHandler(w, r)
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
	// Call the hook if set
	var putback []byte
	var hooked bool
	if h.config.RequestHook != nil {
		hooked = h.config.RequestHook.Check(false, reqAddr)
		// When the hook is enabled, the server should always accept a connection
		// so that the client will send whatever request the hook wants to see.
		// This is essentially a server-side fast-open.
		if hooked {
			streamStats.State.Store(StreamStateHooking)
			_ = protocol.WriteTCPResponse(stream, true, "RequestHook enabled")
			putback, err = h.config.RequestHook.TCP(stream, &reqAddr)
			if err != nil {
				_ = stream.Close()
				return
			}
			streamStats.setHookedReqAddr(reqAddr)
		}
	}
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
		if !hooked {
			_ = protocol.WriteTCPResponse(stream, false, err.Error())
		}
		_ = stream.Close()
		// Log the error
		if h.config.EventLogger != nil {
			h.config.EventLogger.TCPError(h.conn.RemoteAddr(), h.authID, reqAddr, err)
		}
		return
	}
	defer tConn.Close()
	if !hooked {
		if err := protocol.WriteTCPResponse(stream, true, "Connected"); err != nil {
			return
		}
	}
	_ = stream.SetDeadline(time.Time{})
	streamStats.State.Store(StreamStateEstablished)
	// Put back the data if the hook requested
	if len(putback) > 0 {
		n, _ := tConn.Write(putback)
		streamStats.Tx.Add(uint64(n))
	}
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

func (h *h3sHandler) masqHandler(w http.ResponseWriter, r *http.Request) {
	if h.config.MasqHandler != nil {
		h.config.MasqHandler.ServeHTTP(w, r)
	} else {
		// Return 404 for everything
		http.NotFound(w, r)
	}
}

// udpIOImpl is the IO implementation for udpSessionManager with TrafficLogger support
type udpIOImpl struct {
	Conn          *quic.Conn
	AuthID        string
	TrafficLogger TrafficLogger
	RequestHook   RequestHook
	Outbound      Outbound
}

func (io *udpIOImpl) ReceiveMessage() (*protocol.UDPMessage, error) {
	for {
		msg, err := io.Conn.ReceiveDatagram(context.Background())
		if err != nil {
			// Connection error, this will stop the session manager
			return nil, err
		}
		udpMsg, err := protocol.ParseUDPMessage(msg)
		if err != nil {
			// Invalid message, this is fine - just wait for the next
			continue
		}
		if io.TrafficLogger != nil {
			ok := io.TrafficLogger.LogTraffic(io.AuthID, uint64(len(udpMsg.Data)), 0)
			if !ok {
				// TrafficLogger requested to disconnect the client
				_ = io.Conn.CloseWithError(closeErrCodeTrafficLimitReached, "")
				return nil, errDisconnect
			}
		}
		return udpMsg, nil
	}
}

func (io *udpIOImpl) SendMessage(buf []byte, msg *protocol.UDPMessage) error {
	if io.TrafficLogger != nil {
		ok := io.TrafficLogger.LogTraffic(io.AuthID, 0, uint64(len(msg.Data)))
		if !ok {
			// TrafficLogger requested to disconnect the client
			_ = io.Conn.CloseWithError(closeErrCodeTrafficLimitReached, "")
			return errDisconnect
		}
	}
	msgN := msg.Serialize(buf)
	if msgN < 0 {
		// Message larger than buffer, silent drop
		return nil
	}
	return io.Conn.SendDatagram(buf[:msgN])
}

func (io *udpIOImpl) Hook(packets [][]byte, reqAddr *string) (bool, error) {
	if io.RequestHook != nil && io.RequestHook.Check(true, *reqAddr) {
		return io.RequestHook.UDP(packets, reqAddr)
	} else {
		return true, nil
	}
}

func (io *udpIOImpl) UDP(reqAddr string) (UDPConn, error) {
	return io.Outbound.UDP(reqAddr)
}

func (io *udpIOImpl) CheckUDP(reqAddr string) error {
	return io.Outbound.CheckUDP(reqAddr)
}

type udpEventLoggerImpl struct {
	Conn        *quic.Conn
	AuthID      string
	EventLogger EventLogger
}

func (l *udpEventLoggerImpl) New(sessionID uint32, reqAddr string) {
	if l.EventLogger != nil {
		l.EventLogger.UDPRequest(l.Conn.RemoteAddr(), l.AuthID, sessionID, reqAddr)
	}
}

func (l *udpEventLoggerImpl) Close(sessionID uint32, err error) {
	if l.EventLogger != nil {
		l.EventLogger.UDPError(l.Conn.RemoteAddr(), l.AuthID, sessionID, err)
	}
}
