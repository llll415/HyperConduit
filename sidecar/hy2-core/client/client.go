package client

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"net/http"
	"net/url"
	"os"
	"sync"
	"time"

	coreErrs "github.com/apernet/hysteria/core/v2/errors"
	"github.com/apernet/hysteria/core/v2/internal/congestion"
	"github.com/apernet/hysteria/core/v2/internal/protocol"
	"github.com/apernet/hysteria/core/v2/internal/utils"
	"github.com/apernet/hysteria/core/v2/observe"

	"github.com/apernet/quic-go"
	"github.com/apernet/quic-go/http3"
)

const (
	closeErrCodeOK            = 0x100 // HTTP3 ErrCodeNoError
	closeErrCodeProtocolError = 0x101 // HTTP3 ErrCodeGeneralProtocolError

	// streamErrCodeCancel is used to reset a single stream whose dial context
	// has been canceled. Resetting the stream does not affect the underlying
	// QUIC connection, so other streams remain usable.
	streamErrCodeCancel quic.StreamErrorCode = 0
)

type Client interface {
	TCP(addr string) (net.Conn, error)
	UDP() (HyUDPConn, error)
	Close() error
}

// ContextClient is implemented by clients that support dialing with a context.
// The context governs the dial operation only; once TCPContext returns a
// connection, canceling the context no longer affects that connection.
type ContextClient interface {
	Client
	TCPContext(ctx context.Context, addr string) (net.Conn, error)
	// Done is closed when the underlying QUIC connection is closed, either
	// locally or by the remote peer. It can be used by the sidecar to tear
	// down local listeners and exit instead of staying in a stale READY state.
	Done() <-chan struct{}
	ConnectionStats() observe.Snapshot
	Observation() *observe.Connection
}

type HyUDPConn interface {
	Receive() ([]byte, string, error)
	Send([]byte, string) error
	Close() error
}

type HandshakeInfo struct {
	UDPEnabled  bool
	Tx          uint64             // Local Brutal target; 0 when using BBR or Reno.
	Congestion  observe.Congestion // Local sending controller, not the remote controller.
	ServerAddr  net.Addr
	ECHAccepted bool
}

func NewClient(config *Config) (Client, *HandshakeInfo, error) {
	c, info, err := NewClientContext(context.Background(), config)
	if err != nil {
		return nil, nil, err
	}
	return c, info, nil
}

// NewClientContext creates a client and connects to the server, honoring ctx
// for the connection handshake and authentication request. The context only
// covers the connect phase; it does not control the lifetime of the returned
// client or of connections created by TCPContext.
func NewClientContext(ctx context.Context, config *Config) (ContextClient, *HandshakeInfo, error) {
	if err := ctx.Err(); err != nil {
		// Avoid creating a socket / transport for a context that is already done.
		return nil, nil, coreErrs.ConnectError{Err: err}
	}
	if err := config.verifyAndFill(); err != nil {
		return nil, nil, err
	}
	c := &clientImpl{
		config: config,
	}
	info, err := c.connect(ctx)
	if err != nil {
		return nil, nil, err
	}
	return c, info, nil
}

type clientImpl struct {
	config *Config

	pktConn     net.PacketConn
	tr          *quic.Transport
	conn        *quic.Conn
	observation *observe.Connection

	udpSM *udpSessionManager
}

var _ ContextClient = (*clientImpl)(nil)

