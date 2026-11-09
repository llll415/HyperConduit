package integration_tests

import (
	"io"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/apernet/hysteria/core/v2/internal/integration_tests/mocks"
	"github.com/apernet/hysteria/core/v2/server"
)

// TestClientServerTrafficLoggerTCP tests that the traffic logger is correctly called for TCP connections,
// and that the client is disconnected when the traffic logger returns false.
func TestClientServerTrafficLoggerTCP(t *testing.T) {
	// Create server
	udpConn, udpAddr, err := serverConn()
	assert.NoError(t, err)
	serverOb := mocks.NewMockOutbound(t)
	auth := mocks.NewMockAuthenticator(t)
	auth.EXPECT().Authenticate(mock.Anything, mock.Anything, mock.Anything).Return(true, "nobody")
	trafficLogger := mocks.NewMockTrafficLogger(t)
	s, err := server.NewServer(&server.Config{
		TLSConfig:     serverTLSConfig(),
		Conn:          udpConn,
		Outbound:      serverOb,
		Authenticator: auth,
		TrafficLogger: trafficLogger,
	})
	assert.NoError(t, err)
	defer s.Close()
	go s.Serve()

	// Create client
	trafficLogger.EXPECT().LogOnlineState("nobody", true).Return().Once()
	c, _, err := client.NewClient(&client.Config{
		ServerAddr: udpAddr,
		TLSConfig:  client.TLSConfig{InsecureSkipVerify: true},
	})
	assert.NoError(t, err)
	defer c.Close()

	addr := "dontcare.cc:4455"

	sobConn := mocks.NewMockConn(t)
	sobConnCh := make(chan []byte, 1)
	sobConnChCloseFunc := sync.OnceFunc(func() { close(sobConnCh) })
	sobConn.EXPECT().Read(mock.Anything).RunAndReturn(func(bs []byte) (int, error) {
		b := <-sobConnCh
		if b == nil {
			return 0, io.EOF
		} else {
			return copy(bs, b), nil
		}
	})
	sobConn.EXPECT().Close().RunAndReturn(func() error {
		sobConnChCloseFunc()
		return nil
	})
	serverOb.EXPECT().TCP(addr).Return(sobConn, nil).Once()
	trafficLogger.EXPECT().TraceStream(mock.Anything, mock.Anything).Return().Once()

	conn, err := c.TCP(addr)
	assert.NoError(t, err)

	// Client reads from server
	trafficLogger.EXPECT().LogTraffic("nobody", uint64(0), uint64(11)).Return(true).Once()
	sobConnCh <- []byte("knock knock")
	buf := make([]byte, 100)
	n, err := conn.Read(buf)
	assert.NoError(t, err)
	assert.Equal(t, 11, n)
	assert.Equal(t, "knock knock", string(buf[:n]))

	// Client writes to server
	trafficLogger.EXPECT().LogTraffic("nobody", uint64(12), uint64(0)).Return(true).Once()
	sobConn.EXPECT().Write([]byte("who is there")).Return(12, nil).Once()
	n, err = conn.Write([]byte("who is there"))
	assert.NoError(t, err)
	assert.Equal(t, 12, n)
	time.Sleep(1 * time.Second) // Need some time for the server to receive the data

	// Client reads from server again but blocked
	trafficLogger.EXPECT().UntraceStream(mock.Anything).Return().Once()
	trafficLogger.EXPECT().LogTraffic("nobody", uint64(0), uint64(4)).Return(false).Once()
	trafficLogger.EXPECT().LogOnlineState("nobody", false).Return().Once()
	sobConnCh <- []byte("nope")
	n, err = conn.Read(buf)
	assert.Zero(t, n)
	assert.Error(t, err)

	// The client should be disconnected
	_, err = c.TCP("whatever")
	assert.Error(t, err)
}
