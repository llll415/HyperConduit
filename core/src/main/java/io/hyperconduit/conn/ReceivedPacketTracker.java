package io.hyperconduit.conn;

import io.hyperconduit.frame.Frame;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tracks received packet numbers and turns them into {@link Frame.Ack} frames.
 *
 * <p>Acknowledgement timing follows RFC 9002's guidance: ack every second ack-eliciting packet
 * immediately, and ack at once when a packet arrives out of order, because a gap is exactly the
 * signal the sender's loss detector is waiting for. Delaying it would add a full round trip to
 * every retransmission on a lossy path, which is the thing this transport exists to minimise.
 */
public final class ReceivedPacketTracker {

    private static final int ACK_ELICITING_THRESHOLD = 2;
    /** Bounds the ACK frame size; the oldest ranges are dropped first. */
    private static final int MAX_ACK_RANGES = 64;

    /** Inclusive ranges of received packet numbers, keyed by the smallest number in each range. */
    private final TreeMap<Long, Long> ranges = new TreeMap<>();

    private long largestReceived = -1;
    private long largestReceivedTimeNanos;
    private int ackElicitingSinceLastAck;
    private boolean receivedOutOfOrder;

    /**
     * @param ackEliciting whether the packet carried frames the peer expects to be acked for;
     *                     pure-ACK packets are excluded, matching how the sender counts them
     */
    public void receivedPacket(long packetNumber, boolean ackEliciting, long nowNanos) {
        if (packetNumber < largestReceived) {
            receivedOutOfOrder = true;
        } else {
            largestReceived = packetNumber;
            largestReceivedTimeNanos = nowNanos;
        }
        addRange(packetNumber);
        if (ackEliciting) {
            ackElicitingSinceLastAck++;
        }
    }

    public boolean hasAnythingToAck() {
        return largestReceived >= 0;
    }

    /**
     * Whether an ACK should go out now rather than being coalesced into the next data packet.
     * Only meaningful when {@link #hasAnythingToAck()}.
     */
    public boolean shouldAckImmediately() {
        return receivedOutOfOrder || ackElicitingSinceLastAck >= ACK_ELICITING_THRESHOLD;
    }

    /** Builds the ACK frame and clears the "needs an immediate ack" state. */
    public Frame.Ack buildAck(long nowNanos) {
        if (!hasAnythingToAck()) {
            return null;
        }
        long ackDelayMicros = Math.max(0, nowNanos - largestReceivedTimeNanos) / 1000;
        List<Frame.AckRange> ackRanges = new ArrayList<>(ranges.size());
        // Descending by packet number, which is the wire order and what Frame.Ack normalises to.
        for (Map.Entry<Long, Long> entry : ranges.descendingMap().entrySet()) {
            ackRanges.add(new Frame.AckRange(entry.getKey(), entry.getValue()));
        }
        ackElicitingSinceLastAck = 0;
        receivedOutOfOrder = false;
        return new Frame.Ack(ackDelayMicros, ackRanges);
    }

    /** Largest received packet number, or {@code -1} before anything arrives. */
    public long largestReceived() {
        return largestReceived;
    }

    public int rangeCount() {
        return ranges.size();
    }

    private void addRange(long packetNumber) {
        Map.Entry<Long, Long> floor = ranges.floorEntry(packetNumber);
        if (floor != null && packetNumber <= floor.getValue()) {
            return; // already covered
        }
        long smallest = packetNumber;
        long largest = packetNumber;
        if (floor != null && floor.getValue() == packetNumber - 1) {
            smallest = floor.getKey();
            ranges.remove(smallest);
        }
        Long followingLargest = ranges.get(packetNumber + 1);
        if (followingLargest != null) {
            largest = followingLargest;
            ranges.remove(packetNumber + 1);
        }
        ranges.put(smallest, largest);
        pruneOldestRanges();
    }

    /**
     * Dropping the oldest ranges can leave some packets permanently unacknowledged; the sender then
     * declares them lost and retransmits, and {@link ReceiveBuffer} discards the duplicates.
     * Losing a little redundant work is preferable to an unbounded ACK frame.
     */
    private void pruneOldestRanges() {
        while (ranges.size() > MAX_ACK_RANGES) {
            ranges.pollFirstEntry();
        }
    }
}
