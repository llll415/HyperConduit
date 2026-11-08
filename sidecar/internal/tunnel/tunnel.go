// Package tunnel implements the fixed-service transport, not a generic proxy.
package tunnel

import (
	"context"
	"crypto/tls"
	"errors"
	"io"
	"net"
	"sync"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/apernet/hysteria/core/v2/server"

	"github.com/llll415/HyperConduit/sidecar/internal/config"
)

// Endpoint owns every socket and copy loop created for one role. No reconnect or
// direct-TCP fallback is performed. READY only describes this endpoint's listener.
type Endpoint struct {
	addr    net.Addr
	cancel  context.CancelFunc
	once    sync.Once
	done    chan struct{}
	stop    func()
	waitErr error // written before done is closed
}

func (e *Endpoint) Addr() net.Addr { return e.addr }

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

// StartServer requires explicit certificate files at this stage. Automatic
// persisted identity and TOFU are implemented separately in plan step 4.
func StartServer(parent context.Context, raw config.Config) (*Endpoint, error) {
	c, err := raw.Normalize("server")
	if err != nil {
		return nil, err
	}
	if err := parent.Err(); err != nil {
		return nil, err
	}
	cert, err := tls.LoadX509KeyPair(c.CertFile, c.KeyFile)
	if err != nil {
		return nil, errors.New("cannot load certFile/keyFile")
	}
	ctx, cancel := context.WithCancel(parent)
	pc, err := (&net.ListenConfig{}).ListenPacket(ctx, "udp", c.Listen)
	if err != nil {
		cancel()
		return nil, err
	}
	conns := newConnSet(c.MaxConnections)
	outbound := &fixedOutbound{ctx: ctx, target: c.Target, timeout: time.Duration(c.ConnectTimeoutMS) * time.Millisecond, conns: conns}
	s, err := server.NewServer(&server.Config{
		Conn:      pc,
		TLSConfig: server.TLSConfig{Certificates: []tls.Certificate{cert}},
		QUICConfig: server.QUICConfig{
			InitialStreamReceiveWindow: 256 * 1024, MaxStreamReceiveWindow: 4 * 1024 * 1024,
			InitialConnectionReceiveWindow: 512 * 1024, MaxConnectionReceiveWindow: 8 * 1024 * 1024,
			MaxIdleTimeout: time.Duration(c.IdleTimeoutMS) * time.Millisecond, MaxIncomingStreams: c.MaxStreams,
		},
		Outbound: outbound, DisableUDP: true,
		Authenticator:         tokenAuthenticator{expected: c.Token},
		CongestionConfig:      server.CongestionConfig{Type: c.Congestion},
		BandwidthConfig:       server.BandwidthConfig{MaxTx: c.UpBytesPerSecond(), MaxRx: c.DownBytesPerSecond()},
		IgnoreClientBandwidth: c.DisableBrutal,
		RequestTimeout:        time.Duration(c.RequestTimeoutMS) * time.Millisecond,
		MaxConnections:        c.MaxConnections,
	})
	if err != nil {
		cancel()
		_ = pc.Close()
		return nil, err
	}
	e := &Endpoint{addr: pc.LocalAddr(), cancel: cancel, done: make(chan struct{})}
	e.stop = func() { _ = s.Close(); conns.close() }
	go func() {
		stop := context.AfterFunc(ctx, func() { e.once.Do(e.stop) })
		err := s.Serve()
		stop()
		wasCanceled := ctx.Err() != nil
		e.once.Do(func() { cancel(); e.stop() })
		if !wasCanceled {
			e.waitErr = err
		}
		close(e.done)
	}()
	return e, nil
}

func StartClient(parent context.Context, raw config.Config) (*Endpoint, error) {
	c, err := raw.Normalize("client")
	if err != nil {
		return nil, err
	}
	tlsConfig, err := clientTLS(c)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(parent)
	connectCtx, connectCancel := context.WithTimeout(ctx, time.Duration(c.ConnectTimeoutMS)*time.Millisecond)
	defer connectCancel()
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
	ln, err := (&net.ListenConfig{}).Listen(ctx, "tcp", c.Listen)
	if err != nil {
		cancel()
		_ = hy.Close()
		return nil, err
	}
	conns := newConnSet(c.MaxConnections)
	contextClient := hy
	e := &Endpoint{addr: ln.Addr(), cancel: cancel, done: make(chan struct{})}
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
				_ = local.Close()
				continue
			}
			owned, ok := conns.add(local)
			if !ok {
				break
			}
			wg.Add(1)
			go func() {
				defer wg.Done()
				defer owned.Close()
				requestCtx, requestCancel := context.WithTimeout(ctx, time.Duration(c.RequestTimeoutMS)*time.Millisecond)
				remote, err := contextClient.TCPContext(requestCtx, serviceAddress)
				requestCancel()
				if err != nil {
					return
				}
				bridge(owned, remote)
			}()
		}
		stop()
		wasCanceled := ctx.Err() != nil
		e.once.Do(func() { cancel(); e.stop() })
		wg.Wait()
		if !wasCanceled {
			e.waitErr = acceptErr
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
