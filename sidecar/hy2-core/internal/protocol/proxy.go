package protocol

import (
	"fmt"
	"io"

	"github.com/apernet/hysteria/core/v2/errors"

	"github.com/apernet/quic-go/quicvarint"
)

const (
	FrameTypeTCPRequest = 0x401

	// Max length values are for preventing DoS attacks

	MaxAddressLength = 2048
	MaxMessageLength = 2048

	maxVarInt1 = 63
	maxVarInt2 = 16383
	maxVarInt4 = 1073741823
	maxVarInt8 = 4611686018427387903
)

// TCPRequest format:
// 0x401 (QUIC varint)
// Address length (QUIC varint)
// Address (bytes)
// Padding length (QUIC varint, always 0)
//
// There is no padding. The trailing padding length is kept for framing
// compatibility, but the writer always emits 0 and the reader rejects any
// non-zero value.

func ReadTCPRequest(r io.Reader) (string, error) {
	bReader := quicvarint.NewReader(r)
	addrLen, err := quicvarint.Read(bReader)
	if err != nil {
		return "", err
	}
	if addrLen == 0 || addrLen > MaxAddressLength {
		return "", errors.ProtocolError{Message: "invalid address length"}
	}
	addrBuf := make([]byte, addrLen)
	_, err = io.ReadFull(r, addrBuf)
	if err != nil {
		return "", err
	}
	paddingLen, err := quicvarint.Read(bReader)
	if err != nil {
		return "", err
	}
	if paddingLen != 0 {
		return "", errors.ProtocolError{Message: "invalid padding length"}
	}
	return string(addrBuf), nil
}

func WriteTCPRequest(w io.Writer, addr string) error {
	addrLen := len(addr)
	if addrLen == 0 || addrLen > MaxAddressLength {
		return errors.ProtocolError{Message: "invalid address length"}
	}
	sz := int(quicvarint.Len(FrameTypeTCPRequest)) +
		int(quicvarint.Len(uint64(addrLen))) + addrLen +
		int(quicvarint.Len(0))
	buf := make([]byte, sz)
	i := varintPut(buf, FrameTypeTCPRequest)
	i += varintPut(buf[i:], uint64(addrLen))
	i += copy(buf[i:], addr)
	varintPut(buf[i:], 0)
	return writeFull(w, buf)
}

// TCPResponse format:
// Status (byte, 0=ok, 1=error)
// Message length (QUIC varint)
// Message (bytes)
// Padding length (QUIC varint, always 0)
//
// There is no padding. The trailing padding length is kept for framing
// compatibility, but the writer always emits 0 and the reader rejects any
// non-zero value.

func ReadTCPResponse(r io.Reader) (bool, string, error) {
	var status [1]byte
	if _, err := io.ReadFull(r, status[:]); err != nil {
		return false, "", err
	}
	if status[0] != 0 && status[0] != 1 {
		return false, "", errors.ProtocolError{Message: "invalid status"}
	}
	bReader := quicvarint.NewReader(r)
	msgLen, err := quicvarint.Read(bReader)
	if err != nil {
		return false, "", err
	}
	if msgLen > MaxMessageLength {
		return false, "", errors.ProtocolError{Message: "invalid message length"}
	}
	var msgBuf []byte
	// No message is fine
	if msgLen > 0 {
		msgBuf = make([]byte, msgLen)
		_, err = io.ReadFull(r, msgBuf)
		if err != nil {
			return false, "", err
		}
	}
	paddingLen, err := quicvarint.Read(bReader)
	if err != nil {
		return false, "", err
	}
	if paddingLen != 0 {
		return false, "", errors.ProtocolError{Message: "invalid padding length"}
	}
	return status[0] == 0, string(msgBuf), nil
}

func WriteTCPResponse(w io.Writer, ok bool, msg string) error {
	msgLen := len(msg)
	if msgLen > MaxMessageLength {
		return errors.ProtocolError{Message: "invalid message length"}
	}
	sz := 1 + int(quicvarint.Len(uint64(msgLen))) + msgLen +
		int(quicvarint.Len(0))
	buf := make([]byte, sz)
	if ok {
		buf[0] = 0
	} else {
		buf[0] = 1
	}
	i := varintPut(buf[1:], uint64(msgLen))
	i += copy(buf[1+i:], msg)
	varintPut(buf[1+i:], 0)
	return writeFull(w, buf)
}

// writeFull writes the whole buffer and reports io.ErrShortWrite if the
// underlying writer accepted only part of it.
func writeFull(w io.Writer, buf []byte) error {
	n, err := w.Write(buf)
	if err != nil {
		return err
	}
	if n != len(buf) {
		return io.ErrShortWrite
	}
	return nil
}

// varintPut is like quicvarint.Append, but instead of appending to a slice,
// it writes to a fixed-size buffer. Returns the number of bytes written.
func varintPut(b []byte, i uint64) int {
	if i <= maxVarInt1 {
		b[0] = uint8(i)
		return 1
	}
	if i <= maxVarInt2 {
		b[0] = uint8(i>>8) | 0x40
		b[1] = uint8(i)
		return 2
	}
	if i <= maxVarInt4 {
		b[0] = uint8(i>>24) | 0x80
		b[1] = uint8(i >> 16)
		b[2] = uint8(i >> 8)
		b[3] = uint8(i)
		return 4
	}
	if i <= maxVarInt8 {
		b[0] = uint8(i>>56) | 0xc0
		b[1] = uint8(i >> 48)
		b[2] = uint8(i >> 40)
		b[3] = uint8(i >> 32)
		b[4] = uint8(i >> 24)
		b[5] = uint8(i >> 16)
		b[6] = uint8(i >> 8)
		b[7] = uint8(i)
		return 8
	}
	panic(fmt.Sprintf("%#x doesn't fit into 62 bits", i))
}
