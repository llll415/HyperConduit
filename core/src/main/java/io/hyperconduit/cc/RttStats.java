package io.hyperconduit.cc;

import static io.hyperconduit.cc.Constants.DEFAULT_INITIAL_RTT_NANOS;
import static io.hyperconduit.cc.Constants.TIMER_GRANULARITY_NANOS;

/**
 * Round-trip time estimator, a port of quic-go's {@code internal/utils/rtt_stats.go}.
 *
 * <p>Not thread-safe: the owning session performs all congestion-control and RTT work under a
 * single lock. Reads for statistics must take that same lock.
 */
public final class RttStats {

    private static final double ALPHA = 0.125;
    private static final double BETA = 0.25;

    private boolean hasMeasurement;
    private long minRttNanos = DEFAULT_INITIAL_RTT_NANOS;
    private long latestRttNanos = DEFAULT_INITIAL_RTT_NANOS;
    private long smoothedRttNanos = DEFAULT_INITIAL_RTT_NANOS;
    private long meanDeviationNanos;
    private long maxAckDelayNanos;

    public long minRttNanos() {
        return minRttNanos;
    }

    public long latestRttNanos() {
        return latestRttNanos;
    }

    public long smoothedRttNanos() {
        return smoothedRttNanos;
    }

    public long meanDeviationNanos() {
        return meanDeviationNanos;
    }

    public long maxAckDelayNanos() {
        return maxAckDelayNanos;
    }

    public boolean hasMeasurement() {
        return hasMeasurement;
    }

    public void setMaxAckDelayNanos(long maxAckDelayNanos) {
        this.maxAckDelayNanos = maxAckDelayNanos;
    }

    /**
     * @param sendDeltaNanos time between sending the packet and receiving its ACK
     * @param ackDelayNanos  delay the peer reported between receiving and acknowledging; must
     *                       already be capped at {@link #maxAckDelayNanos()} by the caller
     */
    public void updateRtt(long sendDeltaNanos, long ackDelayNanos) {
        if (sendDeltaNanos <= 0) {
            return;
        }
        // minRTT uses the uncorrected sample: coarse client clocks plus a large ackDelay could
        // otherwise drive minRTT below the true path minimum.
        if (!hasMeasurement || minRttNanos > sendDeltaNanos) {
            minRttNanos = sendDeltaNanos;
        }
        long sample = sendDeltaNanos;
        if (sample - minRttNanos >= ackDelayNanos) {
            sample -= ackDelayNanos;
        }
        latestRttNanos = sample;

        if (!hasMeasurement) {
            hasMeasurement = true;
            smoothedRttNanos = sample;
            meanDeviationNanos = sample / 2;
            return;
        }
        meanDeviationNanos = (long) ((1 - BETA) * meanDeviationNanos + BETA * Math.abs(smoothedRttNanos - sample));
        smoothedRttNanos = (long) ((1 - ALPHA) * smoothedRttNanos + ALPHA * sample);
    }

    /** Probe timeout: {@code sRTT + max(4*rttVar, timerGranularity) [+ maxAckDelay]}. */
    public long ptoNanos(boolean includeMaxAckDelay) {
        if (!hasMeasurement) {
            return 2 * DEFAULT_INITIAL_RTT_NANOS;
        }
        long pto = smoothedRttNanos + Math.max(4 * meanDeviationNanos, TIMER_GRANULARITY_NANOS);
        if (includeMaxAckDelay) {
            pto += maxAckDelayNanos;
        }
        return pto;
    }

    public void resetForPathMigration() {
        hasMeasurement = false;
        minRttNanos = DEFAULT_INITIAL_RTT_NANOS;
        latestRttNanos = DEFAULT_INITIAL_RTT_NANOS;
        smoothedRttNanos = DEFAULT_INITIAL_RTT_NANOS;
        meanDeviationNanos = 0;
    }
}
