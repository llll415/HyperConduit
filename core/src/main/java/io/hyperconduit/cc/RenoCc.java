package io.hyperconduit.cc;

import java.util.List;

import static io.hyperconduit.cc.Constants.INITIAL_PACKET_SIZE;

/**
 * TCP-Reno-style AIMD with <b>no pacer</b>, used purely as a measurement baseline.
 *
 * <p>It exists so the testbed can answer the question this project is built on: how much of the
 * win comes from Brutal's refusal to halve its window, and how much from pacing? Both are absent
 * here, so this controller reproduces the bursty, loss-triggered-backoff behaviour of kernel TCP
 * over the same lossy path. Do not ship it as a default.
 */
public final class RenoCc implements CongestionController {

    private static final int INITIAL_WINDOW_PACKETS = 10;
    private static final int MIN_WINDOW_PACKETS = 2;
    /** quic-go's {@code MaxCongestionWindowPackets}. */
    private static final long MAX_WINDOW_PACKETS = 20000;

    private RttStats rttStats;
    private int maxDatagramSize = INITIAL_PACKET_SIZE;
    private long congestionWindow;
    private long slowStartThreshold;

    public RenoCc() {
        this.congestionWindow = (long) INITIAL_WINDOW_PACKETS * INITIAL_PACKET_SIZE;
        this.slowStartThreshold = MAX_WINDOW_PACKETS * INITIAL_PACKET_SIZE;
    }

    @Override
    public void setRttStats(RttStats rttStats) {
        this.rttStats = rttStats;
    }

    @Override
    public void setMaxDatagramSize(int size) {
        this.maxDatagramSize = size;
        this.congestionWindow = Math.max(congestionWindow, size);
    }

    @Override
    public long nanosUntilSend(long nowNanos) {
        return 0;
    }

    @Override
    public boolean hasPacingBudget(long nowNanos) {
        return true;
    }

    @Override
    public boolean canSend(long bytesInFlight) {
        return bytesInFlight < congestionWindow;
    }

    @Override
    public long congestionWindow() {
        return congestionWindow;
    }

    @Override
    public void onPacketSent(long sentTimeNanos, long bytesInFlight, long packetNumber, long bytes,
                             boolean ackEliciting) {
        // No pacer: the whole window may leave at once, which is the behaviour under test.
    }

    @Override
    public void onCongestionEvent(long priorInFlight, long eventTimeNanos,
                                  List<AckedPacket> acked, List<LostPacket> lost) {
        // Loss is applied before acknowledgements, matching the order quic-go drives its controllers in.
        if (!lost.isEmpty()) {
            long floor = (long) MIN_WINDOW_PACKETS * maxDatagramSize;
            slowStartThreshold = Math.max(congestionWindow / 2, floor);
            congestionWindow = slowStartThreshold;
        }
        if (acked.isEmpty()) {
            return;
        }
        long ackedBytes = 0;
        for (AckedPacket p : acked) {
            ackedBytes += p.bytesAcked();
        }
        if (congestionWindow < slowStartThreshold) {
            congestionWindow += ackedBytes;
        } else {
            // Roughly one MSS per RTT: each ACK adds MSS * ackedBytes / cwnd.
            congestionWindow += (long) maxDatagramSize * ackedBytes / congestionWindow;
        }
        congestionWindow = Math.min(congestionWindow, MAX_WINDOW_PACKETS * maxDatagramSize);
    }
}
