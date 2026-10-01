package io.hyperconduit.conn;

/** Recent 10-second network quality snapshot for a single tunnel direction. */
public record RollingMetrics(
        long txBytesPerSecond,
        long rxBytesPerSecond,
        double lossRate,
        double retransmitsPerSecond,
        long p95RttNanos,
        long jitterNanos) {

    public static final RollingMetrics EMPTY = new RollingMetrics(0, 0, 0, 0, 0, 0);
}
