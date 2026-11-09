package io.hyperconduit.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Process-level tests for {@link ProbeProcess}. Every child is a local Java protocol fixture
 * ({@link ProbeFixture}), never the real Go probe implementation.
 */
class ProbeProcessTest {

    private static final long TIMEOUT_SECONDS = 5;

    @Test
    void oneProcessServesEveryDocumentedState(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "ready");
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long pid = process.pid();

            assertEquals("enabled", process.probe(server(1001)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals("disabled", process.probe(server(1002)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals("undeclared", process.probe(server(1003)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals("unknown", process.probe(server(1004)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals("incompatible", process.probe(server(1005)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            assertEquals(pid, process.pid(), "probes must reuse the same child process");
            assertTrue(process.isAlive());
        } finally {
            closeQuietly(process);
        }
        assertEquals(0, process.exit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue());
    }

    @Test
    void probeDetailsCarriesPolicyOnlyForValidDeclarations(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "ready");
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            ProbeProcess.Details enabled = process.probeDetails(server(1001)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals("enabled", enabled.state());
            assertTrue(enabled.declared());
            assertNotNull(enabled.capability());
            assertEquals(ProbeProcess.CAPABILITY_PROTOCOL, enabled.capability().protocol());
            assertTrue(enabled.capability().enabled());
            assertTrue(enabled.capability().tcpAllowed());
            assertFalse(enabled.capability().disableBrutal());
            assertTrue(enabled.capability().tokenRequired());

            ProbeProcess.Details disabled = process.probeDetails(server(1002)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals("disabled", disabled.state());
            assertNotNull(disabled.capability());
            assertFalse(disabled.capability().enabled());

            ProbeProcess.Details unknown = process.probeDetails(server(1004)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals("unknown", unknown.state());
            assertNull(unknown.capability(), "unknown must never expose policy");
            assertFalse(unknown.declared());
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void declarationRequiresCompleteCapabilityObject(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "missingCapability");
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            awaitFailure(process.probe(server(1001)), "protocol_capability");
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void capabilityObjectIsStrictlyValidated(@TempDir Path workingDirectory) throws Exception {
        for (String mode : List.of("badCapabilityProtocol", "duplicateCapabilityField",
                "unknownCapabilityField", "nonBooleanCapability")) {
            ProbeProcess process = start(workingDirectory, mode);
            try {
                process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                awaitFailure(process.probe(server(1001)), "protocol_invalid");
            } finally {
                closeQuietly(process);
            }
        }
    }

    @Test
    void capabilityObjectIsForbiddenWithoutADeclaration(@TempDir Path workingDirectory) throws Exception {
        for (String mode : List.of("capabilityOnUnknown", "capabilityWrongEnabled")) {
            ProbeProcess process = start(workingDirectory, mode);
            try {
                process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                awaitFailure(process.probe(server(1001)), "protocol_capability");
            } finally {
                closeQuietly(process);
            }
        }
    }

    @Test
    void probeDetailsCancellationSendsCancelCommand(@TempDir Path workingDirectory) throws Exception {
        Path marker = workingDirectory.resolve("probe-seen");
        ProbeProcess process = startCommand(workingDirectory, "cancelExits", marker.toString());
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            CompletableFuture<ProbeProcess.Details> result = process.probeDetails(server(1001));
            awaitFile(marker);

            assertTrue(result.cancel(true));
            assertEquals(0, process.exit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue());
            assertFalse(process.isAlive(), "the fixture exits only after receiving cancel");
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void probeIssuedBeforeReadyIsQueuedUntilReady(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "delayedReady");
        try {
            CompletableFuture<String> result = process.probe(server(1001));
            assertEquals("enabled", result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void startupTimeoutFailsReadyAndClosesChild(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "neverReady",
                Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofMillis(300));
        try {
            awaitFailure(process.ready(), "ready_timeout");
        } finally {
            closeQuietly(process);
        }
        assertFalse(process.isAlive());
    }

    @Test
    void crashBeforeReadyFailsWithExitCode(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "crash");
        try {
            awaitFailure(process.ready(), "exited_before_ready");
            assertEquals(7, process.exit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue());
        } finally {
            closeQuietly(process);
        }
        assertFalse(process.isAlive());
    }

    @Test
    void wrongProtocolVersionIsRejected(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "wrongVersion");
        try {
            awaitFailure(process.ready(), "protocol_version");
        } finally {
            closeQuietly(process);
        }
        assertFalse(process.isAlive());
    }

    @Test
    void duplicateReadyIsRejected(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "duplicateReady");
        try {
            // The first ready already completed ready(); the duplicate must surface as a
            // process failure instead of being silently ignored.
            SidecarException failure = process.failure().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals("protocol_duplicate_ready", failure.code());
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void unknownReadyFieldIsRejected(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "readyExtraField");
        try {
            awaitFailure(process.ready(), "protocol_invalid");
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void oversizedStdoutLineIsRejected(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "tooLong");
        try {
            awaitFailure(process.ready(), "protocol_line_too_long");
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void malformedJsonIsRejected(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "badJson");
        try {
            awaitFailure(process.ready(), "protocol_invalid");
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void invalidUtf8OutputIsRejected(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "invalidUtf8");
        try {
            awaitFailure(process.ready(), "protocol_invalid");
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void unknownResultStateFailsPendingProbe(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "badState");
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            awaitFailure(process.probe(server(1001)), "protocol_state");
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void duplicateResultFieldIsRejected(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "duplicateResultField");
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            awaitFailure(process.probe(server(1001)), "protocol_invalid");
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void unknownResultFieldIsRejected(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "unknownResultField");
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            awaitFailure(process.probe(server(1001)), "protocol_invalid");
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void resultForUnknownIdIsIgnored(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "unknownResultId");
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals("enabled", process.probe(server(1001)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(process.isAlive());
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void resultBeforeReadyIsRejected(@TempDir Path workingDirectory) throws Exception {
        Path marker = workingDirectory.resolve("probe-queued");
        ProbeProcess process = startCommand(workingDirectory, "resultBeforeReady", marker.toString());
        try {
            CompletableFuture<String> result = process.probe(server(1001));
            Files.writeString(marker, "queued");

            awaitFailure(result, "protocol_invalid");
            SidecarException failure = process.failure().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals("protocol_invalid", failure.code());
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void unexpectedExitFailsPendingProbe(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "exitOnProbe");
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            awaitFailure(process.probe(server(1001)), "unexpected_exit");
            assertEquals(9, process.exit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue());
        } finally {
            closeQuietly(process);
        }
        assertFalse(process.isAlive());
    }

    @Test
    void callerCancelSendsCancelCommand(@TempDir Path workingDirectory) throws Exception {
        Path marker = workingDirectory.resolve("probe-seen");
        ProbeProcess process = startCommand(workingDirectory, "cancelExits", marker.toString());
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            CompletableFuture<String> result = process.probe(server(1001));
            awaitFile(marker);

            assertTrue(result.cancel(true));
            assertEquals(0, process.exit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue());
            assertFalse(process.isAlive(), "the fixture exits only after receiving cancel");
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void probeTimeoutSendsCancelForAlreadyWrittenRequest(@TempDir Path workingDirectory) throws Exception {
        Path marker = workingDirectory.resolve("probe-seen");
        ProbeProcess process = startCommand(workingDirectory, "cancelExits", marker.toString(),
                Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofMillis(500));
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            CompletableFuture<String> result = process.probe(server(1001));
            awaitFile(marker);

            assertEquals("unknown", result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(0, process.exit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue());
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void probeTimeoutBeforeReadyDoesNotWriteCancel(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "neverReady",
                Duration.ofSeconds(5), Duration.ofMillis(200), Duration.ofMillis(300));
        try {
            assertEquals("unknown", process.probe(server(1001)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(process.isAlive(), "a queued request is dropped without a cancel command");
            assertFalse(process.ready().isDone());
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void pendingRequestsAreBoundedTo32(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "neverRespond");
        List<CompletableFuture<String>> accepted = new ArrayList<>();
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            for (int i = 0; i < 32; i++) {
                accepted.add(process.probe(server(1001)));
            }
            awaitFailure(process.probe(server(1001)), "limit");
            assertFalse(accepted.get(0).isDone());
        } finally {
            closeQuietly(process);
        }
        awaitFailure(accepted.get(0), "canceled");
    }

    @Test
    void noisyStderrIsDrainedAndNeverSurfaced(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "noisy");
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals("enabled", process.probe(server(1001)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertFalse(process.failure().isDone());
        } finally {
            closeQuietly(process);
        }
        assertEquals(0, process.exit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue());
    }

    @Test
    void rawStderrSecretIsNeverIncludedInFailure(@TempDir Path workingDirectory) throws Exception {
        // A 1 ms deadline fires long before a fresh JVM can emit ready, so the fixture's noisy
        // stderr is in flight while the process fails. The failure must stay code-only.
        ProbeProcess process = start(workingDirectory, "noisy",
                Duration.ofMillis(1), Duration.ofSeconds(5), Duration.ofMillis(300));
        try {
            SidecarException failure = awaitFailure(process.ready(), "ready_timeout");
            assertFalse(failure.getMessage().contains(ProbeFixture.SECRET));
            assertFalse(failure.toString().contains(ProbeFixture.SECRET));
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void unresponsiveChildIsForcedDownWithinBound(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "ignoreShutdown",
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMillis(300));
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            long started = System.nanoTime();
            process.close();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertTrue(elapsed.compareTo(Duration.ofSeconds(5)) < 0, () -> "close took " + elapsed);
            assertFalse(process.isAlive());
            process.exit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            process.close(); // close is idempotent.
        } finally {
            closeQuietly(process);
        }
    }

    @Test
    void probeAfterCloseFailsWithoutRestarting(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "ready");
        process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        process.close();

        awaitFailure(process.probe(server(1001)), "not_running");
        assertFalse(process.isAlive());
    }

    @Test
    void unresolvedServerAddressIsRejected(@TempDir Path workingDirectory) throws Exception {
        ProbeProcess process = start(workingDirectory, "ready");
        try {
            process.ready().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThrows(IllegalArgumentException.class,
                    () -> process.probe(InetSocketAddress.createUnresolved("example.invalid", 443)));
        } finally {
            closeQuietly(process);
        }
    }

    private static ProbeProcess start(Path workingDirectory, String mode) throws IOException {
        return ProbeProcess.startCommand(ProbeFixture.command(mode), workingDirectory);
    }

    private static ProbeProcess start(Path workingDirectory, String mode, Duration readyTimeout,
                                      Duration probeTimeout, Duration shutdownTimeout) throws IOException {
        return ProbeProcess.startCommand(ProbeFixture.command(mode), workingDirectory,
                readyTimeout, probeTimeout, shutdownTimeout);
    }

    private static ProbeProcess startCommand(Path workingDirectory, String mode,
                                             String... extra) throws IOException {
        return ProbeProcess.startCommand(ProbeFixture.command(mode, extra), workingDirectory);
    }

    private static ProbeProcess startCommand(Path workingDirectory, String mode, String marker,
                                             Duration readyTimeout, Duration probeTimeout,
                                             Duration shutdownTimeout) throws IOException {
        return ProbeProcess.startCommand(ProbeFixture.command(mode, marker), workingDirectory,
                readyTimeout, probeTimeout, shutdownTimeout);
    }

    private static InetSocketAddress server(int port) throws IOException {
        return new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port);
    }

    private static void awaitFile(Path marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (Files.exists(marker)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("fixture never observed the probe request");
    }

    private static SidecarException awaitFailure(CompletableFuture<?> future,
                                                 String expectedCode) throws Exception {
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        Throwable cause = thrown.getCause();
        assertInstanceOf(SidecarException.class, cause);
        SidecarException failure = (SidecarException) cause;
        assertEquals(expectedCode, failure.code());
        return failure;
    }

    private static void closeQuietly(ProbeProcess process) {
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
