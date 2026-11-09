package protocol

import (
	"bytes"
	"io"
	"strings"
	"testing"

	"github.com/apernet/quic-go/quicvarint"
)

func TestReadTCPRequest(t *testing.T) {
	tests := []struct {
		name    string
		data    []byte
		want    string
		wantErr bool
	}{
		{
			name:    "normal no padding",
			data:    []byte("\x0egoogle.com:443\x00"),
			want:    "google.com:443",
			wantErr: false,
		},
		{
			name:    "padding rejected",
			data:    []byte("\x0bholy.cc:443\x02gg"),
			want:    "",
			wantErr: true,
		},
		{
			name:    "zero address length",
			data:    []byte("\x00\x00"),
			want:    "",
			wantErr: true,
		},
		{
			name:    "incomplete 1",
			data:    []byte("\x0bhoho"),
			want:    "",
			wantErr: true,
		},
		{
			name:    "incomplete 2",
			data:    []byte("\x0bholy.cc:443\x05x"),
			want:    "",
			wantErr: true,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r := bytes.NewReader(tt.data)
			got, err := ReadTCPRequest(r)
			if (err != nil) != tt.wantErr {
				t.Errorf("ReadTCPRequest() error = %v, wantErr %v", err, tt.wantErr)
				return
			}
			if got != tt.want {
				t.Errorf("ReadTCPRequest() got = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestWriteTCPRequest(t *testing.T) {
	tests := []struct {
		name    string
		addr    string
		wantW   string
		wantErr bool
	}{
		{
			name:    "normal 1",
			addr:    "google.com:443",
			wantW:   "\x44\x01\x0egoogle.com:443\x00",
			wantErr: false,
		},
		{
			name:    "normal 2",
			addr:    "client-api.arkoselabs.com:8080",
			wantW:   "\x44\x01\x1eclient-api.arkoselabs.com:8080\x00",
			wantErr: false,
		},
		{
			name:    "empty rejected",
			addr:    "",
			wantW:   "",
			wantErr: true,
		},
		{
			name:    "oversized address rejected",
			addr:    strings.Repeat("a", MaxAddressLength+1),
			wantW:   "",
			wantErr: true,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			w := &bytes.Buffer{}
			err := WriteTCPRequest(w, tt.addr)
			if (err != nil) != tt.wantErr {
				t.Errorf("WriteTCPRequest() error = %v, wantErr %v", err, tt.wantErr)
				return
			}
			if tt.wantErr {
				return
			}
			if gotW := w.String(); gotW != tt.wantW {
				t.Errorf("WriteTCPRequest() gotW = %q, want %q", gotW, tt.wantW)
			}
			// The trailing padding length must be zero and no padding bytes follow.
			if !strings.HasSuffix(tt.wantW, "\x00") {
				t.Errorf("expected trailing zero padding length, got %q", tt.wantW)
			}
		})
	}
}

func TestReadTCPResponse(t *testing.T) {
	tests := []struct {
		name    string
		data    []byte
		want    bool
		want1   string
		wantErr bool
	}{
		{
			name:    "normal ok no padding",
			data:    []byte("\x00\x0bhello world\x00"),
			want:    true,
			want1:   "hello world",
			wantErr: false,
		},
		{
			name:    "normal error no padding",
			data:    []byte("\x01\x06stop!!\x00"),
			want:    false,
			want1:   "stop!!",
			wantErr: false,
		},
		{
			name:    "normal ok no message",
			data:    []byte("\x00\x00\x00"),
			want:    true,
			want1:   "",
			wantErr: false,
		},
		{
			name:    "padding rejected",
			data:    []byte("\x01\x06stop!!\x05xxxxx"),
			want1:   "",
			wantErr: true,
		},
		{
			name:    "no message with padding rejected",
			data:    []byte("\x01\x00\x05xxxxx"),
			want1:   "",
			wantErr: true,
		},
		{
			name:    "invalid status rejected",
			data:    []byte("\x02\x00\x00"),
			want1:   "",
			wantErr: true,
		},
		{
			name:    "incomplete 1",
			data:    []byte("\x00\x0bhoho"),
			want1:   "",
			wantErr: true,
		},
		{
			name:    "incomplete 2",
			data:    []byte("\x01\x05jesus\x05x"),
			want1:   "",
			wantErr: true,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r := bytes.NewReader(tt.data)
			got, got1, err := ReadTCPResponse(r)
			if (err != nil) != tt.wantErr {
				t.Errorf("ReadTCPResponse() error = %v, wantErr %v", err, tt.wantErr)
				return
			}
			if got != tt.want {
				t.Errorf("ReadTCPResponse() got = %v, want %v", got, tt.want)
			}
			if got1 != tt.want1 {
				t.Errorf("ReadTCPResponse() got1 = %v, want %v", got1, tt.want1)
			}
		})
	}
}

func TestWriteTCPResponse(t *testing.T) {
	type args struct {
		ok  bool
		msg string
	}
	tests := []struct {
		name    string
		args    args
		wantW   string
		wantErr bool
	}{
		{
			name:    "normal ok",
			args:    args{ok: true, msg: "hello world"},
			wantW:   "\x00\x0bhello world\x00",
			wantErr: false,
		},
		{
			name:    "normal error",
			args:    args{ok: false, msg: "stop!!"},
			wantW:   "\x01\x06stop!!\x00",
			wantErr: false,
		},
		{
			name:    "empty",
			args:    args{ok: true, msg: ""},
			wantW:   "\x00\x00\x00",
			wantErr: false,
		},
		{
			name:    "oversized message rejected",
			args:    args{ok: true, msg: strings.Repeat("a", MaxMessageLength+1)},
			wantW:   "",
			wantErr: true,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			w := &bytes.Buffer{}
			err := WriteTCPResponse(w, tt.args.ok, tt.args.msg)
			if (err != nil) != tt.wantErr {
				t.Errorf("WriteTCPResponse() error = %v, wantErr %v", err, tt.wantErr)
				return
			}
			if tt.wantErr {
				return
			}
			if gotW := w.String(); gotW != tt.wantW {
				t.Errorf("WriteTCPResponse() gotW = %q, want %q", gotW, tt.wantW)
			}
			if !strings.HasSuffix(tt.wantW, "\x00") {
				t.Errorf("expected trailing zero padding length, got %q", tt.wantW)
			}
		})
	}
}

// shortWriter accepts only part of every write.
type shortWriter struct{}

func (shortWriter) Write(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	return len(p) - 1, nil
}

func TestWriteShortWrite(t *testing.T) {
	t.Run("request", func(t *testing.T) {
		if err := WriteTCPRequest(shortWriter{}, "google.com:443"); err != io.ErrShortWrite {
			t.Errorf("WriteTCPRequest() error = %v, want %v", err, io.ErrShortWrite)
		}
	})
	t.Run("response", func(t *testing.T) {
		if err := WriteTCPResponse(shortWriter{}, true, "hello world"); err != io.ErrShortWrite {
			t.Errorf("WriteTCPResponse() error = %v, want %v", err, io.ErrShortWrite)
		}
	})
}

func TestVarintPut(t *testing.T) {
	values := []uint64{
		0,
		maxVarInt1,
		maxVarInt1 + 1,
		maxVarInt2,
		maxVarInt2 + 1,
		maxVarInt4,
		maxVarInt4 + 1,
		maxVarInt8,
	}
	for _, v := range values {
		want := quicvarint.Append(nil, v)
		buf := make([]byte, len(want))
		n := varintPut(buf, v)
		if n != len(want) {
			t.Errorf("varintPut(%d) wrote %d bytes, want %d", v, n, len(want))
			continue
		}
		if !bytes.Equal(buf[:n], want) {
			t.Errorf("varintPut(%d) = %v, want %v", v, buf[:n], want)
		}
	}
}
