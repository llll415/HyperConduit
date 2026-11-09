package main

import (
	"math"
	"testing"

	"github.com/apernet/hysteria/core/v2/observe"
)

func TestTelemetryDropsUnrepresentableConnectionIdentity(t *testing.T) {
	var snapshots []observe.Snapshot
	for _, id := range []uint64{0, 1, math.MaxUint64} {
		snapshots = append(snapshots, observe.Snapshot{ID: id, State: "established", Congestion: observe.Congestion{Type: "bbr"}})
	}
	frame := buildTelemetry(1, 1, snapshots)
	if len(frame.Connections) != 1 || frame.Connections[0].ID != 1 {
		t.Fatal("telemetry must preserve exact, representable connection identities")
	}
}
