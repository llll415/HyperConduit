package server

import (
	"bytes"
	"net/http"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/apernet/hysteria/core/v2/internal/protocol"
)

func TestNonzeroApplicationPaddingNeverDialsBackend(t *testing.T) {
	auth := &capabilityAuth{accept: true}
	address, backend := startCapabilityServer(t, nil, auth)
	c := newCapabilityClient(t, address)
	header := make(http.Header)
	protocol.AuthRequestToHeader(header, protocol.AuthRequest{Auth: "token"})
	resp := c.do(t, http.MethodPost, protocol.URLHost, protocol.URLPath, header)
	require.Equal(t, protocol.StatusAuthOK, resp.StatusCode)
	stream, err := c.conn.OpenStream()
	require.NoError(t, err)
	defer stream.Close()
	require.NoError(t, stream.SetDeadline(time.Now().Add(capabilityTestTimeout)))
	var request bytes.Buffer
	require.NoError(t, protocol.WriteTCPRequest(&request, "minecraft:0"))
	frame := request.Bytes()
	frame[len(frame)-1] = 1 // A removed application-padding feature, not QUIC padding.
	_, err = stream.Write(append(frame, 'x'))
	require.NoError(t, err)
	_, err = stream.Read(make([]byte, 1))
	require.Error(t, err)
	assert.Zero(t, backend.count(), "invalid framing must be rejected before outbound")
}

// TestAuthResponseNeverEnablesUDP locks in the TCP-only transport contract:
// authentication must never advertise datagram support to a client.
func TestAuthResponseNeverEnablesUDP(t *testing.T) {
	auth := &capabilityAuth{accept: true}
	address, _ := startCapabilityServer(t, nil, auth)
	c := newCapabilityClient(t, address)

	header := make(http.Header)
	protocol.AuthRequestToHeader(header, protocol.AuthRequest{Auth: "token"})
	resp := c.do(t, http.MethodPost, protocol.URLHost, protocol.URLPath, header)
	require.Equal(t, protocol.StatusAuthOK, resp.StatusCode)
	assert.False(t, protocol.AuthResponseFromHeader(resp.Header).UDPEnabled)
}

// TestFixedMasqueradeAlwaysNotFound ensures the unauthenticated response is a
// fixed 404 and cannot be replaced by a configurable handler.
func TestFixedMasqueradeAlwaysNotFound(t *testing.T) {
	address, _ := startCapabilityServer(t, nil, nil)
	c := newCapabilityClient(t, address)

	for _, path := range []string{"/", "/index.html", protocol.URLPath + "-other"} {
		resp := c.do(t, http.MethodGet, "example.com", path, nil)
		assert.Equal(t, http.StatusNotFound, resp.StatusCode, "path %q", path)
	}
}

// TestTCPDialFailureWritesSafeErrorResponse verifies the fixed request order:
// the server reads the address, attempts the outbound dial, and only then
// writes a response. A failed dial must produce an error response instead of
// leaving the stream without one.
func TestTCPDialFailureWritesSafeErrorResponse(t *testing.T) {
	auth := &capabilityAuth{accept: true}
	address, backend := startCapabilityServer(t, nil, auth)
	c := newCapabilityClient(t, address)

	header := make(http.Header)
	protocol.AuthRequestToHeader(header, protocol.AuthRequest{Auth: "token"})
	resp := c.do(t, http.MethodPost, protocol.URLHost, protocol.URLPath, header)
	require.Equal(t, protocol.StatusAuthOK, resp.StatusCode)
	require.NotNil(t, c.conn)

	stream, err := c.conn.OpenStream()
	require.NoError(t, err)
	defer stream.Close()
	require.NoError(t, stream.SetDeadline(time.Now().Add(capabilityTestTimeout)))
	require.NoError(t, protocol.WriteTCPRequest(stream, "127.0.0.1:1"))

	ok, msg, err := protocol.ReadTCPResponse(stream)
	require.NoError(t, err)
	assert.False(t, ok)
	assert.Equal(t, "target unavailable", msg)
	assert.Equal(t, 1, backend.count())
}
