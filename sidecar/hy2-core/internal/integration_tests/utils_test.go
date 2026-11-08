package integration_tests

import (
	"crypto/tls"
	"io"
	"net"
	"time"

	"github.com/apernet/hysteria/core/v2/server"
)

// This file provides utilities for the integration tests.

const (
	testCertFile = "test.crt"
	testKeyFile  = "test.key"
)

func serverTLSConfig() server.TLSConfig {
	cert, err := tls.LoadX509KeyPair(testCertFile, testKeyFile)
	if err != nil {
		panic(err)
	}
	return server.TLSConfig{
		Certificates: []tls.Certificate{cert},
	}
}

func serverConn() (net.PacketConn, net.Addr, error) {
	udpAddr := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 14514}
	udpConn, err := net.ListenUDP("udp", udpAddr)
	if err != nil {
		return nil, nil, err
	}
	return udpConn, udpAddr, nil
}

// testOutbound is the outbound used by integration tests that only care about
// the server-side behavior. It forwards to the real target and keeps the
// full-cone UDP semantics the server used to provide by default.
type testOutbound struct{}

var testOutboundDialer = net.Dialer{
	Timeout: 10 * time.Second,
}

func (o testOutbound) TCP(reqAddr string) (net.Conn, error) {
	return testOutboundDialer.Dial("tcp", reqAddr)
}

func (o testOutbound) UDP(reqAddr string) (server.UDPConn, error) {
	conn, err := net.ListenUDP("udp", nil)
	if err != nil {
		return nil, err
	}
	return &testUDPConn{conn}, nil
}

func (o testOutbound) CheckUDP(reqAddr string) error {
	return nil
}

// testUDPConn is the full-cone UDP connection wrapper used by testOutbound.
type testUDPConn struct {
	*net.UDPConn
}

func (c *testUDPConn) ReadFrom(b []byte) (int, string, error) {
	n, addr, err := c.UDPConn.ReadFrom(b)
	if addr != nil {
		return n, addr.String(), err
	}
	return n, "", err
}

func (c *testUDPConn) WriteTo(b []byte, addr string) (int, error) {
	uAddr, err := net.ResolveUDPAddr("udp", addr)
	if err != nil {
		return 0, err
	}
	return c.UDPConn.WriteTo(b, uAddr)
}

// tcpEchoServer is a TCP server that echoes what it reads from the connection.
// It will never actively close the connection.
type tcpEchoServer struct {
	Listener net.Listener
}

func (s *tcpEchoServer) Serve() error {
	for {
		conn, err := s.Listener.Accept()
		if err != nil {
			return err
		}
		go func() {
			_, _ = io.Copy(conn, conn)
			_ = conn.Close()
		}()
	}
}

func (s *tcpEchoServer) Close() error {
	return s.Listener.Close()
}

// udpEchoServer is a UDP server that echoes what it reads from the connection.
// It will never actively close the connection.
type udpEchoServer struct {
	Conn net.PacketConn
}

func (s *udpEchoServer) Serve() error {
	buf := make([]byte, 65536)
	for {
		n, addr, err := s.Conn.ReadFrom(buf)
		if err != nil {
			return err
		}
		_, err = s.Conn.WriteTo(buf[:n], addr)
		if err != nil {
			return err
		}
	}
}

func (s *udpEchoServer) Close() error {
	return s.Conn.Close()
}
