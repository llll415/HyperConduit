package integration_tests

import (
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"net/url"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/apernet/hysteria/core/v2/internal/integration_tests/mocks"
	"github.com/apernet/hysteria/core/v2/internal/protocol"
	"github.com/apernet/hysteria/core/v2/server"

	"github.com/apernet/quic-go"
	"github.com/apernet/quic-go/http3"
)

// TestClientServerTCPBasic keeps the meaningful TCP regression from the old
// hook test: a plain TCP request still reaches the outbound and echoes data
// without any request hook or sniffing stage.
func TestClientServerTCPBasic(t *testing.T) {
	udpConn, udpAddr, err := serverConn()
	assert.NoError(t, err)
	auth := mocks.NewMockAuthenticator(t)
	auth.EXPECT().Authenticate(mock.Anything, mock.Anything, mock.Anything).Return(true, "nobody")
	s, err := server.NewServer(&server.Config{
		TLSConfig:     serverTLSConfig(),
		Conn:          udpConn,
		Outbound:      testOutbound{},
		Authenticator: auth,
	})
	assert.NoError(t, err)
	defer s.Close()
	go s.Serve()

	echoListener, err := net.Listen("tcp", "127.0.0.1:0")
	assert.NoError(t, err)
	echoServer := &tcpEchoServer{Listener: echoListener}
	defer echoServer.Close()
	go echoServer.Serve()

	c, _, err := client.NewClient(&client.Config{
		ServerAddr: udpAddr,
		TLSConfig:  client.TLSConfig{InsecureSkipVerify: true},
	})
	assert.NoError(t, err)
	defer c.Close()

	conn, err := c.TCP(echoListener.Addr().String())
	assert.NoError(t, err)
	defer conn.Close()

	sData := []byte("hello world")
	_, err = conn.Write(sData)
	assert.NoError(t, err)
	rData := make([]byte, len(sData))
	_, err = io.ReadFull(conn, rData)
	assert.NoError(t, err)
	assert.Equal(t, sData, rData)
}

// TestServerRejectsUnauthenticatedRequests keeps the meaningful gate
// regression from the old masquerade test: unauthenticated HTTP gets the
// fixed 404 page and an unauthenticated proxy stream receives nothing.
func TestServerRejectsUnauthenticatedRequests(t *testing.T) {
	udpConn, udpAddr, err := serverConn()
	assert.NoError(t, err)
	auth := mocks.NewMockAuthenticator(t)
	auth.EXPECT().Authenticate(mock.Anything, "", uint64(0)).Return(false, "").Once()
	s, err := server.NewServer(&server.Config{
		TLSConfig:     serverTLSConfig(),
		Conn:          udpConn,
		Outbound:      testOutbound{},
		Authenticator: auth,
	})
	assert.NoError(t, err)
	defer s.Close()
	go s.Serve()

	var conn *quic.Conn
	rt := &http3.Transport{
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true,
		},
		Dial: func(ctx context.Context, _ string, tlsCfg *tls.Config, cfg *quic.Config) (*quic.Conn, error) {
			qc, err := quic.DialAddrEarly(ctx, udpAddr.String(), tlsCfg, cfg)
			if err != nil {
				return nil, err
			}
			conn = qc
			return qc, nil
		},
	}
	defer rt.Close()

	req := &http.Request{
		Method: http.MethodPost,
		URL: &url.URL{
			Scheme: "https",
			Host:   protocol.URLHost,
			Path:   protocol.URLPath,
		},
		Header: make(http.Header),
	}
	resp, err := rt.RoundTrip(req)
	assert.NoError(t, err)
	assert.Equal(t, http.StatusNotFound, resp.StatusCode)
	for k := range resp.Header {
		assert.NotContains(t, k, "Hysteria")
	}

	buf := make([]byte, 1024)
	tcpStream, err := conn.OpenStream()
	assert.NoError(t, err)
	defer tcpStream.Close()
	err = protocol.WriteTCPRequest(tcpStream, "www.google.com:443")
	assert.NoError(t, err)

	_ = tcpStream.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, err := tcpStream.Read(buf)
	assert.Equal(t, 0, n)
	nErr, ok := err.(net.Error)
	assert.True(t, ok)
	assert.True(t, nErr.Timeout())
}