func (c *clientImpl) connect(ctx context.Context) (*HandshakeInfo, error) {
	pktConn, err := c.config.ConnFactory.New(c.config.ServerAddr)
	if err != nil {
		return nil, err
	}
	// Convert config to TLS config & QUIC config
	tlsConfig := &tls.Config{
		ServerName:                     c.config.TLSConfig.ServerName,
		InsecureSkipVerify:             c.config.TLSConfig.InsecureSkipVerify,
		VerifyPeerCertificate:          c.config.TLSConfig.VerifyPeerCertificate,
		RootCAs:                        c.config.TLSConfig.RootCAs,
		GetClientCertificate:           c.config.TLSConfig.GetClientCertificate,
		EncryptedClientHelloConfigList: c.config.TLSConfig.ECHConfigList,
	}
	quicConfig := &quic.Config{
		InitialStreamReceiveWindow:     c.config.QUICConfig.InitialStreamReceiveWindow,
		MaxStreamReceiveWindow:         c.config.QUICConfig.MaxStreamReceiveWindow,
		InitialConnectionReceiveWindow: c.config.QUICConfig.InitialConnectionReceiveWindow,
		MaxConnectionReceiveWindow:     c.config.QUICConfig.MaxConnectionReceiveWindow,
		MaxIdleTimeout:                 c.config.QUICConfig.MaxIdleTimeout,
		KeepAlivePeriod:                c.config.QUICConfig.KeepAlivePeriod,
		DisablePathMTUDiscovery:        c.config.QUICConfig.DisablePathMTUDiscovery,
		EnableDatagrams:                true,
		MaxDatagramFrameSize:           protocol.MaxDatagramFrameSize,
		OmitMaxDatagramFrameSize:       true,
		DisablePathManager:             true,
		ChromeParrot:                   !c.config.QUICConfig.DisableChromeParrot,
	}
	tr := &quic.Transport{Conn: pktConn, DisableGSO: c.config.QUICConfig.DisableGSO}
	if !c.config.QUICConfig.DisableChromeParrot {
		// Chrome uses a zero-length source connection ID. This has to be set on the
		// Transport, since it fixes the length at which incoming packets' connection
		// IDs are parsed; leaving it default yields 4-byte IDs, visible on the wire.
		tr.ConnectionIDGenerator = quic.ZeroLengthConnectionIDGenerator{}
	}
	// Prepare RoundTripper
	// The dial goroutine may still be running when RoundTrip returns early due
	// to ctx cancellation, so access to conn must be synchronized.
	var (
		connMu sync.Mutex
		conn   *quic.Conn
	)
	getConn := func() *quic.Conn {
		connMu.Lock()
		defer connMu.Unlock()
		return conn
	}
	rt := &http3.Transport{
		TLSClientConfig: tlsConfig,
		QUICConfig:      quicConfig,
		Dial: func(ctx context.Context, _ string, tlsCfg *tls.Config, cfg *quic.Config) (*quic.Conn, error) {
			qc, err := tr.DialEarly(ctx, c.config.ServerAddr, tlsCfg, cfg)
			if err != nil {
				return nil, err
			}
			connMu.Lock()
			conn = qc
			connMu.Unlock()
			return qc, nil
		},
	}
	// rt is only used for the auth request and is deliberately never closed:
	// on success its connection is the one handed out to the caller, and on
	// failure tr.Close/pktConn.Close already tear down the transport.
	// cleanup releases everything allocated for a failed connect attempt.
	// It must never run after a successful connect, as that would tear down
	// the live QUIC connection.
	cleanup := func() {
		if qc := getConn(); qc != nil {
			_ = qc.CloseWithError(closeErrCodeProtocolError, "")
		}
		_ = tr.Close()
		_ = pktConn.Close()
	}
	// Send auth HTTP request
	req := &http.Request{
		Method: http.MethodPost,
		URL: &url.URL{
			Scheme: "https",
			Host:   protocol.URLHost,
			Path:   protocol.URLPath,
		},
		Header: make(http.Header),
	}
	protocol.AuthRequestToHeader(req.Header, protocol.AuthRequest{
		Auth: c.config.Auth,
		Rx:   c.config.BandwidthConfig.MaxRx,
	})
	req = req.WithContext(ctx)
	resp, err := rt.RoundTrip(req)
	if err != nil {
		cleanup()
		if ctxErr := ctx.Err(); ctxErr != nil {
			return nil, coreErrs.ConnectError{Err: ctxErr}
		}
		return nil, coreErrs.ConnectError{Err: err}
	}
	qc := getConn()
	if qc == nil {
		_ = resp.Body.Close()
		cleanup()
		return nil, coreErrs.ConnectError{Err: errors.New("no QUIC connection established")}
	}
	if resp.StatusCode != protocol.StatusAuthOK {
		_ = resp.Body.Close()
		cleanup()
		return nil, coreErrs.AuthError{StatusCode: resp.StatusCode}
	}
	_ = resp.Body.Close()
	// The context only covers the connect phase. If it was canceled while we
	// were finishing the handshake, discard the connection instead of handing
	// out a client the caller has already given up on.
	if ctxErr := ctx.Err(); ctxErr != nil {
		cleanup()
		return nil, coreErrs.ConnectError{Err: ctxErr}
	}
	// Auth OK
	authResp := protocol.AuthResponseFromHeader(resp.Header)
	var actualTx uint64
	if authResp.RxAuto {
		// Server asks client to use bandwidth detection,
		// ignore local bandwidth config and use the configured congestion controller.
		congestion.UseConfigured(qc, c.config.CongestionConfig.Type, c.config.CongestionConfig.BBRProfile)
	} else {
		// actualTx = min(serverRx, clientTx)
		actualTx = authResp.Rx
		if actualTx == 0 || actualTx > c.config.BandwidthConfig.MaxTx {
			// Server doesn't have a limit, or our clientTx is smaller than serverRx
			actualTx = c.config.BandwidthConfig.MaxTx
		}
		if actualTx > 0 {
			congestion.UseBrutal(qc, actualTx, c.config.BandwidthConfig.DisableLossCompensation)
		} else {
			// We don't know our own bandwidth either, use the configured congestion controller.
			congestion.UseConfigured(qc, c.config.CongestionConfig.Type, c.config.CongestionConfig.BBRProfile)
		}
	}

	c.pktConn = pktConn
	c.tr = tr
	c.conn = qc
	cc := observe.Congestion{Type: c.config.CongestionConfig.Type}
	if actualTx > 0 {
		cc = observe.Congestion{Type: "brutal", TxBytesPerSecond: actualTx}
	}
	c.observation = observe.New(qc, "client", cc)
	if authResp.UDPEnabled {
		c.udpSM = newUDPSessionManager(&udpIOImpl{Conn: qc})
	}
	return &HandshakeInfo{
		UDPEnabled:  authResp.UDPEnabled,
		Tx:          actualTx,
		Congestion:  cc,
		ServerAddr:  c.config.ServerAddr,
		ECHAccepted: qc.ConnectionState().TLS.ECHAccepted,
	}, nil
}

