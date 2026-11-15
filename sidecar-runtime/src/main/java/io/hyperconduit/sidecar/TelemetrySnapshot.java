package io.hyperconduit.sidecar;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable telemetry view produced from one sidecar {@code stats} event.
 *
 * <p>{@link #available()} is time-based: a snapshot older than 3.5 seconds is
 * no longer available. {@link SidecarProcess#telemetry()} also checks that the
 * process is still opted in, ready, and alive before returning a sample.</p>
 */
public record TelemetrySnapshot(
        long sequence,
        long elapsedNanos,
        boolean truncated,
        long capturedAtNanos,
        Map<Long, Connection> connections,
        List<Bridge> bindings) {

    /** Samples older than this are never presented as live telemetry. */
    public static final long STALE_NANOS = 3_500_000_000L;

    public TelemetrySnapshot {
        connections = Map.copyOf(connections);
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
    }

    /** True only while the sample age is non-negative and within the limit. */
    public boolean available() {
        long age = System.nanoTime() - capturedAtNanos;
        return age >= 0 && age <= STALE_NANOS;
    }

    /**
     * Finds the single active bridge whose local TCP endpoint equals
     * {@code endpoint}. The match compares the full literal address and port;
     * it never falls back to a public-IP-only guess. Returns {@code null} when
     * the endpoint is absent or unresolved.
     */
    public Bridge bridgeFor(InetSocketAddress endpoint) {
        if (endpoint == null || endpoint.isUnresolved()) {
            return null;
        }
        for (Bridge bridge : bindings) {
            if (EndpointText.matches(bridge.localEndpoint(), endpoint)) {
                return bridge;
            }
        }
        return null;
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

    /**
     * One active TCP bridge stream. {@code localEndpoint} is the exact source
     * socket the sidecar used to reach the configured target, which is also the
     * peer address the proxy accepts. {@code peerEndpoint} is the QUIC peer and
     * is shown as the tunnel source.
     */
    public record Bridge(
            long connectionId,
            long streamId,
            InetSocketAddress localEndpoint,
            InetSocketAddress peerEndpoint) {

        public Bridge {
            Objects.requireNonNull(localEndpoint, "localEndpoint");
            Objects.requireNonNull(peerEndpoint, "peerEndpoint");
        }

        /** Display form of the QUIC peer; never used for association. */
        public String peerAddress() {
            return EndpointText.format(peerEndpoint);
        }
    }
}