package io.hyperconduit.conn;

import io.hyperconduit.frame.Frame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceivedPacketTrackerTest {

    private static final long MS = 1_000_000L;

    @Test
    void nothingToAckBeforeAnyPacketArrives() {
        ReceivedPacketTracker tracker = new ReceivedPacketTracker();
        assertFalse(tracker.hasAnythingToAck());
        assertNull(tracker.buildAck(0));
        assertEquals(-1, tracker.largestReceived());
    }

    @Test
    void contiguousPacketNumbersCollapseIntoOneRange() {
        ReceivedPacketTracker tracker = new ReceivedPacketTracker();
        for (long pn = 0; pn <= 9; pn++) {
            tracker.receivedPacket(pn, true, pn * MS);
        }
        Frame.Ack ack = tracker.buildAck(9 * MS);
        assertEquals(1, ack.ranges().size());
        assertEquals(new Frame.AckRange(0, 9), ack.ranges().get(0));
        assertEquals(9, ack.largestAcked());
        assertEquals(1, tracker.rangeCount());
    }

    @Test
    void aGapProducesTwoRangesInDescendingOrder() {
        ReceivedPacketTracker tracker = new ReceivedPacketTracker();
        tracker.receivedPacket(0, true, 0);
        tracker.receivedPacket(1, true, MS);
        tracker.receivedPacket(5, true, 2 * MS);
        tracker.receivedPacket(6, true, 3 * MS);

        Frame.Ack ack = tracker.buildAck(3 * MS);
        assertEquals(List.of(new Frame.AckRange(5, 6), new Frame.AckRange(0, 1)), ack.ranges());
        assertEquals(6, ack.largestAcked());
    }

    @Test
    void fillingAGapMergesTheSurroundingRanges() {
        ReceivedPacketTracker tracker = new ReceivedPacketTracker();
        tracker.receivedPacket(0, true, 0);
        tracker.receivedPacket(2, true, MS);
        assertEquals(2, tracker.rangeCount());

        tracker.receivedPacket(1, true, 2 * MS);
        assertEquals(1, tracker.rangeCount());
        assertEquals(List.of(new Frame.AckRange(0, 2)), tracker.buildAck(2 * MS).ranges());
    }

    @Test
    void duplicatePacketNumbersChangeNothing() {
        ReceivedPacketTracker tracker = new ReceivedPacketTracker();
        tracker.receivedPacket(3, true, 0);
        tracker.receivedPacket(3, true, MS);
        tracker.receivedPacket(3, true, 2 * MS);

        assertEquals(1, tracker.rangeCount());
        assertEquals(3, tracker.largestReceived());
        assertEquals(List.of(new Frame.AckRange(3, 3)), tracker.buildAck(2 * MS).ranges());
    }

    @Test
    void acksImmediatelyEverySecondAckElicitingPacket() {
        ReceivedPacketTracker tracker = new ReceivedPacketTracker();
        tracker.receivedPacket(0, true, 0);
        assertFalse(tracker.shouldAckImmediately(), "one packet may be coalesced into the next send");

        tracker.receivedPacket(1, true, MS);
        assertTrue(tracker.shouldAckImmediately(), "RFC 9002's ack-eliciting threshold is 2");
    }

    @Test
    void acksImmediatelyOnOutOfOrderArrival() {
        ReceivedPacketTracker tracker = new ReceivedPacketTracker();
        tracker.receivedPacket(5, true, 0);
        assertFalse(tracker.shouldAckImmediately());

        // A gap means the sender's loss detector is waiting on exactly this information.
        tracker.receivedPacket(3, true, MS);
        assertTrue(tracker.shouldAckImmediately());
        assertEquals(5, tracker.largestReceived(), "a late packet must not lower the largest");
    }

    @Test
    void pureAckPacketsDoNotTriggerTheAckElicitingThreshold() {
        ReceivedPacketTracker tracker = new ReceivedPacketTracker();
        for (long pn = 0; pn < 10; pn++) {
            tracker.receivedPacket(pn, false, pn * MS);
        }
        assertFalse(tracker.shouldAckImmediately(), "acking an ACK would ping-pong forever");
        assertTrue(tracker.hasAnythingToAck());
    }

    @Test
    void buildingAnAckClearsTheImmediateFlag() {
        ReceivedPacketTracker tracker = new ReceivedPacketTracker();
        tracker.receivedPacket(0, true, 0);
        tracker.receivedPacket(1, true, MS);
        assertTrue(tracker.shouldAckImmediately());

        tracker.buildAck(MS);
        assertFalse(tracker.shouldAckImmediately());

        tracker.receivedPacket(2, true, 2 * MS);
        assertFalse(tracker.shouldAckImmediately(), "the counter restarts after each ACK is built");
    }

    @Test
    void ackDelayMeasuresTimeSinceTheLargestArrived() {
        ReceivedPacketTracker tracker = new ReceivedPacketTracker();
        tracker.receivedPacket(0, true, 10 * MS);
        tracker.receivedPacket(1, true, 40 * MS);

        // 15ms after the largest arrived, expressed in microseconds.
        assertEquals(15_000, tracker.buildAck(55 * MS).ackDelayMicros());
    }

    @Test
    void ackDelayIsNeverNegative() {
        ReceivedPacketTracker tracker = new ReceivedPacketTracker();
        tracker.receivedPacket(0, true, 100 * MS);
        assertEquals(0, tracker.buildAck(50 * MS).ackDelayMicros());
    }

    @Test
    void rangeCountIsBounded() {
        ReceivedPacketTracker tracker = new ReceivedPacketTracker();
        // Every other packet number, so no two ever merge into one range.
        for (long pn = 0; pn < 400; pn += 2) {
            tracker.receivedPacket(pn, true, pn);
        }
        assertTrue(tracker.rangeCount() <= 64, "got " + tracker.rangeCount());
        // The newest ranges are the ones kept, since those are the ones the sender is waiting on.
        assertEquals(398, tracker.buildAck(0).largestAcked());
    }
}