// openStream wraps the stream with QStream, which handles Close() properly
func (c *clientImpl) openStream() (*utils.QStream, error) {
	stream, err := c.conn.OpenStream()
	if err != nil {
		return nil, err
	}
	return &utils.QStream{Stream: stream}, nil
}

func (c *clientImpl) TCP(addr string) (net.Conn, error) {
	return c.TCPContext(context.Background(), addr)
}

// TCPContext opens a TCP proxy stream to addr, honoring ctx for the dial
// operation. Canceling ctx resets only this stream; the underlying QUIC
// connection is left open. Once this method returns successfully, ctx has no
// further effect on the returned connection.
func (c *clientImpl) TCPContext(ctx context.Context, addr string) (net.Conn, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	stream, err := c.openStream()
	if err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return nil, ctxErr
		}
		return nil, wrapIfConnectionClosed(err)
	}

	// Make blocking stream operations observe the context deadline even if
	// the cancellation callback below has not run yet.
	if deadline, ok := ctx.Deadline(); ok {
		_ = stream.SetDeadline(deadline)
	}

	// Canceling the dial context resets only this stream. The QUIC connection
	// and all other streams stay intact.
	cancelDone := make(chan struct{})
	stop := context.AfterFunc(ctx, func() {
		defer close(cancelDone)
		stream.CancelRead(streamErrCodeCancel)
		stream.CancelWrite(streamErrCodeCancel)
	})

	// stopCancellation prevents the callback from touching the stream after
	// this function returns. If the callback has already started, wait for it
	// to finish. A non-nil result means the dial failed due to context
	// cancellation and the stream must not be handed to the caller.
	stopCancellation := func(dialErr error) error {
		if !stop() {
			<-cancelDone
			_ = stream.Close()
			if err := ctx.Err(); err != nil {
				return err
			}
			return context.Canceled
		}
		if err := ctx.Err(); err != nil {
			_ = stream.Close()
			return err
		}
		// The stream deadline can fire slightly before the context's own timer
		// is observed. Map that timeout back to the context error so callers
		// can reliably use errors.Is(err, context.DeadlineExceeded).
		if _, ok := ctx.Deadline(); ok && errors.Is(dialErr, os.ErrDeadlineExceeded) {
			_ = stream.Close()
			return context.DeadlineExceeded
		}
		return nil
	}

	// Send request
	err = protocol.WriteTCPRequest(stream, addr)
	if err != nil {
		if ctxErr := stopCancellation(err); ctxErr != nil {
			return nil, ctxErr
		}
		_ = stream.Close()
		return nil, wrapIfConnectionClosed(err)
	}
	if c.config.FastOpen {
		// Don't wait for the response when fast open is enabled.
		// Return the connection immediately, defer the response handling
		// to the first Read() call.
		if ctxErr := stopCancellation(nil); ctxErr != nil {
			return nil, ctxErr
		}
		_ = stream.SetDeadline(time.Time{})
		return &tcpConn{
			Orig:             stream,
			PseudoLocalAddr:  c.conn.LocalAddr(),
			PseudoRemoteAddr: c.conn.RemoteAddr(),
			Established:      false,
			Context:          c.streamContext(stream),
		}, nil
	}
	// Read response
	ok, msg, err := protocol.ReadTCPResponse(stream)
	if err != nil {
		if ctxErr := stopCancellation(err); ctxErr != nil {
			return nil, ctxErr
		}
		_ = stream.Close()
		return nil, wrapIfConnectionClosed(err)
	}
	if !ok {
		if ctxErr := stopCancellation(nil); ctxErr != nil {
			return nil, ctxErr
		}
		_ = stream.Close()
		return nil, coreErrs.DialError{Message: msg}
	}
	if ctxErr := stopCancellation(nil); ctxErr != nil {
		return nil, ctxErr
	}
	// The context controls only the dial. Clear its deadline so it does not
	// leak into the lifetime of the returned connection.
	_ = stream.SetDeadline(time.Time{})
	return &tcpConn{
		Orig:             stream,
		PseudoLocalAddr:  c.conn.LocalAddr(),
		PseudoRemoteAddr: c.conn.RemoteAddr(),
		Established:      true,
		Context:          c.streamContext(stream),
	}, nil
}

