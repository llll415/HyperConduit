package io.hyperconduit.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real {@link SidecarProcess} tests against {@link TelemetryFixture}. No
 * Minecraft, no screenshots, and no real sidecar binary are involved.
 */
class SidecarProcessTelemetryTest {

    private static final long TIMEOUT_SECONDS = 5;

    @Test
    void telemetryIsOptInAndIdempotent(@TempDir Path workingDirectory) throws Exception {
        SidecarProcess process = start(workingDirectory, "observe");
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNull(process.telemetry(), "telemetry must stay off until requested");

            process.enableTelemetry();
            process.enableTelemetry();
            TelemetrySnapshot snapshot = awaitTelemetry(process, 5_000);
            assertTrue(snapshot.available());
            assertFalse(snapshot.truncated());
            assertEquals(2, snapshot.connections().size());

            TelemetrySnapshot.Connection first = snapshot.connections().get(1L);
            assertNotNull(first);
            assertEquals("bbr", first.congestion());
            assertEquals(5_000_000, first.smoothedRttNanos());
            assertEquals(1_000, first.bytesSent());
            assertEquals(2_000, first.bytesReceived());

            TelemetrySnapshot withRates = awaitRates(process, 5_000);
            TelemetrySnapshot.Connection rated = withRates.connections().get(1L);
            assertTrue(rated.ratesAvailable());
            assertTrue(rated.sentBytesPerSecond() > 0);
            assertTrue(rated.receivedBytesPerSecond() > 0);

            process.close();
            assertNull(process.telemetry(), "a stopped process must not expose telemetry");
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void invalidStatsDisablesTelemetryWithoutKillingTheProcess(@TempDir Path workingDirectory)
            throws Exception {
        SidecarProcess process = start(workingDirectory, "invalidStats");
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            process.enableTelemetry();
            Thread.sleep(600);
            assertTrue(process.isAlive());
            assertNull(process.telemetry());
            assertFalse(process.ready().isCompletedExceptionally());
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void staleTelemetryDisappearsWithoutStoppingTheProcess(@TempDir Path workingDirectory)
            throws Exception {
        SidecarProcess process = start(workingDirectory, "stale");
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            process.enableTelemetry();
            assertNotNull(awaitTelemetry(process, 5_000));
            Thread.sleep(TelemetrySnapshot.STALE_NANOS / 1_000_000 + 300);
            assertNull(process.telemetry());
            assertTrue(process.isAlive());
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void processCrashClearsOldTelemetry(@TempDir Path workingDirectory) throws Exception {
        SidecarProcess process = start(workingDirectory, "crashAfterStats");
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            process.enableTelemetry();
            assertNotNull(awaitTelemetry(process, 5_000));
            assertEquals(9, process.exit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue());
            assertNull(process.telemetry(), "a dead process must never expose old telemetry");
        } finally {
            closeQuietly(process);
        }
    }

    private static SidecarProcess start(Path workingDirectory, String mode) throws IOException {
        return SidecarProcess.startCommand(TelemetryFixture.command(mode), "client",
                workingDirectory, Duration.ofSeconds(5), Duration.ofSeconds(2));
    }

    private static TelemetrySnapshot awaitTelemetry(SidecarProcess process, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            TelemetrySnapshot snapshot = process.telemetry();
            if (snapshot != null) return snapshot;
            Thread.sleep(25);
        }
        fail("telemetry did not arrive");
        return null;
    }

    private static TelemetrySnapshot awaitRates(SidecarProcess process, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            TelemetrySnapshot snapshot = process.telemetry();
            if (snapshot != null && snapshot.connections().values().stream()
                    .anyMatch(TelemetrySnapshot.Connection::ratesAvailable)) {
                return snapshot;
            }
            Thread.sleep(25);
        }
        fail("telemetry rates did not arrive");
        return null;
    }

    private static void closeQuietly(SidecarProcess process) {
        try {
            process.close();
        } catch (RuntimeException ignored) {
            // Cleanup must not mask the assertion that failed.
        }
    }
}
