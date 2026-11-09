package protocol

import (
	"net/http"
	"testing"
)

func TestAuthRequestHeaderRoundTrip(t *testing.T) {
	h := http.Header{}
	AuthRequestToHeader(h, AuthRequest{Auth: "secret-token", Rx: 12345})

	if got := h.Get(RequestHeaderAuth); got != "secret-token" {
		t.Errorf("RequestHeaderAuth = %q, want %q", got, "secret-token")
	}
	if got := h.Get(CommonHeaderCCRX); got != "12345" {
		t.Errorf("CommonHeaderCCRX = %q, want %q", got, "12345")
	}
	if got := h.Get("Hysteria-Padding"); got != "" {
		t.Errorf("Hysteria-Padding should not be set, got %q", got)
	}

	req := AuthRequestFromHeader(h)
	if req.Auth != "secret-token" {
		t.Errorf("AuthRequestFromHeader().Auth = %q, want %q", req.Auth, "secret-token")
	}
	if req.Rx != 12345 {
		t.Errorf("AuthRequestFromHeader().Rx = %d, want %d", req.Rx, 12345)
	}
}

func TestAuthResponseHeaderRoundTrip(t *testing.T) {
	t.Run("udp disabled and rx auto", func(t *testing.T) {
		h := http.Header{}
		AuthResponseToHeader(h, AuthResponse{UDPEnabled: false, RxAuto: true})

		if got := h.Get(ResponseHeaderUDPEnabled); got != "false" {
			t.Errorf("ResponseHeaderUDPEnabled = %q, want %q", got, "false")
		}
		if got := h.Get(CommonHeaderCCRX); got != "auto" {
			t.Errorf("CommonHeaderCCRX = %q, want %q", got, "auto")
		}
		if got := h.Get("Hysteria-Padding"); got != "" {
			t.Errorf("Hysteria-Padding should not be set, got %q", got)
		}

		resp := AuthResponseFromHeader(h)
		if resp.UDPEnabled {
			t.Error("AuthResponseFromHeader().UDPEnabled = true, want false")
		}
		if !resp.RxAuto {
			t.Error("AuthResponseFromHeader().RxAuto = false, want true")
		}
		if resp.Rx != 0 {
			t.Errorf("AuthResponseFromHeader().Rx = %d, want 0", resp.Rx)
		}
	})

	t.Run("udp disabled and numeric rx", func(t *testing.T) {
		h := http.Header{}
		AuthResponseToHeader(h, AuthResponse{UDPEnabled: false, Rx: 6789})

		if got := h.Get(ResponseHeaderUDPEnabled); got != "false" {
			t.Errorf("ResponseHeaderUDPEnabled = %q, want %q", got, "false")
		}
		if got := h.Get(CommonHeaderCCRX); got != "6789" {
			t.Errorf("CommonHeaderCCRX = %q, want %q", got, "6789")
		}
		if got := h.Get("Hysteria-Padding"); got != "" {
			t.Errorf("Hysteria-Padding should not be set, got %q", got)
		}

		resp := AuthResponseFromHeader(h)
		if resp.UDPEnabled {
			t.Error("AuthResponseFromHeader().UDPEnabled = true, want false")
		}
		if resp.RxAuto {
			t.Error("AuthResponseFromHeader().RxAuto = true, want false")
		}
		if resp.Rx != 6789 {
			t.Errorf("AuthResponseFromHeader().Rx = %d, want %d", resp.Rx, 6789)
		}
	})
}
