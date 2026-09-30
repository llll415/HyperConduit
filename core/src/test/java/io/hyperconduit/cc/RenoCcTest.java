package io.hyperconduit.cc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenoCcTest {

    private static final int DATAGRAM = Constants.INITIAL_PACKET_SIZE;

    private static List<AckedPacket> acked(int count, long bytesEach) {
        List<AckedPacket> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new AckedPacket(i, bytesEach));
        }
        return out;
    }

    private static List<LostPacket> lost(int count) {
        List<LostPacket> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new LostPacket(i, 1200));
        }
        return out;
    }

    private static void ack(RenoCc cc, int count, long bytesEach) {
        cc.onCongestionEvent(0, 0, acked(count, bytesEach), List.of());
    }

    private static void lose(RenoCc cc, int count) {
        cc.onCongestionEvent(0, 0, List.of(), lost(count));
    }

    @Test
    void startsAtTenDatagrams() {
        assertEquals(10L * DATAGRAM, new RenoCc().congestionWindow());
    }

    @Test
    void hasNoPacerWhichIsThePointOfTheBaseline() {
        RenoCc cc = new RenoCc();
        assertEquals(0, cc.nanosUntilSend(0));
        assertEquals(0, cc.nanosUntilSend(1_000_000_000L));
        assertTrue(cc.hasPacingBudget(0));
    }

    @Test
    void slowStartGrowsByTheAcknowledgedBytes() {
        RenoCc cc = new RenoCc();
        ack(cc, 10, 1200);
        assertEquals(10L * DATAGRAM + 12_000, cc.congestionWindow());
    }

    @Test
    void lossHalvesTheWindow() {
        RenoCc cc = new RenoCc();
        ack(cc, 10, 1200); // 24800
        long before = cc.congestionWindow();

        lose(cc, 1);
        assertEquals(before / 2, cc.congestionWindow());
    }

    @Test
    void manyLostPacketsInOneEventReduceOnlyOnce() {
        RenoCc one = new RenoCc();
        RenoCc many = new RenoCc();
        ack(one, 10, 1200);
        ack(many, 10, 1200);

        lose(one, 1);
        lose(many, 5);
        assertEquals(one.congestionWindow(), many.congestionWindow(),
                "a single ACK event must cause a single multiplicative decrease");
    }

    @Test
    void congestionAvoidanceGrowsAboutOneDatagramPerRtt() {
        RenoCc cc = new RenoCc();
        ack(cc, 10, 1200);
        lose(cc, 1); // cwnd == ssthresh == 12400, so the next ACK enters congestion avoidance
        long before = cc.congestionWindow();

        ack(cc, 1, 1200);
        assertEquals(before + (long) DATAGRAM * 1200 / before, cc.congestionWindow());
        assertTrue(cc.congestionWindow() - before < DATAGRAM,
                "congestion avoidance must grow far slower than slow start");
    }

    @Test
    void windowIsCappedAtMaxCongestionWindowPackets() {
        RenoCc cc = new RenoCc();
        for (int i = 0; i < 200; i++) {
            ack(cc, 100, 1280);
        }
        assertEquals(20000L * DATAGRAM, cc.congestionWindow());
    }

    @Test
    void windowNeverFallsBelowTwoDatagrams() {
        RenoCc cc = new RenoCc();
        for (int i = 0; i < 20; i++) {
            lose(cc, 1);
        }
        assertEquals(2L * DATAGRAM, cc.congestionWindow());
    }

    @Test
    void canSendIsStrictlyBelowTheWindow() {
        RenoCc cc = new RenoCc();
        long cwnd = cc.congestionWindow();
        assertTrue(cc.canSend(cwnd - 1));
        assertFalse(cc.canSend(cwnd));
    }

    @Test
    void ackOnlyAndLossOnlyEventsAreBothHandled() {
        RenoCc cc = new RenoCc();
        cc.onCongestionEvent(0, 0, List.of(), List.of()); // neither: must not throw or change cwnd
        assertEquals(10L * DATAGRAM, cc.congestionWindow());
    }
}
