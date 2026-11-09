// Package tunnel implements the fixed-service transport, not a generic proxy.
package tunnel

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"io"
	"net"
	"sync"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/apernet/hysteria/core/v2/server"

	"github.com/llll415/HyperConduit/sidecar/internal/config"
	"github.com/llll415/HyperConduit/sidecar/internal/identity"
	"github.com/llll415/HyperConduit/sidecar/internal/privatefile"
)

// capabilityResponse is the fixed, public declaration served before
// authentication. It is intentionally a compile-time constant: probing must not
// depend on server configuration, credentials, or identity.
const capabilityResponse = `{"protocol":1,"enabled":true}`

// Endpoint owns every socket and copy loop created for one role. No reconnect or
// direct-TCP fallback is performed. READY only describes this endpoint's listener.
type Endpoint struct {
	addr        net.Addr
	cancel      context.CancelFunc
	once        sync.Once
	done        chan struct{}
	stop        func()
	waitErr     error // written before done is closed
	status      *statusStore
	fingerprint string
}

func (e *Endpoint) Addr() net.Addr { return e.addr }

// Fingerprint is the server's public SPKI fingerprint; it contains no key data.
func (e *Endpoint) Fingerprint() string { return e.fingerprint }

// Status returns only active connection/stream associations and bounded counters.
func (e *Endpoint) Status() Status { return e.status.snapshot() }

func (e *Endpoint) Wait() error {
	<-e.done
	return e.waitErr
}

func (e *Endpoint) Close() error {
	e.once.Do(func() {
		e.cancel()
		e.stop()
	})
	return e.Wait()
}

// StartServer loads or creates a persisted identity, unless explicit certificate
// files were supplied by the administrator.
func StartServer(parent context.Context, raw config.Config) (*Endpoint, error) {
	endpoint, err := openServer(parent, raw)
	return endpoint, classifyError("server", err)
}

func openServer(parent context.Context, raw config.Config) (*Endpoint, error) {
	c, err := raw.Normalize("server")
	if err != nil {
		return nil, &Error{Code: CodeConfig, Op: "server", Err: err}
	}
	if err := parent.Err(); err != nil {
		return nil, err
	}
	var cert tls.Certificate
	if c.CertFile == "" {
		cert, err = identity.LoadOrCreate(parent, c.IdentityFile)
	} else {
		var certPEM, keyPEM []byte
		certPEM, err = privatefile.Read(c.CertFile, 1<<20)
		if err == nil {
			keyPEM, err = privatefile.Read(c.KeyFile, 64<<10)
		}
		if err == nil {
			cert, err = tls.X509KeyPair(certPEM, keyPEM)
		}
	}
	if err != nil {
		if c.CertFile == "" {
			return nil, &Error{Code: CodeStorage, Op: "identity", Err: err}
		}
		return nil, &Error{Code: CodeTLS, Op: "server", Err: err}
	}
	ctx, cancel := context.WithCancel(parent)
	pc, err := (&net.ListenConfig{}).ListenPacket(ctx, "udp", c.Listen)
	if err != nil {
		cancel()
		return nil, err
	}
	conns := newConnSet(c.MaxConnections)
	status := newStatusStore()
	serverTLS := server.TLSConfig{Certificates: []tls.Certificate{cert}}
	if c.CertFile == "" {
		serverTLS = server.TLSConfig{GetCertificate: managedCertificate(c.IdentityFile, cert)}
	}
	outbound := &fixedOutbound{ctx: ctx, target: c.Target, timeout: time.Duration(c.ConnectTimeoutMS) * time.Millisecond, conns: conns, status: status, bridgeToken: c.BridgeToken}
	s, err := server.NewServer(&server.Config{
		Conn:      pc,
		TLSConfig: serverTLS,
		QUICConfig: server.QUICConfig{
			InitialStreamReceiveWindow: 256 * 1024, MaxStreamReceiveWindow: 4 * 1024 * 1024,
			InitialConnectionReceiveWindow: 512 * 1024, MaxConnectionReceiveWindow: 8 * 1024 * 1024,
			MaxIdleTimeout: time.Duration(c.IdleTimeoutMS) * time.Millisecond, MaxIncomingStreams: c.MaxStreams,
		},
		Outbound: outbound, DisableUDP: true,
		Authenticator:         statusAuthenticator{tokenAuthenticator{expected: c.Token}, status},
		CongestionConfig:      server.CongestionConfig{Type: c.Congestion},
		BandwidthConfig:       server.BandwidthConfig{MaxTx: c.UpBytesPerSecond(), MaxRx: c.DownBytesPerSecond()},
		IgnoreClientBandwidth: c.DisableBrutal,
		RequestTimeout:        time.Duration(c.RequestTimeoutMS) * time.Millisecond,
		MaxConnections:        c.MaxConnections,
		CapabilityResponse:    []byte(capabilityResponse),
		ConnectionObserver:    status.connection,
		ErrorObserver:         func(err error) { status.record(classifyError("request", err)) },
	})
	if err != nil {
		cancel()
		_ = pc.Close()
		return nil, err
	}
	if cert.Leaf == nil {
		cert.Leaf, _ = x509.ParseCertificate(cert.Certificate[0])
	}
	e := &Endpoint{addr: pc.LocalAddr(), cancel: cancel, done: make(chan struct{}), status: status, fingerprint: identity.Fingerprint(cert.Leaf)}
	e.stop = func() { _ = s.Close(); conns.close() }
	go func() {
		stop := context.AfterFunc(ctx, func() { e.once.Do(e.stop) })
		err := s.Serve()
		stop()
		wasCanceled := ctx.Err() != nil
		e.once.Do(func() { cancel(); e.stop() })
		if !wasCanceled {
			e.waitErr = classifyError("server", err)
			status.record(e.waitErr)
		}
		close(e.done)
	}()
	return e, nil
}