func (c *clientImpl) UDP() (HyUDPConn, error) {
	if c.udpSM == nil {
		return nil, coreErrs.DialError{Message: "UDP not enabled"}
	}
	return c.udpSM.NewUDP()
}

func (c *clientImpl) Close() error {
	_ = c.conn.CloseWithError(closeErrCodeOK, "")
	_ = c.tr.Close()
	_ = c.pktConn.Close()
	if c.observation != nil {
		c.observation.Close()
	}
	return nil
}

func (c *clientImpl) ConnectionStats() observe.Snapshot {
	return c.observation.Snapshot()
}

func (c *clientImpl) Observation() *observe.Connection { return c.observation }

func (c *clientImpl) streamContext(stream *utils.QStream) observe.StreamContext {
	var id uint64
	if c.observation != nil {
		id = c.observation.ID()
	}
	return observe.StreamContext{ConnectionID: id, StreamID: int64(stream.StreamID()), RemoteAddr: c.conn.RemoteAddr().String()}
}

// Done is closed when the underlying QUIC connection is closed, either by the
// remote peer or by a call to Close. It lets the sidecar tear down local
// listeners and exit instead of staying in a stale READY state.
func (c *clientImpl) Done() <-chan struct{} {
	return c.conn.Context().Done()
}

var nonPermanentErrors = []error{
	quic.StreamLimitReachedError{},
}

// wrapIfConnectionClosed checks if the error returned by quic-go
// is recoverable (listed in nonPermanentErrors) or permanent.
// Recoverable errors are returned as-is,
// permanent ones are wrapped as ClosedError.
func wrapIfConnectionClosed(err error) error {
	for _, e := range nonPermanentErrors {
		if errors.Is(err, e) {
			return err
		}
	}
	return coreErrs.ClosedError{Err: err}
}

type tcpConn struct {
	Orig             *utils.QStream
	PseudoLocalAddr  net.Addr
	PseudoRemoteAddr net.Addr
	Established      bool
	Context          observe.StreamContext
}

// StreamConnection associates a byte stream with its local QUIC connection.
type StreamConnection interface {
	net.Conn
	StreamContext() observe.StreamContext
}

func (c *tcpConn) StreamContext() observe.StreamContext { return c.Context }

func (c *tcpConn) Read(b []byte) (n int, err error) {
	if !c.Established {
		// Read response
		ok, msg, err := protocol.ReadTCPResponse(c.Orig)
		if err != nil {
			return 0, err
		}
		if !ok {
			return 0, coreErrs.DialError{Message: msg}
		}
		c.Established = true
	}
	return c.Orig.Read(b)
}

func (c *tcpConn) Write(b []byte) (n int, err error) {
	return c.Orig.Write(b)
}

func (c *tcpConn) Close() error {
	return c.Orig.Close()
}

func (c *tcpConn) LocalAddr() net.Addr {
	return c.PseudoLocalAddr
}

func (c *tcpConn) RemoteAddr() net.Addr {
	return c.PseudoRemoteAddr
}

func (c *tcpConn) SetDeadline(t time.Time) error {
	return c.Orig.SetDeadline(t)
}

func (c *tcpConn) SetReadDeadline(t time.Time) error {
	return c.Orig.SetReadDeadline(t)
}

func (c *tcpConn) SetWriteDeadline(t time.Time) error {
	return c.Orig.SetWriteDeadline(t)
}

type udpIOImpl struct {
	Conn *quic.Conn
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
		return udpMsg, nil
	}
}

func (io *udpIOImpl) SendMessage(buf []byte, msg *protocol.UDPMessage) error {
	msgN := msg.Serialize(buf)
	if msgN < 0 {
		// Message larger than buffer, silent drop
		return nil
	}
	return io.Conn.SendDatagram(buf[:msgN])
}
