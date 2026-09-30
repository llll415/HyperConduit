package io.hyperconduit.conn;

import io.hyperconduit.ProtocolException;
import io.hyperconduit.cc.BrutalCc;
import io.hyperconduit.cc.RttStats;
import io.hyperconduit.frame.Frame;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentPacketHandlerTest {

    private static final long MS = 1_000_000L;
    private static final int SIZE = 1200;

    private final RecordingCc cc = new RecordingCc();
    private final RttStats rtt = new RttStats();
    private final SentPacketHandler handler = new SentPacketHandler(cc, rtt);

    private static Frame streamData(long offset, String text) {
        return new Frame.StreamData(offset, text.getBytes(StandardCharsets.UTF_8));
    }

    private static Frame.Ack ackOf(Frame.AckRange... ranges) {
        return new Frame.Ack(0, List.of(ranges));
    }

    private void send(long packetNumber, long nowNanos) {
        handler.sentPacket(packetNumber, nowNanos, List.of(streamData(packetNumber * 100L, "x")), SIZE, true);
    }

    // --- bytes in flight -------------------------------------------------------

    @Test
    void sendingPutsBytesInFlight() {
        send(0, 0);
        send(1, MS);
        assertEquals(2 * SIZE, handler.bytesInFlight());
        assertEquals(2, handler.outstandingPackets());
        assertEquals(1, handler.largestSent());
    }

    @Test
    void pureAckPacketsAreTrackedButNotInFlight() throws Exception {
        handler.sentPacket(0, 0, List.of(new Frame.Ping()), SIZE, true);
        handler.sentPacket(1, MS, List.of(ackOf(new Frame.AckRange(0, 0))), 64, false);

        assertEquals(SIZE, handler.bytesInFlight(), "a pure ACK does not enter bytes in flight");
        assertEquals(2, handler.outstandingPackets(),
                "but it is tracked, so the peer's ACK for it can resolve and yield an RTT sample");

        handler.receivedAck(ackOf(new Frame.AckRange(0, 0)), 50 * MS);

        assertEquals(0, handler.bytesInFlight());
        assertEquals(1, handler.outstandingPackets(), "the pure ACK is still awaiting its own ACK");
        assertFalse(handler.hasAlarm(),
                "nothing recoverable is outstanding, so no probe timer should be armed");
    }

    @Test
    void rttIsSampledWhenTheLargestAckedPacketWasAPureAck() throws Exception {
        // Regression: the peer acknowledges every packet number it receives, including our pure-ACK
        // packets, so an ACK frame's largest-acked is often one of those. Not tracking them meant the
        // largest never resolved and no RTT sample was ever taken, silently leaving Brutal sizing its
        // window from the 100ms default for the whole session.
        handler.sentPacket(0, 0, List.of(streamData(0, "a")), SIZE, true);
        handler.sentPacket(1, MS, List.of(ackOf(new Frame.AckRange(0, 0))), 64, false);

        handler.receivedAck(ackOf(new Frame.AckRange(0, 1)), 50 * MS);

        assertTrue(rtt.hasMeasurement(), "an RTT sample must be taken");
        assertEquals(49 * MS, rtt.latestRttNanos(), "sampled from packet 1: sent at 1ms, acked at 50ms");
        assertEquals(0, handler.bytesInFlight());
        assertEquals(0, handler.outstandingPackets());
    }

    @Test
    void acknowledgingRemovesBytesFromFlight() throws Exception {
        send(0, 0);
        send(1, MS);

        assertTrue(handler.receivedAck(ackOf(new Frame.AckRange(0, 1)), 50 * MS));
        assertEquals(0, handler.bytesInFlight());
        assertEquals(0, handler.outstandingPackets());
    }

    @Test
    void duplicateAckIsIgnored() throws Exception {
        send(0, 0);
        Frame.Ack ack = ackOf(new Frame.AckRange(0, 0));

        assertTrue(handler.receivedAck(ack, 50 * MS));
        assertFalse(handler.receivedAck(ack, 60 * MS), "the second ACK covers nothing new");
        assertEquals(1, cc.congestionEvents.size(), "the congestion controller must not see it twice");
    }

    @Test
    void ackForAnUnsentPacketIsAProtocolViolation() {
        send(0, 0);
        assertThrows(ProtocolException.class,
                () -> handler.receivedAck(ackOf(new Frame.AckRange(5, 5)), 10 * MS));
    }

    @Test
    void packetNumbersMustIncrease() {
        send(5, 0);
        assertThrows(IllegalArgumentException.class, () -> send(5, MS));
        assertThrows(IllegalArgumentException.class, () -> send(4, MS));
    }

    // --- RTT sampling ----------------------------------------------------------

    @Test
    void rttIsSampledFromTheLargestNewlyAcknowledgedPacket() throws Exception {
        send(0, 0);
        send(1, 10 * MS);

        handler.receivedAck(ackOf(new Frame.AckRange(0, 1)), 110 * MS);
        assertTrue(rtt.hasMeasurement());
        // The sample comes from packet 1 (sent at 10ms, acked at 110ms), not packet 0.
        assertEquals(100 * MS, rtt.latestRttNanos());
    }

    @Test
    void reportedAckDelayIsCappedThenSubtracted() throws Exception {
        rtt.setMaxAckDelayNanos(25 * MS);
        send(0, 0);
        handler.receivedAck(ackOf(new Frame.AckRange(0, 0)), 50 * MS);
        assertEquals(50 * MS, rtt.latestRttNanos());
        assertEquals(50 * MS, rtt.minRttNanos());

        send(1, 50 * MS);
        // The peer claims a 10s delay. Uncapped that would drive the sample negative; capped to
        // maxAckDelay (25ms) it is subtracted from the 100ms raw delta.
        handler.receivedAck(new Frame.Ack(10_000_000, List.of(new Frame.AckRange(1, 1))), 150 * MS);
        assertEquals(75 * MS, rtt.latestRttNanos());
    }

    @Test
    void reorderedAckDoesNotProduceAnRttSample() throws Exception {
        // Both packets go out at effectively the same time so that neither trips loss detection
        // and packet 0 is still in history when its late ACK arrives.
        send(0, 0);
        send(1, MS);
        handler.receivedAck(ackOf(new Frame.AckRange(1, 1)), 100 * MS);
        long sampleAfterFirstAck = rtt.latestRttNanos();
        assertEquals(99 * MS, sampleAfterFirstAck);

        // A late ACK for packet 0 would report a far larger RTT; the send-time guard rejects it.
        handler.receivedAck(ackOf(new Frame.AckRange(0, 0)), 110 * MS);
        assertEquals(sampleAfterFirstAck, rtt.latestRttNanos());
    }

    // --- loss detection --------------------------------------------------------

    @Test
    void packetThresholdDeclaresTheOldestPacketLost() throws Exception {
        for (long pn = 0; pn <= 3; pn++) {
            send(pn, 0);
        }
        handler.receivedAck(ackOf(new Frame.AckRange(3, 3)), MS);

        // Packet 0 is 3 numbers behind the largest acked -> lost. Packets 1 and 2 are not yet.
        List<Frame> retransmits = handler.takeRetransmitQueue();
        assertEquals(1, retransmits.size());
        assertEquals(0L, ((Frame.StreamData) retransmits.get(0)).offset());

        // 4 sent, 1 acked, 1 lost: packets 1 and 2 remain.
        assertEquals(2 * SIZE, handler.bytesInFlight());
        assertEquals(2, handler.outstandingPackets());
        assertEquals(1, cc.lostTotal);
        assertEquals(1, cc.ackedTotal);
    }

    @Test
    void packetThresholdNeedsThreeNumbersOfDistance() throws Exception {
        for (long pn = 0; pn <= 2; pn++) {
            send(pn, 0);
        }
        handler.receivedAck(ackOf(new Frame.AckRange(2, 2)), MS);
        assertEquals(0, cc.lostTotal, "a distance of 2 is below the threshold of 3");
        assertTrue(handler.takeRetransmitQueue().isEmpty());
    }

    @Test
    void timeThresholdDeclaresAPacketLostWhenThePacketThresholdCannot() throws Exception {
        RecordingCc localCc = new RecordingCc();
        SentPacketHandler local = new SentPacketHandler(localCc, new RttStats());
        local.sentPacket(0, 0, List.of(streamData(0, "a")), SIZE, true);
        local.sentPacket(1, 0, List.of(streamData(1, "b")), SIZE, true);

        local.receivedAck(ackOf(new Frame.AckRange(1, 1)), 200 * MS);
        assertEquals(0, localCc.lostTotal,
                "packet 0 is only 1 number behind, below the packet threshold of 3");

        // The RTT sample is 200ms, so the loss delay is 9/8 * 200ms measured from packet 0's send.
        assertEquals(225 * MS, local.alarmNanos());
        local.onLossDetectionTimeout(225 * MS);

        assertEquals(1, localCc.lostTotal, "the time threshold must declare it lost");
        assertEquals(1, local.takeRetransmitQueue().size());
    }

    @Test
    void onlyStreamDataAndCloseAreRetransmitted() throws Exception {
        handler.sentPacket(0, 0,
                List.of(new Frame.Ping(), streamData(0, "a"), ackOf(new Frame.AckRange(0, 0))), SIZE, true);
        handler.sentPacket(3, 0, List.of(streamData(3, "b")), SIZE, true);
        handler.receivedAck(ackOf(new Frame.AckRange(3, 3)), MS);

        List<Frame> retransmits = handler.takeRetransmitQueue();
        assertEquals(1, retransmits.size(), "PING and ACK frames go stale and must not be replayed");
        assertTrue(retransmits.get(0) instanceof Frame.StreamData);
    }

    @Test
    void aLateAckForAnAlreadyLostPacketIsNotCountedAgain() throws Exception {
        for (long pn = 0; pn <= 3; pn++) {
            send(pn, 0);
        }
        handler.receivedAck(ackOf(new Frame.AckRange(3, 3)), MS); // declares packet 0 lost
        assertEquals(1, cc.lostTotal);
        assertEquals(2 * SIZE, handler.bytesInFlight());

        // The original packet 0 turns up after all. It must not be re-acked, re-counted, or
        // subtracted from bytes in flight a second time.
        assertFalse(handler.receivedAck(ackOf(new Frame.AckRange(0, 0)), 2 * MS));
        assertEquals(1, cc.lostTotal);
        assertEquals(1, cc.ackedTotal);
        assertEquals(2 * SIZE, handler.bytesInFlight());
    }

    // --- timers ----------------------------------------------------------------

    @Test
    void noAlarmWhenNothingIsInFlight() {
        assertFalse(handler.hasAlarm());
        handler.sentPacket(0, 0, List.of(ackOf(new Frame.AckRange(0, 0))), 64, false);
        assertFalse(handler.hasAlarm(), "a pure ACK needs no loss timer or probe");
    }

    @Test
    void ptoFiresAndRequestsTwoProbes() {
        send(0, 0);
        assertTrue(handler.hasAlarm());
        // No RTT measurement yet, so PTO = 2 * the 100ms default.
        assertEquals(200 * MS, handler.alarmNanos());

        handler.onLossDetectionTimeout(200 * MS);
        assertEquals(SendMode.PTO_PROBE, handler.sendMode(200 * MS));
        assertEquals(2, handler.probesToSend());
        assertEquals(1, handler.ptoCount());
    }

    @Test
    void ptoBacksOffExponentially() {
        send(0, 0);
        handler.onLossDetectionTimeout(200 * MS);
        assertEquals(400 * MS, handler.alarmNanos());

        handler.onLossDetectionTimeout(400 * MS);
        assertEquals(800 * MS, handler.alarmNanos());
        assertEquals(2, handler.ptoCount());
        assertEquals(4, handler.probesToSend());
    }

    @Test
    void sendingProbesConsumesTheProbeBudget() {
        send(0, 0);
        handler.onLossDetectionTimeout(200 * MS);
        assertEquals(2, handler.probesToSend());

        handler.sentPacket(1, 200 * MS, List.of(streamData(1, "p")), SIZE, true);
        assertEquals(1, handler.probesToSend());
        handler.sentPacket(2, 200 * MS, List.of(streamData(2, "p")), SIZE, true);
        assertEquals(0, handler.probesToSend());
        assertEquals(SendMode.ANY, handler.sendMode(200 * MS));
    }

    @Test
    void anAckResetsTheProbeState() throws Exception {
        send(0, 0);
        handler.onLossDetectionTimeout(200 * MS);
        assertEquals(1, handler.ptoCount());

        handler.receivedAck(ackOf(new Frame.AckRange(0, 0)), 210 * MS);
        assertEquals(0, handler.ptoCount());
        assertEquals(0, handler.probesToSend());
        assertFalse(handler.hasAlarm(), "nothing is outstanding any more");
    }

    @Test
    void lossTimerFiresBeforeThePto() throws Exception {
        for (long pn = 0; pn <= 3; pn++) {
            send(pn, 0);
        }
        // The 1ms RTT sample makes the loss delay 9/8ms, so the loss timer is armed well inside
        // the 200ms PTO and must be the one that fires.
        handler.receivedAck(ackOf(new Frame.AckRange(3, 3)), MS);
        assertEquals(1, cc.lostTotal, "packet 0 lost by the packet threshold");
        assertTrue(handler.hasAlarm());
        assertEquals(1_125_000L, handler.alarmNanos());

        handler.onLossDetectionTimeout(handler.alarmNanos());
        assertEquals(3, cc.lostTotal, "the loss timer must also declare packets 1 and 2 lost");
    }

    // --- send gating -----------------------------------------------------------

    @Test
    void sendModeReflectsTheCongestionWindow() {
        cc.congestionWindow = 2 * SIZE;
        send(0, 0);
        send(1, MS);
        assertEquals(SendMode.ANY, handler.sendMode(2 * MS));

        send(2, 2 * MS);
        assertEquals(SendMode.ACK_ONLY, handler.sendMode(3 * MS));
    }

    @Test
    void sendModeReflectsThePacer() {
        send(0, 0);
        assertEquals(SendMode.ANY, handler.sendMode(MS));

        cc.pacingBudget = false;
        assertEquals(SendMode.PACING_LIMITED, handler.sendMode(MS));
    }

    @Test
    void probesOutrankTheCongestionWindow() {
        cc.congestionWindow = 0;
        send(0, 0);
        assertEquals(SendMode.ACK_ONLY, handler.sendMode(MS));

        handler.onLossDetectionTimeout(200 * MS);
        assertEquals(SendMode.PTO_PROBE, handler.sendMode(200 * MS),
                "a window that is full because nothing came back is exactly what a probe must break");
    }

    @Test
    void timeUntilSendIsDelegatedToTheController() {
        cc.pacingDelayNanos = 5 * MS;
        assertEquals(5 * MS, handler.timeUntilSendNanos(0));
    }

    @Test
    void controllerIsGivenTheRttStats() {
        assertEquals(rtt, cc.rttStats());
    }

    // --- Brutal integration ----------------------------------------------------

    @Test
    void brutalReceivesAckedAndLostCountsFromOneEvent() throws Exception {
        BrutalCc brutal = new BrutalCc(12_500_000, false);
        SentPacketHandler brutalHandler = new SentPacketHandler(brutal, new RttStats());

        for (long pn = 0; pn < 60; pn++) {
            brutalHandler.sentPacket(pn, pn * MS, List.of(streamData(pn, "x")), SIZE, true);
        }
        // Ack the top 10; everything below them is old enough to be declared lost in one pass.
        brutalHandler.receivedAck(ackOf(new Frame.AckRange(50, 59)), 100 * MS);

        assertTrue(brutal.ackRate() < 1.0, "Brutal must have observed loss");
        assertEquals(0.8, brutal.ackRate(), 1e-9,
                "10 acks against ~57 losses is far below the floor, so it clamps to 0.8");
        assertEquals(12_500_000L * 5 / 4, brutal.pacingRateBytesPerSec(),
                "loss compensation must raise the pacing rate to bps / 0.8");
    }
}
