package io.hyperconduit.cc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrutalCcTest {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    /** 100 Mbps expressed in bytes/s, matching how Hysteria's `up`/`down` config is converted. */
    private static final long BPS_100MBIT = 100_000_000L / 8;

    private static BrutalCc newCc(RttStats rtt) {
        BrutalCc cc = new BrutalCc(BPS_100MBIT, false);
        cc.setRttStats(rtt);
        return cc;
    }

    private static List<AckedPacket> acked(int n) {
        List<AckedPacket> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new AckedPacket(i, 1200));
        }
        return out;
    }

    private static List<LostPacket> lost(int n) {
        List<LostPacket> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new LostPacket(i, 1200));
        }
        return out;
    }

    @Test
    void windowIsBpsTimesRttTimesGainWhenNoLoss() {
        RttStats rtt = new RttStats();
        rtt.updateRtt(200_000_000L, 0); // 200ms
        BrutalCc cc = newCc(rtt);

        // cwnd = 12_500_000 B/s * 0.2 s * 2 / 1.0 = 5_000_000 bytes
        assertEquals(5_000_000L, cc.congestionWindow());
        assertEquals(BPS_100MBIT, cc.pacingRateBytesPerSec());
        assertEquals(1.0, cc.ackRate(), 1e-9);
    }

    @Test
    void windowFallsBackBeforeAnyRttSample() {
        BrutalCc cc = new BrutalCc(BPS_100MBIT, false);
        // No RttStats wired up at all: the transport must still have a usable window.
        assertEquals(10240L, cc.congestionWindow());
    }

    @Test
    void windowIsNeverSmallerThanOneDatagram() {
        RttStats rtt = new RttStats();
        rtt.updateRtt(1_000L, 0); // absurdly tiny RTT
        BrutalCc cc = newCc(rtt);
        assertEquals(Constants.INITIAL_PACKET_SIZE, cc.congestionWindow());
    }

    @Test
    void lossCompensationRaisesWindowAndPacingRate() {
        RttStats rtt = new RttStats();
        rtt.updateRtt(200_000_000L, 0);
        BrutalCc cc = newCc(rtt);
        long windowBeforeLoss = cc.congestionWindow();

        // 40 acked / 10 lost = ackRate 0.8, exactly at the clamp floor, with 50 samples.
        cc.onCongestionEvent(0, 5 * NANOS_PER_SECOND, acked(40), lost(10));

        assertEquals(0.8, cc.ackRate(), 1e-9);
        // Losing packets must make Brutal push *harder*, never back off.
        assertTrue(cc.congestionWindow() > windowBeforeLoss,
                "window shrank on loss: " + windowBeforeLoss + " -> " + cc.congestionWindow());
        assertEquals(6_250_000L, cc.congestionWindow()); // 12.5e6 * 0.2 * 2 / 0.8
        assertEquals(BPS_100MBIT * 5 / 4, cc.pacingRateBytesPerSec());
    }

    @Test
    void ackRateIsClampedAtTheFloor() {
        BrutalCc cc = newCc(new RttStats());
        cc.onCongestionEvent(0, 5 * NANOS_PER_SECOND, acked(10), lost(90));
        assertEquals(0.8, cc.ackRate(), 1e-9, "10% delivery must still clamp to 0.8");
    }

    @Test
    void ackRateStaysAtOneUntilEnoughSamples() {
        BrutalCc cc = newCc(new RttStats());
        cc.onCongestionEvent(0, 5 * NANOS_PER_SECOND, acked(5), lost(44)); // 49 samples
        assertEquals(1.0, cc.ackRate(), 1e-9);

        cc.onCongestionEvent(0, 5 * NANOS_PER_SECOND, acked(1), lost(0)); // 50 samples
        assertTrue(cc.ackRate() < 1.0, "50 samples should activate loss compensation");
    }

    @Test
    void ackRateOnlyCountsTheLastFiveSeconds() {
        BrutalCc cc = newCc(new RttStats());
        // Heavy loss at t=0s, then clean delivery from t=1s to t=5s.
        cc.onCongestionEvent(0, 0, acked(30), lost(70));
        for (long sec = 1; sec <= 5; sec++) {
            cc.onCongestionEvent(0, sec * NANOS_PER_SECOND, acked(10), lost(0));
        }
        assertEquals(1.0, cc.ackRate(), 1e-9, "the lossy slot must have aged out");
    }

    @Test
    void disableLossCompensationPinsAckRateToOne() {
        RttStats rtt = new RttStats();
        rtt.updateRtt(200_000_000L, 0);
        BrutalCc cc = new BrutalCc(BPS_100MBIT, true);
        cc.setRttStats(rtt);

        cc.onCongestionEvent(0, 5 * NANOS_PER_SECOND, acked(40), lost(10));
        assertEquals(1.0, cc.ackRate(), 1e-9);
        assertEquals(5_000_000L, cc.congestionWindow());
    }

    @Test
    void samplesInSeparateSlotsBothCount() {
        BrutalCc cc = newCc(new RttStats());
        cc.onCongestionEvent(0, 1 * NANOS_PER_SECOND, acked(20), lost(5));
        cc.onCongestionEvent(0, 2 * NANOS_PER_SECOND, acked(20), lost(5));
        // 40 acked / 10 lost across two slots.
        assertEquals(0.8, cc.ackRate(), 1e-9);
    }

    @Test
    void negativeNanoTimeOriginStillSlotsCorrectly() {
        BrutalCc cc = newCc(new RttStats());
        // System.nanoTime() may be negative; floorDiv/floorMod must keep slots in range.
        cc.onCongestionEvent(0, -3 * NANOS_PER_SECOND, acked(40), lost(10));
        assertEquals(0.8, cc.ackRate(), 1e-9);
    }

    @Test
    void canSendHonoursTheWindow() {
        RttStats rtt = new RttStats();
        rtt.updateRtt(200_000_000L, 0);
        BrutalCc cc = newCc(rtt);
        assertTrue(cc.canSend(5_000_000L));
        assertTrue(!cc.canSend(5_000_001L));
    }

    @Test
    void rejectsNonPositiveRate() {
        assertThrows(IllegalArgumentException.class, () -> new BrutalCc(0, false));
        assertThrows(IllegalArgumentException.class, () -> new BrutalCc(-1, false));
    }
}