func StartClient(parent context.Context, raw config.Config) (*Endpoint, error) {
	endpoint, err := openClient(parent, raw)
	return endpoint, classifyError("client", err)
}

func openClient(parent context.Context, raw config.Config) (*Endpoint, error) {
	c, err := raw.Normalize("client")
	if err != nil {
		return nil, &Error{Code: CodeConfig, Op: "client", Err: err}
	}
	ctx, cancel := context.WithCancel(parent)
	connectCtx, connectCancel := context.WithTimeout(ctx, time.Duration(c.ConnectTimeoutMS)*time.Millisecond)
	defer connectCancel()
	tlsConfig, trustSession, err := clientTLS(connectCtx, c)
	if err != nil {
		cancel()
		return nil, &Error{Code: CodeTLS, Op: "client", Err: err}
	}
	host, port, _ := net.SplitHostPort(c.Server)
	ips, err := net.DefaultResolver.LookupNetIP(connectCtx, "ip", host)
	if err != nil || len(ips) == 0 {
		cancel()
		if err == nil {
			err = errors.New("server resolved to no addresses")
		}
		return nil, err
	}
	udpAddr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(ips[0].String(), port))
	if err != nil {
		cancel()
		return nil, err
	}
	// Keep certificate identity distinct from the resolved network address.
	if tlsConfig.ServerName == "" {
		tlsConfig.ServerName = host
	}
	hy, _, err := client.NewClientContext(connectCtx, &client.Config{
		ServerAddr: udpAddr, Auth: c.Token, TLSConfig: tlsConfig,
		QUICConfig: client.QUICConfig{
			DisableChromeParrot:        true,
			InitialStreamReceiveWindow: 256 * 1024, MaxStreamReceiveWindow: 4 * 1024 * 1024,
			InitialConnectionReceiveWindow: 512 * 1024, MaxConnectionReceiveWindow: 8 * 1024 * 1024,
			MaxIdleTimeout:  time.Duration(c.IdleTimeoutMS) * time.Millisecond,
			KeepAlivePeriod: 2 * time.Second,
		},
		CongestionConfig: client.CongestionConfig{Type: c.Congestion},
		BandwidthConfig:  client.BandwidthConfig{MaxTx: c.UpBytesPerSecond(), MaxRx: c.DownBytesPerSecond()},
	})
	if err != nil {
		cancel()
		return nil, err
	}
	// Pin only after TLS key proof and HY2 authentication succeeded. Recheck
	// under the store lock so concurrent first connections cannot both win.
	if err := trustSession.commit(connectCtx); err != nil {
		cancel()
		_ = hy.Close()
		return nil, classifyError("trust", err)
	}
	ln, err := (&net.ListenConfig{}).Listen(ctx, "tcp", c.Listen)
	if err != nil {
		cancel()
		_ = hy.Close()
		return nil, err
	}
	conns := newConnSet(c.MaxConnections)
	status := newStatusStore()
	// Local observations use the core-assigned ID, shared by all its streams.
	status.connection(hy.Observation(), true)
	contextClient := hy
	e := &Endpoint{addr: ln.Addr(), cancel: cancel, done: make(chan struct{}), status: status}
	e.stop = func() { _ = ln.Close(); conns.close(); _ = hy.Close() }
	go func() {
		stop := context.AfterFunc(ctx, func() { e.once.Do(e.stop) })
		monitorDone := make(chan struct{})
		defer close(monitorDone)
		go func() {
			select {
			case <-contextClient.Done():
				_ = ln.Close()
			case <-monitorDone:
			}
		}()
		var wg sync.WaitGroup
		var acceptErr error
		for {
			local, err := ln.Accept()
			if err != nil {
				acceptErr = err
				break
			}
			if !conns.reserve() {
				status.record(&Error{Code: CodeLimit, Op: "accept"})
				_ = local.Close()
				continue
			}
			owned, ok := conns.add(local)
			if !ok {
				break
			}
			wg.Add(1)
			bindingID := status.addBinding(local.LocalAddr().String(), local.RemoteAddr().String())
			go func() {
				defer wg.Done()
				defer owned.Close()
				defer status.removeBinding(bindingID)
				requestCtx, requestCancel := context.WithTimeout(ctx, time.Duration(c.RequestTimeoutMS)*time.Millisecond)
				remote, err := contextClient.TCPContext(requestCtx, serviceAddress)
				requestCancel()
				if err != nil {
					status.record(classifyError("stream", err))
					return
				}
				status.establish(bindingID, remote.(client.StreamConnection).StreamContext())
				bridge(owned, remote)
			}()
		}
		stop()
		wasCanceled := ctx.Err() != nil
		e.once.Do(func() { cancel(); e.stop() })
		wg.Wait()
		status.connection(hy.Observation(), false)
		if !wasCanceled {
			e.waitErr = classifyError("client", acceptErr)
			status.record(e.waitErr)
		}
		close(e.done)
	}()
	return e, nil
}

func bridge(a, b net.Conn) {
	done := make(chan struct{}, 2)
	copyOne := func(dst, src net.Conn) {
		// Hide ReaderFrom/WriterTo optimizations so buffers remain explicitly bounded.
		_, _ = io.CopyBuffer(writer{dst}, reader{src}, make([]byte, 32*1024))
		done <- struct{}{}
	}
	go copyOne(a, b)
	go copyOne(b, a)
	<-done
	_ = a.Close()
	_ = b.Close()
	<-done
}

type reader struct{ io.Reader }
type writer struct{ io.Writer }
