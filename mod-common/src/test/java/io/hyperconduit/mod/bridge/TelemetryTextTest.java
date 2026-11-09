package io.hyperconduit.mod.bridge;

import io.hyperconduit.sidecar.TelemetrySnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The label is deliberately about local QUIC transport bytes, never Minecraft payload. The text
 * must also distinguish a Brutal target rate from a sample that has not been collected yet.
 */
class TelemetryTextTest {

    @Test
    void ratesAreRenderedAsMegabitsPerSecond() {
        TelemetrySnapshot.Connection metrics =
                metrics("bbr", 0L, 1_250_000L, true, 1_000_000d, 2_000_000d);

        assertEquals("本地发送 BBR | QUIC RTT 1.3 ms | 发送 8.00 Mbps | 接收 16.00 Mbps",
                TelemetryText.describe(metrics));
    }

    @Test
    void brutalDisplaysItsConfiguredTargetRate() {
        String text = TelemetryText.describe(
                metrics("brutal", 1_250_000L, 10_000_000L, true, 0d, 0d));

        assertTrue(text.contains("BRUTAL（目标 10.00 Mbps）"), text);
    }

    @Test
    void bbrAndRenoNeverDisplayABrutalTarget() {
        for (String congestion : new String[]{"bbr", "reno"}) {
            String text = TelemetryText.describe(
                    metrics(congestion, 1_250_000L, 10_000_000L, true, 1_000_000d, 1_000_000d));

            assertFalse(text.contains("目标"), text);
            assertTrue(text.contains(congestion.toUpperCase(java.util.Locale.ROOT)), text);
        }
    }

    @Test
    void firstFrameBeforeRateSamplingShowsPendingInsteadOfZero() {
        String text = TelemetryText.describe(metrics("bbr", 0L, 0L, false, 0d, 0d));

        assertTrue(text.contains("速率待采样"), text);
        assertFalse(text.contains("0.00 Mbps"), text);
        assertTrue(text.contains("QUIC RTT 待采样"), text);
    }

    @Test
    void unknownMetricsAreReportedAsUnavailable() {
        assertEquals("指标暂不可用", TelemetryText.describe(null));
    }

    private static TelemetrySnapshot.Connection metrics(String congestion, long targetBytesPerSecond,
                                                        long smoothedRttNanos, boolean ratesAvailable,
                                                        double sentBytesPerSecond,
                                                        double receivedBytesPerSecond) {
        return new TelemetrySnapshot.Connection(1L, congestion, targetBytesPerSecond, smoothedRttNanos,
                0L, 0L, ratesAvailable, sentBytesPerSecond, receivedBytesPerSecond);
    }
}
