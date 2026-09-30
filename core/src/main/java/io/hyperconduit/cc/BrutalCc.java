package io.hyperconduit.cc;

import java.util.List;

import static io.hyperconduit.cc.Constants.INITIAL_PACKET_SIZE;

/**
 * Hysteria 2's Brutal congestion control, a faithful port of
 * {@code hysteria/core/internal/congestion/brutal/brutal.go}.
 *
 * <p>Brutal is not a bandwidth estimator and does not probe for capacity. It is told a target
 * rate ({@code bps}, the local endpoint's <em>send</em> rate) and holds it regardless of loss:
 *
 * <pre>
 *   cwnd      = bps * sRTT * 2 / ackRate
 *   paceRate  = bps / ackRate
 *   ackRate   = acked / (acked + lost)   over the last 5 one-second slots
 * </pre>
 *
 * <p>Dividing by {@code ackRate} is the loss-compensation term: if only 80% of packets get through,
 * send 25% faster so the <em>delivered</em> rate still equals {@code bps}. {@code ackRate} is
 * floored at 0.8 and forced to 1 until 50 samples have accumulated, so it can never blow the rate
 * up without bound.
 *
 * <p>This is the right shape for the loss this project targets. Deliberate QoS dropping on
 * domestic interconnects is a <em>false</em> congestion signal: the path has capacity, a policy is
 * removing packets. A controller that halves its window on loss is reacting correctly to a lie.
 * Brutal refuses to believe it, which is why the same link yields ~12x more throughput.
 *
 * <p>Consequence for configuration: set {@code bps} from the <em>subscribed</em> bandwidth of the
 * receiving side, not from a measurement of the path.
 */
public final class BrutalCc implements CongestionController {

    /** Slot index is derived from whole seconds, so this is also the sampling window in seconds. */
    static final int PKT_INFO_SLOT_COUNT = 5;
    static final int MIN_SAMPLE_COUNT = 50;
    static final double MIN_ACK_RATE = 0.8;
    static final int CONGESTION_WINDOW_MULTIPLIER = 2;

    /** quic-go's stand-in window before any RTT sample exists. */
    private static final long CWND_WITHOUT_RTT = 10240;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final long bps;
    private final boolean disableLossCompensation;
    private final Pacer pacer;

    private final long[] slotTimestampSec = new long[PKT_INFO_SLOT_COUNT];
    private final long[] slotAckCount = new long[PKT_INFO_SLOT_COUNT];
    private final long[] slotLossCount = new long[PKT_INFO_SLOT_COUNT];

    private RttStats rttStats;
    private int maxDatagramSize = INITIAL_PACKET_SIZE;
    private double ackRate = 1.0;

    /**
     * @param bps                     target send rate in bytes per second
     * @param disableLossCompensation  force {@code ackRate = 1}, i.e. pace at exactly {@code bps}
     *                                 and accept a lower delivered rate under loss
     */
    public BrutalCc(long bps, boolean disableLossCompensation) {
        if (bps <= 0) {
            throw new IllegalArgumentException("bps must be positive, got " + bps);
        }
        this.bps = bps;
        this.disableLossCompensation = disableLossCompensation;
        this.pacer = new Pacer(this::pacingRateBytesPerSec);
    }

    @Override
    public void setRttStats(RttStats rttStats) {
        this.rttStats = rttStats;
    }

    @Override
    public void setMaxDatagramSize(int size) {
        this.maxDatagramSize = size;
        this.pacer.setMaxDatagramSize(size);
    }

    @Override
    public long nanosUntilSend(long nowNanos) {
        return pacer.nanosUntilSend(nowNanos);
    }

    @Override
    public boolean hasPacingBudget(long nowNanos) {
        return pacer.hasBudgetForOnePacket(nowNanos);
    }

    @Override
    public boolean canSend(long bytesInFlight) {
        return bytesInFlight <= congestionWindow();
    }

    @Override
    public long congestionWindow() {
        if (rttStats == null) {
            return CWND_WITHOUT_RTT;
        }
        long rttNanos = rttStats.smoothedRttNanos();
        if (rttNanos <= 0) {
            return CWND_WITHOUT_RTT;
        }
        long cwnd = (long) ((double) bps * ((double) rttNanos / NANOS_PER_SECOND)
                * CONGESTION_WINDOW_MULTIPLIER / ackRate);
        return Math.max(cwnd, maxDatagramSize);
    }

    @Override
    public void onPacketSent(long sentTimeNanos, long bytesInFlight, long packetNumber, long bytes,
                             boolean ackEliciting) {
        pacer.sentPacket(sentTimeNanos, bytes);
    }

    @Override
    public void onCongestionEvent(long priorInFlight, long eventTimeNanos,
                                  List<AckedPacket> acked, List<LostPacket> lost) {
        // floorDiv/floorMod as a pair: System.nanoTime()'s origin may be negative, and a truncated
        // timestamp combined with a floored slot index would corrupt the sampling window.
        long currentTimestampSec = Math.floorDiv(eventTimeNanos, NANOS_PER_SECOND);
        int slot = (int) Math.floorMod(currentTimestampSec, PKT_INFO_SLOT_COUNT);
        if (slotTimestampSec[slot] == currentTimestampSec) {
            slotAckCount[slot] += acked.size();
            slotLossCount[slot] += lost.size();
        } else {
            slotTimestampSec[slot] = currentTimestampSec;
            slotAckCount[slot] = acked.size();
            slotLossCount[slot] = lost.size();
        }
        updateAckRate(currentTimestampSec);
    }

    private void updateAckRate(long currentTimestampSec) {
        if (disableLossCompensation) {
            ackRate = 1;
            return;
        }
        long minTimestampSec = currentTimestampSec - PKT_INFO_SLOT_COUNT;
        long ackCount = 0;
        long lossCount = 0;
        for (int i = 0; i < PKT_INFO_SLOT_COUNT; i++) {
            if (slotTimestampSec[i] < minTimestampSec) {
                continue;
            }
            ackCount += slotAckCount[i];
            lossCount += slotLossCount[i];
        }
        if (ackCount + lossCount < MIN_SAMPLE_COUNT) {
            ackRate = 1;
            return;
        }
        double rate = (double) ackCount / (double) (ackCount + lossCount);
        ackRate = Math.max(rate, MIN_ACK_RATE);
    }

    /** Rate the pacer is currently targeting, i.e. {@code bps} inflated by loss compensation. */
    public long pacingRateBytesPerSec() {
        return (long) ((double) bps / ackRate);
    }

    public double ackRate() {
        return ackRate;
    }

    public long configuredBps() {
        return bps;
    }
}
