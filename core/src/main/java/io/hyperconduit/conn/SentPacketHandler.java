package io.hyperconduit.conn;

import io.hyperconduit.ProtocolException;
import io.hyperconduit.cc.AckedPacket;
import io.hyperconduit.cc.CongestionController;
import io.hyperconduit.cc.Constants;
import io.hyperconduit.cc.LostPacket;
import io.hyperconduit.cc.RttStats;
import io.hyperconduit.frame.Frame;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sender-side reliability: tracks what is in flight, turns peer ACKs into RTT samples and
 * congestion events, declares losses, and arms the loss-detection and probe timers.
 *
 * <p>A single-packet-number-space port of quic-go's {@code internal/ackhandler/sent_packet_handler.go}.
 * Standard QUIC needs three spaces because it changes keys mid-handshake; this transport completes
 * its Noise handshake in unnumbered datagrams, so one space covers everything.
 *
 * <p>Two deliberate simplifications versus the reference:
 * <ul>
 *   <li><b>No packet-number skipping.</b> quic-go's {@code history.Difference} exists only to keep
 *       intentionally skipped numbers from tripping the packet threshold. Nothing here skips, so
 *       the difference is plain subtraction.</li>
 *   <li><b>No spurious-loss recovery.</b> A packet declared lost is dropped from history outright.
 *       quic-go keeps it to undo false declarations, but that only matters to controllers that cut
 *       their window on loss. Brutal does not, and dropping the entry also prevents a late ACK from
 *       counting the same packet as both acked and lost.</li>
 * </ul>
 *
 * <p>Pure-ACK packets <em>are</em> tracked, though they never enter bytes in flight and never yield a
 * retransmittable frame. Skipping them looks like an easy simplification and is not: the peer
 * acknowledges every packet number it receives, so an ACK frame's largest-acked is frequently one of
 * ours that carried nothing but an ACK. The RTT sample is only valid when that number resolves to a
 * packet we can look up, so not tracking them means never sampling the RTT at all — which silently
 * leaves Brutal sizing its window from the 100ms default forever.
 *
 * <p>Not thread-safe; the owning session serialises all calls.
 */
public final class SentPacketHandler {

    private static final long MAX_PTO_NANOS = 60_000_000_000L;
    private static final int MAX_OUTSTANDING_PACKETS = 40_000;
    private static final int MAX_TRACKED_PACKETS = 50_000;

    private final CongestionController cc;
    private final RttStats rttStats;
    private final TreeMap<Long, SentPacket> history = new TreeMap<>();

    private final List<AckedPacket> ackedInfo = new ArrayList<>();
    private final List<LostPacket> lostInfo = new ArrayList<>();
    private final List<SentPacket> ackedPackets = new ArrayList<>();
    private final List<Frame> retransmitQueue = new ArrayList<>();

    private long bytesInFlight;
    private long largestSent = -1;
    private long largestAcked = -1;
    /** Send time of the largest packet we have taken an RTT sample from, to reject reordered ACKs. */
    private long rttSampleSendTimeNanos;
    private boolean hasRttSample;

    private long lossTimeNanos;
    private boolean hasLossTime;
    private long lastAckElicitingTimeNanos;

    private int ptoCount;
    private int probesToSend;
    private long lostPackets;
    private int recentAcknowledgedPackets;
    private int recentLostPackets;
    private long alarmNanos;
    private boolean hasAlarm;

    public record PacketEvents(int acknowledgedPackets, int lostPackets) {
    }

    public SentPacketHandler(CongestionController cc, RttStats rttStats) {
        this.cc = cc;
        this.rttStats = rttStats;
        cc.setRttStats(rttStats);
    }

    public void setMaxDatagramSize(int size) {
        cc.setMaxDatagramSize(size);
    }

    public long bytesInFlight() {
        return bytesInFlight;
    }

    public int outstandingPackets() {
        return history.size();
    }

    public long largestSent() {
        return largestSent;
    }

    public int probesToSend() {
        return probesToSend;
    }

    public int ptoCount() {
        return ptoCount;
    }

