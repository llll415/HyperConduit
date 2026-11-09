package io.hyperconduit.sidecar;

import java.util.Map;

/**
 * Immutable telemetry view produced from one sidecar {@code stats} event.
 *
 * <p>{@link #available()} is time-based: a snapshot older than 3.5 seconds is
 * no longer available. {@link SidecarProcess#telemetry()} also checks that the
 * process is still opted in, ready, and alive before returning a sample.
 */
public record TelemetrySnapshot(
        long sequence,
        long elapsedNanos,
        boolean truncated,
        long capturedAtNanos,
        Map<Long, Connection> connections) {

    /** Samples older than this are never presented as live telemetry. */
    public static final long STALE_NANOS = 3_500_000_000L;

    public TelemetrySnapshot {
        connections = Map.copyOf(connections);
    }

    /** True only while the sample age is non-negative and within the limit. */
    public boolean available() {
        long age = System.nanoTime() - capturedAtNanos;
        return age >= 0 && age <= STALE_NANOS;
    }

    /**
     * One observed QUIC connection. Congestion describes only the local sender.
     * A zero {@code smoothedRttNanos} means the transport has not measured an
     * RTT yet. When {@code ratesAvailable} is false the two rates are
     * placeholders and must not be shown as real zero throughput.
     */
    public record Connection(
            long id,
            String congestion,
            long txTargetBytesPerSecond,
            long smoothedRttNanos,
            long bytesSent,
            long bytesReceived,
            boolean ratesAvailable,
            double sentBytesPerSecond,
            double receivedBytesPerSecond) {}
}