package io.hyperconduit.cc;

import java.util.function.LongSupplier;

import static io.hyperconduit.cc.Constants.INITIAL_PACKET_SIZE;
import static io.hyperconduit.cc.Constants.MAX_BURST_PACKETS;
import static io.hyperconduit.cc.Constants.MAX_BURST_PACING_DELAY_MULTIPLIER;
import static io.hyperconduit.cc.Constants.MIN_PACING_DELAY_NANOS;

/**
 * Token-bucket pacer, a port of Hysteria's {@code internal/congestion/common/pacer.go}.
 *
 * <p>Pacing is the half of Brutal that a cwnd-only controller cannot express: the window says how
 * much may be outstanding, the pacer says how fast it may leave. Without it, traffic goes out in
 * bursts and queueing delay spikes on exactly the lossy paths this project targets.
 */
public final class Pacer {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    /** Matches quic-go's {@code if budget < 0} guard against 64-bit overflow. */
    private static final long BUDGET_SATURATION = (1L << 62) - 1;

    private final LongSupplier bandwidthBytesPerSec;

    private long budgetAtLastSent = (long) MAX_BURST_PACKETS * INITIAL_PACKET_SIZE;
    private int maxDatagramSize = INITIAL_PACKET_SIZE;
    private long lastSentTimeNanos;
    private boolean hasSent;

    public Pacer(LongSupplier bandwidthBytesPerSec) {
        this.bandwidthBytesPerSec = bandwidthBytesPerSec;
    }

    public void setMaxDatagramSize(int maxDatagramSize) {
        this.maxDatagramSize = maxDatagramSize;
    }

    public int maxDatagramSize() {
        return maxDatagramSize;
    }

    public void sentPacket(long sendTimeNanos, long size) {
        long budget = budget(sendTimeNanos);
        budgetAtLastSent = size > budget ? 0 : budget - size;
        lastSentTimeNanos = sendTimeNanos;
        hasSent = true;
    }

    public long budget(long nowNanos) {
        long maxBurst = maxBurstSize();
        if (!hasSent) {
            return maxBurst;
        }
        long bandwidth = bandwidthBytesPerSec.getAsLong();
        // A non-positive rate means "stop accruing", not "budget is zero": returning 0 here would
        // break the invariant budget(now) >= budgetAtLastSent that nanosUntilSend relies on, and
        // the two would disagree about whether a packet may go out now.
        long accrued = bandwidth > 0
                ? mulDivSaturating(bandwidth, Math.max(0, nowNanos - lastSentTimeNanos), NANOS_PER_SECOND)
                : 0;
        long budget = budgetAtLastSent >= BUDGET_SATURATION - accrued
                ? BUDGET_SATURATION
                : budgetAtLastSent + accrued;
        return Math.min(maxBurst, budget);
    }

    /** Whether at least one full datagram's worth of budget is available right now. */
    public boolean hasBudgetForOnePacket(long nowNanos) {
        return budget(nowNanos) >= maxDatagramSize;
    }

    /** Nanoseconds to wait before a packet of {@link #maxDatagramSize()} may be sent. */
    public long nanosUntilSend(long nowNanos) {
        if (budgetAtLastSent >= maxDatagramSize || !hasSent) {
            return 0;
        }
        long bandwidth = bandwidthBytesPerSec.getAsLong();
        if (bandwidth <= 0) {
            return Long.MAX_VALUE;
        }
        long neededNanos = (long) (maxDatagramSize - budgetAtLastSent) * NANOS_PER_SECOND;
        // Round up, otherwise the timer can fire with budget still fractionally short of a packet.
        long delay = neededNanos / bandwidth;
        if (neededNanos % bandwidth > 0) {
            delay++;
        }
        long deadline = lastSentTimeNanos + Math.max(MIN_PACING_DELAY_NANOS, delay);
        return Math.max(0, deadline - nowNanos);
    }

    private long maxBurstSize() {
        long bandwidth = Math.max(0, bandwidthBytesPerSec.getAsLong());
        long timeBased = mulDivSaturating(
                (long) MAX_BURST_PACING_DELAY_MULTIPLIER * MIN_PACING_DELAY_NANOS, bandwidth, NANOS_PER_SECOND);
        long packetBased = (long) MAX_BURST_PACKETS * maxDatagramSize;
        return Math.max(timeBased, packetBased);
    }

    /** {@code a * b / divisor}, saturating instead of overflowing. */
    private static long mulDivSaturating(long a, long b, long divisor) {
        if (a == 0 || b == 0) {
            return 0;
        }
        if (a > Long.MAX_VALUE / b) {
            return BUDGET_SATURATION;
        }
        return a * b / divisor;
    }
}