    /** Total packets this side has declared lost over the life of the session. */
    public long lostPackets() {
        return lostPackets;
    }

    /** Returns and clears newly ACKed and newly declared-lost ack-eliciting packets. */
    public PacketEvents takeRecentPacketEvents() {
        PacketEvents events = new PacketEvents(recentAcknowledgedPackets, recentLostPackets);
        recentAcknowledgedPackets = 0;
        recentLostPackets = 0;
        return events;
    }

    public long congestionWindow() {
        return cc.congestionWindow();
    }

    public CongestionController congestionController() {
        return cc;
    }

    public boolean hasAlarm() {
        return hasAlarm;
    }

    /** Absolute {@code nanoTime} at which {@link #onLossDetectionTimeout} should next be called. */
    public long alarmNanos() {
        return alarmNanos;
    }

    /**
     * Records a packet that has just gone out.
     *
     * <p>The packet number comes first because it is the identifier; {@code nowNanos} second. Two
     * adjacent {@code long}s are easy to transpose, so callers outside this package should route
     * through a single helper rather than calling this repeatedly.
     *
     * @param ackEliciting false for packets carrying only ACK frames; those are neither counted in
     *                     bytes in flight nor tracked for loss, since there is nothing to recover
     */
    public void sentPacket(long packetNumber, long nowNanos, List<Frame> frames, int size,
                           boolean ackEliciting) {
        if (largestSent >= 0 && packetNumber <= largestSent) {
            throw new IllegalArgumentException(
                    "packet numbers must increase: " + packetNumber + " <= " + largestSent);
        }
        // Tracked for every packet, including pure ACKs: the peer acknowledges every number it
        // receives, and the RTT sample needs the largest acknowledged one to be resolvable.
        largestSent = packetNumber;
        history.put(packetNumber,
                new SentPacket(packetNumber, nowNanos, size, List.copyOf(frames), ackEliciting));

        if (ackEliciting) {
            bytesInFlight += size;
            lastAckElicitingTimeNanos = nowNanos;
            if (probesToSend > 0) {
                probesToSend--;
            }
        }

        // The pacer must account for every byte that actually left, acknowledged or not.
        cc.onPacketSent(nowNanos, bytesInFlight, packetNumber, size, ackEliciting);
        setLossDetectionTimer(nowNanos);
    }

    /**
     * Applies a received ACK frame.
     *
     * @return true if the frame acknowledged at least one packet not previously acknowledged
     */
    public boolean receivedAck(Frame.Ack ack, long rcvTimeNanos) throws ProtocolException {
        long frameLargestAcked = ack.largestAcked();
        if (frameLargestAcked > largestSent) {
            throw new ProtocolException("ACK for a packet we never sent: " + frameLargestAcked);
        }
        long priorInFlight = bytesInFlight;
        boolean hasAckEliciting = detectAndRemoveAckedPackets(ack);
        if (ackedPackets.isEmpty()) {
            return false;
        }

        // Sample the RTT only when the largest acknowledged number is one we just resolved, and only
        // when at least one acknowledged packet was ack-eliciting. The peer's reported ack delay
        // belongs to its largest received packet, so sampling against any other one inflates the
        // estimate. The send-time guard below additionally rejects reordered ACKs.
        SentPacket largest = ackedPackets.get(ackedPackets.size() - 1);
        if (largest.packetNumber == frameLargestAcked && hasAckEliciting) {
            long ackDelayNanos = Math.min(ack.ackDelayMicros() * 1000, rttStats.maxAckDelayNanos());
            if (!hasRttSample || largest.sendTimeNanos >= rttSampleSendTimeNanos) {
                rttStats.updateRtt(rcvTimeNanos - largest.sendTimeNanos, ackDelayNanos);
                rttSampleSendTimeNanos = largest.sendTimeNanos;
                hasRttSample = true;
            }
        }

        largestAcked = Math.max(largestAcked, frameLargestAcked);
        detectLostPackets(rcvTimeNanos);

        ackedInfo.clear();
        for (SentPacket packet : ackedPackets) {
            if (packet.inBytesInFlight) {
                ackedInfo.add(new AckedPacket(packet.packetNumber, packet.length));
            }
            removeFromBytesInFlight(packet);
        }
        recentAcknowledgedPackets += ackedInfo.size();
        ackedPackets.clear();

        if (!ackedInfo.isEmpty() || !lostInfo.isEmpty()) {
            cc.onCongestionEvent(priorInFlight, rcvTimeNanos, ackedInfo, lostInfo);
        }

        ptoCount = 0;
        probesToSend = 0;
        setLossDetectionTimer(rcvTimeNanos);
        return true;
    }

