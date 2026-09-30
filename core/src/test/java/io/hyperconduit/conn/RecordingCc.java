package io.hyperconduit.conn;

import io.hyperconduit.cc.AckedPacket;
import io.hyperconduit.cc.CongestionController;
import io.hyperconduit.cc.Constants;
import io.hyperconduit.cc.LostPacket;
import io.hyperconduit.cc.RttStats;

import java.util.ArrayList;
import java.util.List;

/**
 * A congestion controller whose window and pacing verdicts are set directly by the test, so
 * {@link SentPacketHandler}'s send gating can be exercised without depending on any real algorithm.
 * Also records what the handler fed it, which is how the Brutal ack/loss accounting is checked.
 */
final class RecordingCc implements CongestionController {

    long congestionWindow = Long.MAX_VALUE / 4;
    boolean pacingBudget = true;
    long pacingDelayNanos;
    int maxDatagramSize = Constants.INITIAL_PACKET_SIZE;

    final List<long[]> sentPackets = new ArrayList<>();
    final List<long[]> congestionEvents = new ArrayList<>();
    int ackedTotal;
    int lostTotal;
    long lastPriorInFlight = -1;
    long lastEventTimeNanos = -1;

    private RttStats rttStats;

    @Override
    public void setRttStats(RttStats rttStats) {
        this.rttStats = rttStats;
    }

    RttStats rttStats() {
        return rttStats;
    }

    @Override
    public long nanosUntilSend(long nowNanos) {
        return pacingDelayNanos;
    }

    @Override
    public boolean hasPacingBudget(long nowNanos) {
        return pacingBudget;
    }

    @Override
    public boolean canSend(long bytesInFlight) {
        return bytesInFlight <= congestionWindow;
    }

    @Override
    public long congestionWindow() {
        return congestionWindow;
    }

    @Override
    public void onPacketSent(long sentTimeNanos, long bytesInFlight, long packetNumber, long bytes,
                             boolean ackEliciting) {
        sentPackets.add(new long[]{sentTimeNanos, packetNumber, bytes, bytesInFlight, ackEliciting ? 1 : 0});
    }

    @Override
    public void onCongestionEvent(long priorInFlight, long eventTimeNanos,
                                  List<AckedPacket> acked, List<LostPacket> lost) {
        congestionEvents.add(new long[]{priorInFlight, eventTimeNanos, acked.size(), lost.size()});
        ackedTotal += acked.size();
        lostTotal += lost.size();
        lastPriorInFlight = priorInFlight;
        lastEventTimeNanos = eventTimeNanos;
    }

    @Override
    public void setMaxDatagramSize(int size) {
        this.maxDatagramSize = size;
    }
}
