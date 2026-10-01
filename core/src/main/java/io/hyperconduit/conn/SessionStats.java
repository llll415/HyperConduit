package io.hyperconduit.conn;

/**
 * A snapshot of session counters, for a debug HUD or the measurement testbed. Everything is
 * cumulative except the RTT and window figures.
 */
public record SessionStats(
        SessionEngine.State state,
        long smoothedRttNanos,
        long minRttNanos,
        long bytesInFlight,
        long congestionWindow,
        long packetsSent,
        long packetsReceived,
        long packetsLost,
        long packetsRetransmitted,
        long bytesSent,
        long bytesReceived,
        long bytesDelivered,
        long queuedToSendBytes,
        int ptoCount,
        RollingMetrics recent) {
}
