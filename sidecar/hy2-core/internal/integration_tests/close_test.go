package integration_tests

import (
	"io"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/apernet/hysteria/core/v2/errors"
	"github.com/apernet/hysteria/core/v2/internal/integration_tests/mocks"
	"github.com/apernet/hysteria/core/v2/server"
)

// TestClientServerTCPClose tests whether the client/server propagates the close of a connection correctly.
// Closing one side of the connection should close the other side as well.
func TestClientServerTCPClose(t *testing.T) {
	// Create server
	udpConn, udpAddr, err := serverConn()
	assert.NoError(t, err)
	serverOb := mocks.NewMockOutbound(t)
	auth := mocks.NewMockAuthenticator(t)
	auth.EXPECT().Authenticate(mock.Anything, mock.Anything, mock.Anything).Return(true, "nobody")
	s, err := server.NewServer(&server.Config{
		TLSConfig:     serverTLSConfig(),
		Conn:          udpConn,
		Outbound:      serverOb,
		Authenticator: auth,
	})
	assert.NoError(t, err)
	defer s.Close()
	go s.Serve()

	// Create client
	c, _, err := client.NewClient(&client.Config{
		ServerAddr: udpAddr,
		TLSConfig:  client.TLSConfig{InsecureSkipVerify: true},
	})
	assert.NoError(t, err)
	defer c.Close()

	addr := "hi-and-goodbye:2333"

	// Test close from client side:
	// Client creates a connection, writes something, then closes it.
	// Server outbound connection should write the same thing, then close.
	sobConn := mocks.NewMockConn(t)
	sobConnCh := make(chan struct{}) // For close signal only
	sobConnChCloseFunc := sync.OnceFunc(func() { close(sobConnCh) })
	sobConn.EXPECT().Read(mock.Anything).RunAndReturn(func(bs []byte) (int, error) {
		<-sobConnCh
		return 0, io.EOF
	})
	sobConn.EXPECT().Write([]byte("happy")).Return(5, nil)
	sobConn.EXPECT().Close().RunAndReturn(func() error {
		sobConnChCloseFunc()
		return nil
	})
	serverOb.EXPECT().TCP(addr).Return(sobConn, nil).Once()
	conn, err := c.TCP(addr)
	assert.NoError(t, err)
	_, err = conn.Write([]byte("happy"))
	assert.NoError(t, err)
	err = conn.Close()
	assert.NoError(t, err)
	time.Sleep(1 * time.Second)
	mock.AssertExpectationsForObjects(t, sobConn, serverOb)

	// Test close from server side:
	// Client creates a connection.
	// Server outbound connection reads something, then closes.
	// Client connection should read the same thing, then close.
	sobConn = mocks.NewMockConn(t)
	sobConnCh2 := make(chan []byte, 1)
	sobConn.EXPECT().Read(mock.Anything).RunAndReturn(func(bs []byte) (int, error) {
		d := <-sobConnCh2
		if d == nil {
			return 0, io.EOF
		} else {
			return copy(bs, d), nil
		}
	})
	sobConn.EXPECT().Close().Return(nil)
	serverOb.EXPECT().TCP(addr).Return(sobConn, nil).Once()
	conn, err = c.TCP(addr)
	assert.NoError(t, err)
	sobConnCh2 <- []byte("happy")
	close(sobConnCh2)
	bs, err := io.ReadAll(conn)
	assert.NoError(t, err)
	assert.Equal(t, "happy", string(bs))
}

// TestClientServerClientShutdown tests whether the server can handle the client's shutdown correctly.
func TestClientServerClientShutdown(t *testing.T) {
	// Create server
	udpConn, udpAddr, err := serverConn()
	assert.NoError(t, err)
	auth := mocks.NewMockAuthenticator(t)
	auth.EXPECT().Authenticate(mock.Anything, mock.Anything, mock.Anything).Return(true, "nobody")
	eventLogger := mocks.NewMockEventLogger(t)
	eventLogger.EXPECT().Connect(mock.Anything, "nobody", mock.Anything).Once()
	s, err := server.NewServer(&server.Config{
		TLSConfig:     serverTLSConfig(),
		Conn:          udpConn,
		Outbound:      testOutbound{},
		Authenticator: auth,
		EventLogger:   eventLogger,
	})
	assert.NoError(t, err)
	defer s.Close()
	go s.Serve()

	// Create client
	c, _, err := client.NewClient(&client.Config{
		ServerAddr: udpAddr,
		TLSConfig:  client.TLSConfig{InsecureSkipVerify: true},
	})
	assert.NoError(t, err)

	// Close the client - expect disconnect event on the server side.
	// Since client.Close() sends HTTP3 ErrCodeNoError, the error should be nil.
	eventLogger.EXPECT().Disconnect(mock.Anything, "nobody", nil).Once()
	_ = c.Close()
	time.Sleep(1 * time.Second)
}

// TestClientServerServerShutdown tests whether the client can handle the server's shutdown correctly.
func TestClientServerServerShutdown(t *testing.T) {
	// Create server
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
	go s.Serve()

	// Create client
	c, _, err := client.NewClient(&client.Config{
		ServerAddr: udpAddr,
		TLSConfig:  client.TLSConfig{InsecureSkipVerify: true},
		QUICConfig: client.QUICConfig{
			MaxIdleTimeout: 4 * time.Second,
		},
	})
	assert.NoError(t, err)

	// Close the server - expect the client to return ClosedError for TCP calls.
	_ = s.Close()

	_, err = c.TCP("whatever")
	_, ok := err.(errors.ClosedError)
	assert.True(t, ok)

	assert.NoError(t, c.Close())
}
