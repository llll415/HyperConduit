package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"math"
	"sort"
	"strings"
	"time"

	"github.com/apernet/hysteria/core/v2/observe"

	"github.com/llll415/HyperConduit/sidecar/internal/tunnel"
)

const (
	telemetryInterval       = time.Second
	telemetryMaxConnections = 128
	telemetryMaxLineBytes   = 65536
)

// telemetryConnection is the fixed, bounded stats schema. It intentionally has
// no addresses, binding identifiers, credentials, or peer congestion guesses.
type telemetryConnection struct {
	ID                     uint64 `json:"id"`
	Congestion             string `json:"congestion"`
	TxTargetBytesPerSecond uint64 `json:"txTargetBytesPerSecond"`
	SmoothedRTTNanos       int64  `json:"smoothedRttNanos"`
	BytesSent              uint64 `json:"bytesSent"`
	BytesReceived          uint64 `json:"bytesReceived"`
}

type telemetryStats struct {
	Version      int                   `json:"version"`
	Event        string                `json:"event"`
	Sequence     int64                 `json:"sequence"`
	ElapsedNanos int64                 `json:"elapsedNanos"`
	Connections  []telemetryConnection `json:"connections"`
	Truncated    bool                  `json:"truncated"`
}

// streamTelemetry is the opt-in stats loop. It waits for observe (the closed
// signal), then writes one bounded stats line per second until the context ends
// or the endpoint exits. It runs in the same goroutine as the ready event so
// stdout has exactly one writer and events can never interleave.
//
// Writes are intentionally synchronous: a parent that stops draining stdout
// can block this loop. The Java supervisor always drains stdout and enforces a
// bounded kill, so blocking here cannot leak the transport forever.
func streamTelemetry(ctx context.Context, endpoint *tunnel.Endpoint, endpointDone <-chan error, observe <-chan struct{}, stdout io.Writer) error {
	select {
	case <-observe:
	case <-ctx.Done():
		// The deferred endpoint.Close in runEndpointObserved stops the
		// transport; the waiter goroutine drains into a buffered channel.
		return nil
	case err := <-endpointDone:
		return err
	}

	ticker := time.NewTicker(telemetryInterval)
	defer ticker.Stop()
	// time.Now carries a monotonic reading, so time.Since cannot jump with
	// wall-clock adjustments. Sampling starts at the observe opt-in.
	start := time.Now()
	var sequence int64
	for {
		select {
		case <-ctx.Done():
			return nil
		case err := <-endpointDone:
			return err
		case <-ticker.C:
		}
		sequence++
		elapsed := time.Since(start).Nanoseconds()
		if elapsed <= 0 {
			// The schema requires a positive duration; a zero reading is
			// only possible on a clock without monotonic support.
			elapsed = 1
		}
		line, err := encodeTelemetry(buildTelemetry(sequence, elapsed, endpoint.Status().Connections))
		if err != nil {
			return err
		}
		if _, err := stdout.Write(line); err != nil {
			return err
		}
	}
}

// buildTelemetry converts real observed connection snapshots into the fixed
// wire schema. Only established connections with a known local congestion
// controller are reported; closed or unknown values are dropped instead of
// being guessed. Connections are sorted by ID before the 128-entry cap so the
// output is stable.
func buildTelemetry(sequence, elapsedNanos int64, snapshots []observe.Snapshot) telemetryStats {
	connections := make([]telemetryConnection, 0, len(snapshots))
	for _, snapshot := range snapshots {
		if snapshot.State != "established" || snapshot.ID == 0 || snapshot.ID > math.MaxInt64 {
			continue
		}
		congestion := strings.ToLower(snapshot.Congestion.Type)
		switch congestion {
		case "bbr", "reno", "brutal":
		default:
			// Configuration validation normally makes this unreachable. Drop
			// the entry rather than emit a value outside the fixed schema.
			continue
		}
		connections = append(connections, telemetryConnection{
			ID:                     snapshot.ID,
			Congestion:             congestion,
			TxTargetBytesPerSecond: boundedUint64(snapshot.Congestion.TxBytesPerSecond),
			SmoothedRTTNanos:       maxInt64(snapshot.SmoothedRTTNanos, 0),
			BytesSent:              boundedUint64(snapshot.BytesSent),
			BytesReceived:          boundedUint64(snapshot.BytesReceived),
		})
	}
	sort.Slice(connections, func(i, j int) bool { return connections[i].ID < connections[j].ID })
	truncated := len(connections) > telemetryMaxConnections
	if truncated {
		connections = connections[:telemetryMaxConnections]
	}
	return telemetryStats{
		Version:      1,
		Event:        "stats",
		Sequence:     sequence,
		ElapsedNanos: elapsedNanos,
		Connections:  connections,
		Truncated:    truncated,
	}
}

// encodeTelemetry enforces the 64 KiB line bound locally. The parent never has
// to kill the game tunnel because an unexpectedly large stats frame appeared.
func encodeTelemetry(event telemetryStats) ([]byte, error) {
	for {
		var buffer bytes.Buffer
		encoder := json.NewEncoder(&buffer)
		encoder.SetEscapeHTML(false)
		if err := encoder.Encode(event); err != nil {
			return nil, err
		}
		if buffer.Len() <= telemetryMaxLineBytes {
			return buffer.Bytes(), nil
		}
		if len(event.Connections) == 0 {
			return nil, errors.New("telemetry line exceeds limit")
		}
		event.Connections = event.Connections[:len(event.Connections)-1]
		event.Truncated = true
	}
}

func maxInt64(value, floor int64) int64 {
	if value < floor {
		return floor
	}
	return value
}

// boundedUint64 keeps a counter representable as a signed 64-bit integer. The
// wire schema is unsigned, but the Java consumer stores it in a long; real QUIC
// counters never approach this bound, so clamping only protects the frame.
func boundedUint64(value uint64) uint64 {
	const maxSigned = uint64(math.MaxInt64)
	if value > maxSigned {
		return maxSigned
	}
	return value
}
