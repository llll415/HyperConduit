package io.hyperconduit.sidecar;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Unit and live tests for the process status record, RSS parsers, and sampler. */
class ProcessStatusTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void recordExposesNullableResourceSamples() {
        ProcessStatus status = new ProcessStatus(42, true, START, Duration.ofSeconds(3),
                12.5, 1024L);

        assertEquals(42, status.pid());
        assertTrue(status.running());
        assertEquals(START, status.startedAt());
        assertEquals(Duration.ofSeconds(3), status.uptime());
        assertEquals(12.5, status.cpuPercent(), 0.0001);
        assertEquals(1024L, status.residentMemoryBytes().longValue());
        assertTrue(status.cpuAvailable());
        assertTrue(status.memoryAvailable());
    }

    @Test
    void unavailableSamplesStayNullInsteadOfZero() {
        ProcessStatus status = new ProcessStatus(42, false, null, null, null, null);

        assertNull(status.cpuPercent());
        assertNull(status.residentMemoryBytes());
        assertFalse(status.cpuAvailable());
        assertFalse(status.memoryAvailable());
    }

    @Test
    void recordRejectsImpossibleValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessStatus(0, true, START, Duration.ZERO, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessStatus(1, true, START, Duration.ofMillis(-1), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessStatus(1, true, START, Duration.ZERO, -0.1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessStatus(1, true, START, Duration.ZERO, 100.1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessStatus(1, true, START, Duration.ZERO, Double.NaN, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessStatus(1, true, START, Duration.ZERO, null, -1L));
    }

    @Test
    void cpuPercentIsNormalizedToAllLogicalProcessors() {
        long second = Duration.ofSeconds(1).toNanos();

        assertEquals(100.0,
                ProcessStatusSampler.cpuPercent(second, second, 1), 0.0001);
        assertEquals(25.0,
                ProcessStatusSampler.cpuPercent(second, second, 4), 0.0001);
        assertEquals(50.0,
                ProcessStatusSampler.cpuPercent(second, second * 2, 1), 0.0001);
        assertEquals(100.0,
                ProcessStatusSampler.cpuPercent(second * 8, second, 8), 0.0001);
        assertEquals(100.0,
                ProcessStatusSampler.cpuPercent(second * 10, second, 4), 0.0001);
        assertEquals(0.0,
                ProcessStatusSampler.cpuPercent(0, second, 4), 0.0001);

        assertNull(ProcessStatusSampler.cpuPercent(-1, second, 4));
        assertNull(ProcessStatusSampler.cpuPercent(second, 0, 4));
        assertNull(ProcessStatusSampler.cpuPercent(second, second, 0));
    }

    @Test
    void parsesLinuxStatusAndMacPsOutput() {
        assertEquals(2048L * 1024, ProcessMemory.parseLinuxStatus(
                "Name:\tjava\nVmRSS:\t  2048 kB\n").longValue());
        assertNull(ProcessMemory.parseLinuxStatus("Name:\tjava\n"));
        assertNull(ProcessMemory.parseLinuxStatus("VmRSS:\t not-a-number kB"));

        assertEquals(1234L * 1024, ProcessMemory.parsePsRss(" 1234\n").longValue());
        assertNull(ProcessMemory.parsePsRss(""));
        assertNull(ProcessMemory.parsePsRss("not-a-number"));
    }

    @Test
    void parsesWindowsTasklistCsvWithLocalizedSeparators() {
        assertEquals(12_345L * 1024, ProcessMemory.parseTasklistCsv(
                "\"java.exe\",\"1234\",\"Console\",\"1\",\"12,345 K\"\r\n", 1234).longValue());
        assertEquals(12_345L * 1024, ProcessMemory.parseTasklistCsv(
                "\"java.exe\",\"1234\",\"Console\",\"1\",\"12.345 K\"\r\n", 1234).longValue());
        assertEquals(12_345L * 1024, ProcessMemory.parseTasklistCsv(
                "\"a,b.exe\",\"1234\",\"Console\",\"1\",\"12 345 K\"\r\n", 1234).longValue());
        assertNull(ProcessMemory.parseTasklistCsv(
                "\"java.exe\",\"999\",\"Console\",\"1\",\"12,345 K\"\r\n", 1234));
        assertNull(ProcessMemory.parseTasklistCsv(
                "INFO: No tasks are running which match the specified criteria.\r\n", 1234));
        assertNull(ProcessMemory.parseTasklistCsv(
                "\"java.exe\",\"1234\",\"Console\",\"1\",\"N/A\"\r\n", 1234));
    }

    @Test
    void readsResidentMemoryForTheCurrentProcessWhenSupported() {
        Long rss = ProcessMemory.read(ProcessHandle.current().pid());
        if (!ProcessMemory.commandBased()) {
            assertNotNull(rss, "procfs should provide RSS on Linux");
            assertTrue(rss > 0);
        } else {
            // Command probes are best-effort in restricted environments.
            assertTrue(rss == null || rss > 0);
        }
    }

    @Test
    void samplerCollectsCpuOffTheCallerThread() throws Exception {
        ProcessHandle handle = ProcessHandle.current();
        ProcessStatusSampler sampler = new ProcessStatusSampler(handle, handle.pid(),
                handle.info().startInstant().orElse(null),
                Duration.ofMillis(50), Duration.ofSeconds(1));
        try {
            sampler.start();
            sampler.start(); // idempotent

            ProcessStatus status = awaitCpu(sampler, 5_000);
            assertTrue(status.running());
            assertEquals(handle.pid(), status.pid());
            assertNotNull(status.cpuPercent());
            assertTrue(status.cpuPercent() >= 0.0 && status.cpuPercent() <= 100.0);
            if (status.startedAt() != null) assertNotNull(status.uptime());
        } finally {
            sampler.stop();
        }
    }

    private static ProcessStatus awaitCpu(ProcessStatusSampler sampler, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            ProcessStatus status = sampler.current();
            if (status.cpuPercent() != null) return status;
            Thread.sleep(10);
        }
        fail("CPU sample did not arrive");
        return null;
    }
}
