package io.hyperconduit.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Process-level tests for the status sampler and safe stderr proxy diagnostics. */
class SidecarProcessStatusTest {

    private static final long TIMEOUT_SECONDS = 8;

    @Test
    void reportsLiveAndStoppedStatusWithoutTelemetry(@TempDir Path workingDirectory)
            throws Exception {
        SidecarProcess process = start(workingDirectory, "ready");
        try {
            process.ready().get(5, TimeUnit.SECONDS);

            ProcessStatus first = process.processStatus();
            assertEquals(process.pid(), first.pid());
            assertTrue(first.running());
            assertNotNull(first.startedAt());
            assertNotNull(first.uptime());
            assertFalse(first.uptime().isNegative());

            ProcessStatus sampled = awaitCpu(process, TIMEOUT_SECONDS * 1000);
            assertTrue(sampled.running());
            assertNotNull(sampled.cpuPercent());
            assertTrue(sampled.cpuPercent() >= 0.0 && sampled.cpuPercent() <= 100.0);
            if (sampled.residentMemoryBytes() != null) {
                assertTrue(sampled.residentMemoryBytes() > 0);
            }
            if (isLinux()) {
                assertNotNull(sampled.residentMemoryBytes(), "procfs should provide RSS");
            }
        } finally {
            closeQuietly(process);
        }

        ProcessStatus stopped = process.processStatus();
        assertFalse(stopped.running());
        assertEquals(process.pid(), stopped.pid());
        assertNull(stopped.uptime());
        assertNull(stopped.cpuPercent());
        assertNull(stopped.residentMemoryBytes());
    }

    @Test
    void statusPendingTracksTheFirstCpuSample(@TempDir Path workingDirectory) throws Exception {
        SidecarProcess process = start(workingDirectory, "ready");
        try {
            process.ready().get(5, TimeUnit.SECONDS);
            assertTrue(process.processStatusPending(), "sampling has not started yet");

            process.processStatus();
            ProcessStatus sampled = awaitCpu(process, TIMEOUT_SECONDS * 1000);
            assertNotNull(sampled.cpuPercent());
            assertFalse(process.processStatusPending(), "CPU is available, sampling is complete");
        } finally {
            closeQuietly(process);
        }
        assertFalse(process.processStatusPending(), "a stopped process is never pending");
    }

    @Test
    void validAndMalformedProxyLogsNeverKillTheProcess(@TempDir Path workingDirectory)
            throws Exception {
        for (String mode : List.of("proxyLog", "proxyLogInvalid")) {
            SidecarProcess process = start(workingDirectory, mode);
            try {
                SidecarProcess.Ready ready = process.ready().get(5, TimeUnit.SECONDS);
                assertEquals("client", ready.mode());
                assertTrue(process.isAlive());
                assertFalse(process.ready().isCompletedExceptionally());
            } finally {
                closeQuietly(process);
            }
            assertEquals(0, process.exit().get(5, TimeUnit.SECONDS).intValue());
        }
    }

    private static SidecarProcess start(Path workingDirectory, String mode) throws IOException {
        return SidecarProcess.startCommand(ProcessFixture.command(mode), "client",
                workingDirectory, Duration.ofSeconds(5), Duration.ofSeconds(2));
    }

    private static ProcessStatus awaitCpu(SidecarProcess process, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            ProcessStatus status = process.processStatus();
            if (status.cpuPercent() != null) return status;
            Thread.sleep(25);
        }
        fail("CPU sample did not arrive");
        return null;
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static void closeQuietly(SidecarProcess process) {
        try {
            process.close();
        } catch (RuntimeException ignored) {
            // Cleanup must not mask the assertion that failed.
        }
    }
}
