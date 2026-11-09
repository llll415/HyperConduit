package probe

import "testing"

func TestDialerCloseIsIdempotentAndFinal(t *testing.T) {
	d := &probeDialer{}
	d.close()
	d.close()
	if !d.closed || len(d.conns) != 0 || len(d.sockets) != 0 {
		t.Fatal("closed dialer retained resources")
	}
}
