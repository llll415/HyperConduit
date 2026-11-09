package integration_tests

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/apernet/hysteria/core/v2/internal/integration_tests/mocks"
	"github.com/apernet/hysteria/core/v2/internal/protocol"
	"github.com/apernet/hysteria/core/v2/server"

	"github.com/apernet/quic-go"
)

// countingOutbound records TCP dial attempts so a test can assert that the
// server never dials the remote before authentication.
type countingOutbound struct {
	tcpCalls atomic.Int64
}

func (o *countingOutbound) TCP(reqAddr string) (net.Conn, error) {
	o.tcpCalls.Add(1)
	return nil, errors.New("unexpected pre-auth outbound")
}

// TestClientServerPreAuthNoOutbound is the TCP replacement for the old UDP ACL
// coverage. It ensures that a TCP proxy request sent on an unauthenticated QUIC
// connection never reaches the server Outbound, regardless of the requested
// address. This is the core pre-auth isolation guarantee: no outbound dial may
// happen before the client has authenticated.
func TestClientServerPreAuthNoOutbound(t *testing.T) {
	udpConn, udpAddr, err := serverConn()
	require.NoError(t, err)
	outbound := &countingOutbound{}
	// No Authenticate expectation: a raw proxy stream on an unauthenticated
	// connection must not reach the authenticator or the outbound.
	auth := mocks.NewMockAuthenticator(t)
	s, err := server.NewServer(&server.Config{
		TLSConfig:     serverTLSConfig(),
		Conn:          udpConn,
		Outbound:      outbound,
		Authenticator: auth,
	})
	require.NoError(t, err)
	defer s.Close()
	go s.Serve()

	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	qc, err := quic.DialAddrEarly(ctx, udpAddr.String(), &tls.Config{
		InsecureSkipVerify: true,
		NextProtos:         []string{"h3"},
	}, nil)
	require.NoError(t, err)
	defer qc.CloseWithError(0, "")

	stream, err := qc.OpenStream()
	require.NoError(t, err)
	defer stream.Close()
	require.NoError(t, protocol.WriteTCPRequest(stream, "127.0.0.1:22444"))

	// The server must silently drop the pre-auth proxy stream: no TCP response
	// and, crucially, no outbound dial.
	_ = stream.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
	buf := make([]byte, 1)
	n, err := stream.Read(buf)
	assert.Zero(t, n)
	assert.Error(t, err)

	assert.Zero(t, outbound.tcpCalls.Load(), "Outbound must not be dialed before authentication")
}