    public SendMode sendMode(long nowNanos) {
        if (history.size() >= MAX_TRACKED_PACKETS) {
            return SendMode.NONE;
        }
        // Probes take priority over every limit: a window that is full because nothing came back
        // is exactly the situation a probe has to break.
        if (probesToSend > 0) {
            return SendMode.PTO_PROBE;
        }
        if (!cc.canSend(bytesInFlight) || history.size() >= MAX_OUTSTANDING_PACKETS) {
            return SendMode.ACK_ONLY;
        }
        if (!cc.hasPacingBudget(nowNanos)) {
            return SendMode.PACING_LIMITED;
        }
        return SendMode.ANY;
    }

    public long timeUntilSendNanos(long nowNanos) {
        return cc.nanosUntilSend(nowNanos);
    }

    /** Runs the loss timer or the probe timer, whichever the alarm was armed for. */
    public void onLossDetectionTimeout(long nowNanos) {
        if (hasLossTime && nowNanos >= lossTimeNanos) {
            long priorInFlight = bytesInFlight;
            detectLostPackets(nowNanos);
            if (!lostInfo.isEmpty()) {
                cc.onCongestionEvent(priorInFlight, nowNanos, List.of(), lostInfo);
            }
        } else {
            ptoCount++;
            probesToSend += 2;
        }
        setLossDetectionTimer(nowNanos);
    }

    /** Whether declared-lost frames are waiting to be re-sent, without draining them. */
    public boolean hasRetransmitPending() {
        return !retransmitQueue.isEmpty();
    }

    /** Drains the frames that must be re-sent because their packet was declared lost. */
    public List<Frame> takeRetransmitQueue() {
        if (retransmitQueue.isEmpty()) {
            return List.of();
        }
        List<Frame> drained = new ArrayList<>(retransmitQueue);
        retransmitQueue.clear();
        return drained;
    }

    // --- internals ------------------------------------------------------------------

    /** @return whether at least one newly acknowledged packet was ack-eliciting */
    private boolean detectAndRemoveAckedPackets(Frame.Ack ack) {
        ackedPackets.clear();
        List<Frame.AckRange> ranges = ack.ranges();
        long lowestAcked = Long.MAX_VALUE;
        for (Frame.AckRange range : ranges) {
            lowestAcked = Math.min(lowestAcked, range.smallest());
        }
        long frameLargestAcked = ack.largestAcked();

        boolean hasAckEliciting = false;
        for (Map.Entry<Long, SentPacket> entry : history.entrySet()) {
            long packetNumber = entry.getKey();
            if (packetNumber < lowestAcked) {
                continue;
            }
            if (packetNumber > frameLargestAcked) {
                break;
            }
            if (!containedIn(ranges, packetNumber)) {
                continue;
            }
            SentPacket packet = entry.getValue();
            if (packet.acknowledged) {
                continue;
            }
            packet.acknowledged = true;
            hasAckEliciting |= packet.ackEliciting;
            ackedPackets.add(packet);
        }
        // Ascending order, which the caller relies on to find the largest newly acked packet last.
        for (SentPacket packet : ackedPackets) {
            history.remove(packet.packetNumber);
        }
        return hasAckEliciting;
    }

    private static boolean containedIn(List<Frame.AckRange> ranges, long packetNumber) {
        for (Frame.AckRange range : ranges) {
            if (packetNumber >= range.smallest() && packetNumber <= range.largest()) {
                return true;
            }
            if (range.largest() < packetNumber) {
                return false; // ranges are descending, so nothing further can match
            }
        }
        return false;
    }

