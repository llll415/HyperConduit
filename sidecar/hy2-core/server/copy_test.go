package server

import (
	"bytes"
	"errors"
	"io"
	"net"
	"testing"
	"time"
)

type shortWriter struct{}

func (shortWriter) Write(p []byte) (int, error) { return len(p) - 1, nil }

func TestCopyBufferLogRejectsShortWrites(t *testing.T) {
	err := copyBufferLog(shortWriter{}, bytes.NewReader([]byte("payload")), func(uint64) bool { return true })
	if !errors.Is(err, io.ErrShortWrite) {
		t.Fatalf("got %v, want short write", err)
	}
}

func TestCopyTwoWayJoinsBothLoops(t *testing.T) {
	for _, logged := range []bool{false, true} {
		t.Run(map[bool]string{false: "plain", true: "logged"}[logged], func(t *testing.T) {
			a, peerA := net.Pipe()
			b, peerB := net.Pipe()
			defer peerA.Close()
			defer peerB.Close()
			done := make(chan error, 1)
			go func() {
				if logged {
					done <- copyTwoWayEx("test", a, b, allowTraffic{}, &StreamStats{})
				} else {
					done <- copyTwoWay(a, b)
				}
			}()
			_ = peerA.Close()
			select {
			case <-done:
			case <-time.After(2 * time.Second):
				t.Fatal("copy loops did not stop")
			}
			_ = peerB.SetReadDeadline(time.Now().Add(time.Second))
			if _, err := peerB.Read(make([]byte, 1)); err == nil {
				t.Fatal("other endpoint remained open")
			}
		})
	}
}

type allowTraffic struct{}

func (allowTraffic) LogTraffic(string, uint64, uint64) bool { return true }
func (allowTraffic) LogOnlineState(string, bool)            {}
func (allowTraffic) TraceStream(HyStream, *StreamStats)     {}
func (allowTraffic) UntraceStream(HyStream)                 {}
