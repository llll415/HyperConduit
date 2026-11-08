package io.hyperconduit.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Process-level tests for {@link SidecarProcess}. Every child is a local Java protocol fixture
 * ({@link ProcessFixture}), never a real sidecar server.
 */
class SidecarProcessTest {

    private static final long TIMEOUT_SECONDS = 5;

    @Test
    void readyIsParsedAndCloseIsGracefulAndIdempotent(@TempDir Path workingDirectory) throws Exception {
        SidecarProcess process = start(workingDirectory, "ready",
                Duration.ofSeconds(5), Duration.ofSeconds(2));
        try {
            SidecarProcess.Ready ready = process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(1, ready.version());
            assertEquals("client", ready.mode());
            assertEquals("127.0.0.1", ready.listen().getAddress().getHostAddress());
            assertEquals(12345, ready.listen().getPort());
            assertEquals(ProcessFixture.FINGERPRINT, ready.fingerprint());

            assertTrue(process.isAlive());
            assertTrue(process.pid() > 0);
        } finally {
            closeQuietly(process);
        }

        // close() must be idempotent and the child must have exited cleanly.
        process.close();
        assertEquals(0, process.exit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue());
        assertFalse(process.isAlive());
    }

    @Test
    void startupTimeoutKillsChild(@TempDir Path workingDirectory) throws Exception {
        SidecarProcess process = start(workingDirectory, "neverReady",
                Duration.ofMillis(250), Duration.ofSeconds(2));
        try {
            awaitFailure(process.ready(), "startup_timeout");
            assertEquals(0, process.exit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue());
        } finally {
            closeQuietly(process);
        }
        assertFalse(process.isAlive());
    }

    @Test
    void cancellingReadyFutureStopsChild(@TempDir Path workingDirectory) throws Exception {
        SidecarProcess process = start(workingDirectory, "neverReady",
                Duration.ofSeconds(5), Duration.ofSeconds(2));
        try {
            assertTrue(process.ready().cancel(true));
            process.exit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void crashBeforeReadyFailsReadyWithExitCode(@TempDir Path workingDirectory) throws Exception {
        SidecarProcess process = start(workingDirectory, "crash",
                Duration.ofSeconds(5), Duration.ofSeconds(2));
        try {
            awaitFailure(process.ready(), "exited_before_ready");
            assertEquals(7, process.exit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue());
        } finally {
            closeQuietly(process);
        }
        assertFalse(process.isAlive());
    }

    @Test
    void structuredStderrErrorMapsToIdentityCode(@TempDir Path workingDirectory) throws Exception {
        SidecarProcess process = start(workingDirectory, "error",
                Duration.ofSeconds(5), Duration.ofSeconds(2));
        try {
            SidecarException failure = awaitFailure(process.ready(), "identity_changed");
            assertFalse(failure.getMessage().contains(ProcessFixture.SECRET));
            process.exit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            closeQuietly(process);
        }
        assertFalse(process.isAlive());
    }

    @Test
    void wrongProtocolVersionIsRejected(@TempDir Path workingDirectory) throws Exception {
        SidecarProcess process = start(workingDirectory, "wrongVersion",
                Duration.ofSeconds(5), Duration.ofSeconds(2));
        try {
            awaitFailure(process.ready(), "protocol_version");
        } finally {
            closeQuietly(process);
        }
        assertFalse(process.isAlive());
    }

    @Test
    void nonLoopbackClientListenerIsRejected(@TempDir Path workingDirectory) throws Exception {
        SidecarProcess process = start(workingDirectory, "badClientAddr",
                Duration.ofSeconds(5), Duration.ofSeconds(2));
        try {
            awaitFailure(process.ready(), "protocol_invalid");
        } finally {
            closeQuietly(process);
        }
        assertFalse(process.isAlive());
    }

    @Test
    void oversizedStdoutLineIsRejected(@TempDir Path workingDirectory) throws Exception {
        SidecarProcess process = start(workingDirectory, "tooLong",
                Duration.ofSeconds(5), Duration.ofSeconds(2));
        try {
            awaitFailure(process.ready(), "protocol_line_too_long");
        } finally {
            closeQuietly(process);
        }
        assertFalse(process.isAlive());
    }

    @Test
    void noisyStderrIsDrainedWithoutDeadlock(@TempDir Path workingDirectory) throws Exception {
        SidecarProcess process = start(workingDirectory, "noisy",
                Duration.ofSeconds(5), Duration.ofSeconds(2));
        try {
            SidecarProcess.Ready ready = process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals("client", ready.mode());
            assertFalse(process.ready().isCompletedExceptionally());
        } finally {
            closeQuietly(process);
        }
        assertEquals(0, process.exit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue());
        assertFalse(process.isAlive());
    }

    @Test
    void rawStderrSecretIsNeverIncludedInFailure(@TempDir Path workingDirectory) throws Exception {
        // A 1 ms deadline fires long before a fresh JVM can emit its ready event, so the fixture's
        // noisy stderr is in flight while the process fails. The failure must stay code-only.
        SidecarProcess process = start(workingDirectory, "noisy",
                Duration.ofMillis(1), Duration.ofSeconds(2));
        try {
            SidecarException failure = awaitFailure(process.ready(), "startup_timeout");
            assertFalse(failure.getMessage().contains(ProcessFixture.SECRET));
            assertFalse(failure.toString().contains(ProcessFixture.SECRET));
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void unresponsiveChildIsForcedDownWithinBound(@TempDir Path workingDirectory) throws Exception {
        SidecarProcess process = start(workingDirectory, "ignoreShutdown",
                Duration.ofSeconds(5), Duration.ofMillis(500));
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            long started = System.nanoTime();
            process.close();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertTrue(elapsed.compareTo(Duration.ofSeconds(5)) < 0, () -> "close took " + elapsed);
            assertFalse(process.isAlive());
            process.exit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void concurrentChildrenAreIndependent(@TempDir Path workingDirectory) throws Exception {
        SidecarProcess first = start(workingDirectory, "ready",
                Duration.ofSeconds(5), Duration.ofSeconds(2));
        SidecarProcess second = start(workingDirectory, "ready",
                Duration.ofSeconds(5), Duration.ofSeconds(2));
        try {
            assertEquals("client", first.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).mode());
            assertEquals("client", second.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).mode());

            first.close();
            assertFalse(first.isAlive());
            assertTrue(second.isAlive());
            assertEquals("client", second.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).mode());
        } finally {
            closeQuietly(first);
            closeQuietly(second);
        }
        assertFalse(second.isAlive());
    }

    private static SidecarProcess start(Path workingDirectory, String mode,
                                        Duration startupTimeout, Duration stopTimeout) throws IOException {
        return SidecarProcess.startCommand(ProcessFixture.command(mode), "client",
                workingDirectory, startupTimeout, stopTimeout);
    }

    private static SidecarException awaitFailure(CompletableFuture<SidecarProcess.Ready> ready,
                                                 String expectedCode) throws Exception {
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> ready.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        Throwable cause = thrown.getCause();
        assertInstanceOf(SidecarException.class, cause);
        SidecarException failure = (SidecarException) cause;
        assertEquals(expectedCode, failure.code());
        return failure;
    }

    private static void closeQuietly(SidecarProcess process) {
        try {
            process.close();
        } catch (RuntimeException ignored) {
            // Cleanup must not mask the assertion that failed.
        }
        try {
            if (process.isAlive()) {
                process.exit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // The process is already gone or being torn down asynchronously.
        }
    }
}