    private void detectLostPackets(long nowNanos) {
        lostInfo.clear();
        hasLossTime = false;
        if (largestAcked < 0) {
            return;
        }

        long maxRtt = Math.max(rttStats.latestRttNanos(), rttStats.smoothedRttNanos());
        long lossDelay = Math.max((long) (Constants.TIME_THRESHOLD * maxRtt), Constants.TIMER_GRANULARITY_NANOS);
        long lostSendTimeNanos = nowNanos - lossDelay;

        List<SentPacket> lost = null;
        for (Map.Entry<Long, SentPacket> entry : history.entrySet()) {
            long packetNumber = entry.getKey();
            if (packetNumber > largestAcked) {
                break;
            }
            SentPacket packet = entry.getValue();
            boolean packetLost;
            if (packet.sendTimeNanos <= lostSendTimeNanos) {
                packetLost = true;
            } else if (largestAcked - packetNumber >= Constants.PACKET_THRESHOLD) {
                packetLost = true;
            } else {
                // Not lost yet, but this is the earliest packet that could become lost by time.
                if (!hasLossTime) {
                    lossTimeNanos = packet.sendTimeNanos + lossDelay;
                    hasLossTime = true;
                }
                continue;
            }
            if (lost == null) {
                lost = new ArrayList<>();
            }
            lost.add(packet);
        }
        if (lost == null) {
            return;
        }
        for (SentPacket packet : lost) {
            history.remove(packet.packetNumber);
            if (!packet.ackEliciting) {
                // A pure ACK was never in flight and carries nothing worth recovering; forgetting it
                // is enough. Counting it as lost would also corrupt Brutal's ack rate.
                continue;
            }
            lostPackets++;
            recentLostPackets++;
            removeFromBytesInFlight(packet);
            queueFramesForRetransmission(packet);
            lostInfo.add(new LostPacket(packet.packetNumber, packet.length));
        }
    }

    private void queueFramesForRetransmission(SentPacket packet) {
        for (Frame frame : packet.frames) {
            // StreamData is the payload; Close must reach the peer or it hangs waiting for us;
            // MaxData carries flow-control credit, and losing one silently stalls the peer forever
            // because the credit threshold has already moved past it.
            // ACK and PING are regenerated from live state each time, so replaying them is pointless.
            if (frame instanceof Frame.StreamData || frame instanceof Frame.Close
                    || frame instanceof Frame.MaxData) {
                retransmitQueue.add(frame);
            }
        }
    }

    private void removeFromBytesInFlight(SentPacket packet) {
        if (!packet.inBytesInFlight) {
            return;
        }
        if (packet.length > bytesInFlight) {
            throw new IllegalStateException("negative bytes in flight: removing " + packet.length
                    + " from " + bytesInFlight);
        }
        bytesInFlight -= packet.length;
        packet.inBytesInFlight = false;
    }

    private void setLossDetectionTimer(long nowNanos) {
        hasAlarm = false;
        // Only ack-eliciting packets need a timer. A pure-ACK packet carries nothing to recover, and
        // probing for one would wake an otherwise idle session for no benefit — this mirrors quic-go,
        // whose HasOutstandingPackets() likewise counts only ack-eliciting packets. Pure-ACK packets
        // stay in history for RTT resolution and are swept once a later ACK raises largestAcked past
        // them, so at most the last few can ever be resident.
        if (bytesInFlight == 0) {
            return;
        }
        if (hasLossTime) {
            alarmNanos = lossTimeNanos;
            hasAlarm = true;
            return;
        }
        alarmNanos = lastAckElicitingTimeNanos + scaledPtoNanos();
        hasAlarm = true;
    }

    /** Exponential PTO backoff, truncated to 60s. Computed by doubling to avoid shift overflow. */
    private long scaledPtoNanos() {
        long pto = rttStats.ptoNanos(true);
        for (int i = 0; i < ptoCount && pto < MAX_PTO_NANOS; i++) {
            pto *= 2;
        }
        return Math.min(pto, MAX_PTO_NANOS);
    }
}
