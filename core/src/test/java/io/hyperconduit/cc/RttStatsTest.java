package io.hyperconduit.cc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RttStatsTest {

    private static final long MS = 1_000_000L;

    @Test
    void startsAtTheDefaultInitialRttWithNoMeasurement() {
        RttStats rtt = new RttStats();
        assertFalse(rtt.hasMeasurement());
        assertEquals(100 * MS, rtt.smoothedRttNanos());
        assertEquals(100 * MS, rtt.minRttNanos());
        assertEquals(0, rtt.meanDeviationNanos());
        // Without a sample the PTO is twice the default RTT, independent of maxAckDelay.
        assertEquals(200 * MS, rtt.ptoNanos(false));
        assertEquals(200 * MS, rtt.ptoNanos(true));
    }

    @Test
    void firstSampleSeedsSmoothingAndHalvesVariance() {
        RttStats rtt = new RttStats();
        rtt.updateRtt(100 * MS, 0);

        assertTrue(rtt.hasMeasurement());
        assertEquals(100 * MS, rtt.smoothedRttNanos());
        assertEquals(100 * MS, rtt.latestRttNanos());
        assertEquals(100 * MS, rtt.minRttNanos());
        assertEquals(50 * MS, rtt.meanDeviationNanos());
    }

    @Test
    void ptoIsSmoothedPlusFourVariancePlusMaxAckDelay() {
        RttStats rtt = new RttStats();
        rtt.setMaxAckDelayNanos(25 * MS);
        rtt.updateRtt(100 * MS, 0);

        // 100ms + max(4 * 50ms, 1ms) + 25ms
        assertEquals(325 * MS, rtt.ptoNanos(true));
        assertEquals(300 * MS, rtt.ptoNanos(false));
    }

    @Test
    void ptoVarianceTermIsFlooredAtTimerGranularity() {
        RttStats rtt = new RttStats();
        rtt.updateRtt(MS, 0); // 1ms RTT -> 0.5ms variance -> 4*variance = 2ms
        assertEquals(MS + 2 * MS, rtt.ptoNanos(false));

        RttStats jitterFree = new RttStats();
        jitterFree.updateRtt(MS, 0);
        jitterFree.updateRtt(MS, 0); // identical sample drives variance toward zero
        jitterFree.updateRtt(MS, 0);
        jitterFree.updateRtt(MS, 0);
        jitterFree.updateRtt(MS, 0);
        assertTrue(jitterFree.ptoNanos(false) >= MS + Constants.TIMER_GRANULARITY_NANOS,
                "variance term must never fall below timer granularity");
    }

    @Test
    void subsequentSamplesSmoothWithAlphaOneEighth() {
        RttStats rtt = new RttStats();
        rtt.updateRtt(100 * MS, 0);
        rtt.updateRtt(120 * MS, 0);

        assertEquals(120 * MS, rtt.latestRttNanos());
        assertEquals(100 * MS, rtt.minRttNanos(), "minRTT must not rise");
        // smoothed = 7/8 * 100ms + 1/8 * 120ms
        assertEquals(102_500_000L, rtt.smoothedRttNanos());
        // variance = 3/4 * 50ms + 1/4 * |100ms - 120ms|
        assertEquals(42_500_000L, rtt.meanDeviationNanos());
    }

    @Test
    void ackDelayIsSubtractedOnlyWhenItDoesNotUndercutMinRtt() {
        RttStats withRoom = new RttStats();
        withRoom.updateRtt(100 * MS, 0);
        withRoom.updateRtt(150 * MS, 30 * MS); // 150 - 100 = 50ms >= 30ms, so subtract
        assertEquals(120 * MS, withRoom.latestRttNanos());

        RttStats withoutRoom = new RttStats();
        withoutRoom.updateRtt(100 * MS, 0);
        withoutRoom.updateRtt(110 * MS, 30 * MS); // 110 - 100 = 10ms < 30ms, so keep the raw sample
        assertEquals(110 * MS, withoutRoom.latestRttNanos());
    }

    @Test
    void minRttUsesTheUncorrectedSample() {
        RttStats rtt = new RttStats();
        rtt.updateRtt(200 * MS, 100 * MS);
        // The first sample sets minRTT from the raw delta. Because minRTT then equals sendDelta,
        // the correction guard (sample - minRTT >= ackDelay) fails, so the sample stays raw too.
        assertEquals(200 * MS, rtt.minRttNanos());
        assertEquals(200 * MS, rtt.latestRttNanos());
    }

    @Test
    void nonPositiveSamplesAreIgnored() {
        RttStats rtt = new RttStats();
        rtt.updateRtt(100 * MS, 0);
        rtt.updateRtt(0, 0);
        rtt.updateRtt(-5 * MS, 0);
        assertEquals(100 * MS, rtt.latestRttNanos());
        assertEquals(100 * MS, rtt.smoothedRttNanos());
    }

    @Test
    void pathMigrationResetRestoresDefaultsButKeepsMaxAckDelay() {
        RttStats rtt = new RttStats();
        rtt.setMaxAckDelayNanos(25 * MS);
        rtt.updateRtt(10 * MS, 0);

        rtt.resetForPathMigration();
        assertFalse(rtt.hasMeasurement());
        assertEquals(100 * MS, rtt.smoothedRttNanos());
        assertEquals(100 * MS, rtt.minRttNanos(), "minRTT is reset to the default, not preserved");
        assertEquals(0, rtt.meanDeviationNanos());
        assertEquals(25 * MS, rtt.maxAckDelayNanos());
    }
}
