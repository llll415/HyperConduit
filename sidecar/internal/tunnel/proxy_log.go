package tunnel

import (
	"encoding/json"
	"os"
	"sync"
	"time"
)

// Only bounded, typed fields cross the process boundary; raw datagrams never do.
type proxyDiagnostic struct {
	Version    int    `json:"version"`
	Event      string `json:"event"`
	Reason     string `json:"reason"`
	Source     string `json:"source"`
	Client     string `json:"client,omitempty"`
	Length     int    `json:"length"`
	Suppressed uint64 `json:"suppressed"`
}

type proxyLogLimiter struct {
	mu         sync.Mutex
	next       time.Time
	suppressed uint64
}

func (l *proxyLogLimiter) rejected(reason, source, client string, length int) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	if now.Before(l.next) {
		l.suppressed++
		return
	}
	_ = json.NewEncoder(os.Stderr).Encode(proxyDiagnostic{1, "proxy_log", reason, source, client, length, l.suppressed})
	l.suppressed = 0
	l.next = now.Add(time.Second)
}
