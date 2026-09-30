package io.hyperconduit.cc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacerTest {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    /** 1280 bytes/ms, so one max-size datagram takes exactly 1ms of budget to accrue. */
    private static final long ONE_PACKET_PER_MS = 1280L * 1000;

    @Test
    void firstBurstIsAllowedUpToTheBurstCap() {
        Pacer pacer = new Pacer(() -> ONE_PACKET_PER_MS);
        // maxBurst = max(4 * 1ms * rate, 10 * 1280) = max(5120, 12800) = 12800
        assertEquals(12800L, pacer.budget(0));
        assertTrue(pacer.hasBudgetForOnePacket(0));
        assertEquals(0, pacer.nanosUntilSend(0));
    }

    @Test
    void drainingTheBudgetBlocksUntilItRefills() {
        Pacer pacer = new Pacer(() -> ONE_PACKET_PER_MS);
        pacer.sentPacket(0, 12800);

        assertFalse(pacer.hasBudgetForOnePacket(0), "budget is exhausted right after the burst");
        assertEquals(NANOS_PER_MILLI, pacer.nanosUntilSend(0), "one datagram takes 1ms to accrue");

        assertFalse(pacer.hasBudgetForOnePacket(NANOS_PER_MILLI / 2));
        assertTrue(pacer.hasBudgetForOnePacket(NANOS_PER_MILLI));
        assertEquals(0, pacer.nanosUntilSend(NANOS_PER_MILLI));
    }

    @Test
    void budgetAccruesAtTheConfiguredRate() {
        long rate = 10_000_000L; // 10 MB/s
        Pacer pacer = new Pacer(() -> rate);
        pacer.sentPacket(0, 12800);

        // 100ms at 10MB/s would accrue 1_000_000 bytes, but the burst cap is
        // max(4 * 1ms * rate, 10 * 1280) = max(40000, 12800) = 40000.
        assertEquals(40_000L, pacer.budget(100 * NANOS_PER_MILLI));
    }

    @Test
    void burstCapPreventsUnboundedAccrualAfterIdle() {
        Pacer pacer = new Pacer(() -> ONE_PACKET_PER_MS);
        pacer.sentPacket(0, 12800);
        // Idle for a full second: without a cap the budget would be 1.28MB and the next 1000
        // packets would go out back-to-back, defeating the purpose of pacing.
        assertEquals(12800L, pacer.budget(NANOS_PER_SECOND));
    }

    @Test
    void zeroBandwidthStopsAccrualAndBlocksOnceDrained() {
        Pacer drained = new Pacer(() -> 0);
        drained.sentPacket(0, 12800);
        assertFalse(drained.hasBudgetForOnePacket(NANOS_PER_SECOND));
        assertEquals(Long.MAX_VALUE, drained.nanosUntilSend(NANOS_PER_SECOND));

        // Leftover budget stays spendable: a zero rate means no further accrual, not a hard stop.
        // hasBudgetForOnePacket and nanosUntilSend must agree, or the send loop would spin.
        Pacer withLeftover = new Pacer(() -> 0);
        withLeftover.sentPacket(0, 100);
        assertTrue(withLeftover.hasBudgetForOnePacket(0));
        assertEquals(0, withLeftover.nanosUntilSend(0));
    }

    @Test
    void nanosUntilSendIsRelativeToNowNotAbsolute() {
        Pacer pacer = new Pacer(() -> ONE_PACKET_PER_MS);
        pacer.sentPacket(1000 * NANOS_PER_MILLI, 12800);
        // Deadline is sendTime + 1ms; asking 0.5ms later must report only the remaining 0.5ms.
        assertEquals(NANOS_PER_MILLI / 2, pacer.nanosUntilSend(1000 * NANOS_PER_MILLI + NANOS_PER_MILLI / 2));
    }

    @Test
    void maxDatagramSizeScalesTheBurstCap() {
        Pacer pacer = new Pacer(() -> ONE_PACKET_PER_MS);
        pacer.setMaxDatagramSize(1500);
        // Burst cap becomes max(5120, 10 * 1500) = 15000, all of which is drained here.
        pacer.sentPacket(0, 15000);

        assertFalse(pacer.hasBudgetForOnePacket(0));
        assertEquals(1500L * NANOS_PER_SECOND / ONE_PACKET_PER_MS, pacer.nanosUntilSend(0));
    }
}
