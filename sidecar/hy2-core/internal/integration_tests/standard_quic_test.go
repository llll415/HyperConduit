package integration_tests

import (
	"io"
	"net"
	"testing"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/apernet/hysteria/core/v2/internal/integration_tests/mocks"
	"github.com/apernet/hysteria/core/v2/server"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

// TestClientServerStandardQUIC is the regression test for the default,
// standard QUIC/TCP path. Chrome fingerprint parroting and the UDP session API
// have been removed from the client, so a zero-value QUICConfig must still
// connect to the server and carry a TCP stream end to end.
func TestClientServerStandardQUIC(t *testing.T) {
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

	echoAddr := "127.0.0.1:22444"
	echoListener, err := net.Listen("tcp", echoAddr)
	assert.NoError(t, err)
	echoServer := &tcpEchoServer{Listener: echoListener}
	defer echoServer.Close()
	go echoServer.Serve()

	c, info, err := client.NewClient(&client.Config{
		ServerAddr: udpAddr,
		TLSConfig:  client.TLSConfig{InsecureSkipVerify: true},
		QUICConfig: client.QUICConfig{},
	})
	assert.NoError(t, err)
	defer c.Close()
	// Neither endpoint offers application UDP or QUIC datagrams.
	assert.False(t, info.UDPEnabled)

	conn, err := c.TCP(echoAddr)
	assert.NoError(t, err)
	defer conn.Close()

	sData := []byte("hello over standard QUIC")
	_, err = conn.Write(sData)
	assert.NoError(t, err)
	rData := make([]byte, len(sData))
	_, err = io.ReadFull(conn, rData)
	assert.NoError(t, err)
	assert.Equal(t, sData, rData)
}
