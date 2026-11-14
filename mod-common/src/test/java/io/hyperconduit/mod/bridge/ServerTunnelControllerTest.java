package io.hyperconduit.mod.bridge;

import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import io.hyperconduit.mod.probe.Capability;
import io.hyperconduit.mod.probe.CapabilityListenerRegistry;
import io.hyperconduit.sidecar.ControllerProcesses;
import io.hyperconduit.sidecar.SidecarException;
import io.hyperconduit.sidecar.SidecarProcess;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle tests for {@link ServerTunnelController}. A real managed child process (a tiny Java
 * fixture) exercises READY, controlled stop and unexpected exit; the launcher seam lets a test
 * pause a restart so stop/restart races can be forced deterministically.
 */
class ServerTunnelControllerTest {

    private static final String SECRET = "0123456789abcdef".repeat(4);
    private static final InetSocketAddress LISTEN = new InetSocketAddress("127.0.0.1", 25565);
    private static final InetSocketAddress BACKEND = new InetSocketAddress("127.0.0.1", 54321);
    private static final long TIMEOUT_SECONDS = 10;

    @TempDir Path workingDirectory;

    @Test
    void restartDoesNotStartASidecarThatWasNeverRunning() throws Exception {
        RecordingLauncher launcher = new RecordingLauncher(workingDirectory);
        CapabilityListenerRegistry registry = new CapabilityListenerRegistry();
        EmbeddedChannel backend = new EmbeddedChannel();
        ServerTunnelController controller = new ServerTunnelController(backend, BACKEND, LISTEN, true, SECRET,
                registry.add(null, false, false), new AtomicReference<>(), launcher);
        try {
            var result = controller.restart().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(ServerTunnelController.Status.NOT_RUNNING, result.status());
            assertEquals("not_running", result.code());
            assertEquals("[HyperConduit] 隧道程序当前未运行。", result.message());
            assertEquals(0, launcher.calls(), "restart must not start an absent sidecar");
            assertTrue(backend.isOpen());
            assertNull(controller.process());
        } finally {
            controller.close();
            backend.finishAndReleaseAll();
        }
    }

    @Test
    void invalidNewConfigLeavesTheOldProcessAndBackendUntouched() throws Exception {
        HyperConduitConfig first = config(false, "");
        installConfig(first);
        RecordingLauncher launcher = new RecordingLauncher(workingDirectory);
        Harness harness = start(launcher, first, false);
        SidecarProcess old = harness.controller().process();
        assertNotNull(old);
        assertTrue(old.isAlive());
        try {
            // save() validates, so an invalid file must be written raw.
            writeRawConfig("{not valid json");

            var result = harness.controller().restart().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(ServerTunnelController.Status.INVALID_CONFIG, result.status());
            assertEquals("config_invalid", result.code());
            assertEquals("[HyperConduit] 无法重启：配置无效，当前隧道程序未停止。\n"
                    + "原因：配置文件格式无效，请检查 JSON 内容。", result.message());
            assertSame(old, harness.controller().process(), "validation failure must not replace the process");
            assertTrue(old.isAlive());
            assertTrue(harness.backend().isOpen(), "validation failure must not close the backend");
            assertEquals(1, launcher.calls(), "no replacement may be launched");
            assertTrue(harness.registry().declaration().enabled());
        } finally {
            harness.controller().close();
            harness.backend().finishAndReleaseAll();
            launcher.closeAll();
        }
    }

    @Test
    void restartKeepsBackendAndSecretAndPublishesTheNewPolicy() throws Exception {
        HyperConduitConfig first = config(false, "");
        installConfig(first);
        RecordingLauncher launcher = new RecordingLauncher(workingDirectory);
        Harness harness = start(launcher, first, false);
        SidecarProcess old = harness.controller().process();
        try {
            HyperConduitConfig second = config(true, "s3cret");
            installConfig(second);

            var result = harness.controller().restart().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(ServerTunnelController.Status.RESTARTED, result.status());
            assertTrue(result.restarted());
            assertEquals("[HyperConduit] 隧道程序已就绪。", result.message());

            SidecarProcess replacement = harness.controller().process();
            assertNotNull(replacement);
            assertNotSame(old, replacement);
            assertTrue(replacement.isAlive());
            assertTrue(waitFor(() -> !old.isAlive()), "the old sidecar must be stopped");
            assertTrue(harness.backend().isOpen(), "a controlled restart must keep the private backend");

            assertEquals(2, launcher.calls());
            assertEquals(launcher.secrets().get(0), launcher.secrets().get(1),
                    "the bridge secret belongs to the controller and must survive a restart");
            assertTrue(launcher.secrets().get(0).matches("[0-9a-f]{64}"));

            HyperConduitConfig running = harness.controller().runningConfig();
            assertNotNull(running);
            assertTrue(running.disableBrutal);
            assertEquals("s3cret", running.serverToken);
            assertEquals(LISTEN, harness.controller().listenAddress());
            assertEquals(BACKEND, harness.controller().backendAddress());

            var declaration = harness.registry().declaration();
            assertEquals(Capability.ENABLED, declaration.state());
            assertTrue(declaration.disableBrutal(), "discovery must report the replacement's actual policy");
            assertTrue(declaration.tokenRequired());
        } finally {
            harness.controller().close();
            harness.backend().finishAndReleaseAll();
            launcher.closeAll();
        }
    }

    @Test
    void unexpectedExitCleansTheBackendButKeepsALivePublicTcpFact() throws Exception {
        HyperConduitConfig first = config(false, "token");
        installConfig(first);
        RecordingLauncher launcher = new RecordingLauncher(workingDirectory);
        Harness harness = start(launcher, first, true);
        SidecarProcess process = harness.controller().process();
        try {
            var before = harness.registry().declaration();
            assertEquals(Capability.ENABLED, before.state());
            assertTrue(before.tcpAllowed());

            // Closing from the test is not a controller-controlled exit, so it exercises the
            // unexpected-exit cleanup path.
            process.close();

            assertTrue(waitFor(() -> harness.controller().process() == null));
            assertTrue(waitFor(() -> !harness.backend().isOpen()));
            var after = harness.registry().declaration();
            assertEquals(Capability.DISABLED, after.state());
            assertTrue(after.tcpAllowed(), "the public TCP listener is still alive");

            var result = harness.controller().restart().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(ServerTunnelController.Status.NOT_RUNNING, result.status(),
                    "an unexpectedly stopped tunnel must not be restarted implicitly");
            assertEquals("[HyperConduit] 隧道程序当前未运行。", result.message());
            assertEquals(1, launcher.calls());
        } finally {
            harness.controller().close();
            if (harness.publicTcp() != null) harness.publicTcp().finishAndReleaseAll();
            harness.backend().finishAndReleaseAll();
            launcher.closeAll();
        }
    }

    @Test
    void closeDuringRestartCannotPublishOrLeakTheReplacement() throws Exception {
        HyperConduitConfig first = config(false, "");
        installConfig(first);
        RecordingLauncher launcher = new RecordingLauncher(workingDirectory);
        Harness harness = start(launcher, first, false);
        try {
            launcher.gateNextCall(2);
            CompletableFuture<ServerTunnelController.Result> restart = harness.controller().restart();
            assertTrue(waitFor(() -> launcher.calls() == 2), "the restart must reach the launcher");

            harness.controller().close();
            launcher.releaseGate();

            var result = restart.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(ServerTunnelController.Status.SERVER_STOPPING, result.status());
            assertEquals("[HyperConduit] 服务端正在停止，未执行重启。", result.message());
            assertNull(harness.controller().process());
            assertFalse(harness.backend().isOpen());
            assertTrue(waitFor(() -> launcher.started().stream().noneMatch(SidecarProcess::isAlive)),
                    "a child created after close must be stopped, not leaked");
        } finally {
            launcher.releaseGate();
            harness.controller().close();
            harness.backend().finishAndReleaseAll();
            launcher.closeAll();
        }
    }

    @Test
    void restartFailureReportsTheCodeAndKeepsTheBackendForDiagnostics() throws Exception {
        HyperConduitConfig first = config(false, "");
        installConfig(first);
        RecordingLauncher launcher = new RecordingLauncher(workingDirectory);
        Harness harness = start(launcher, first, false);
        try {
            launcher.failNextCall(2);
            var result = harness.controller().restart().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(ServerTunnelController.Status.FAILED, result.status());
            assertEquals("launch_failed", result.code());
            assertEquals("[HyperConduit] 隧道程序启动失败。\n"
                    + "原因：无法启动隧道进程，请检查系统权限或安全软件。", result.message());
            assertNull(harness.controller().process());
            assertTrue(harness.backend().isOpen());
            assertEquals(Capability.DISABLED, harness.registry().declaration().state());

            var second = harness.controller().restart().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(ServerTunnelController.Status.NOT_RUNNING, second.status());
            assertEquals(2, launcher.calls(), "a failed replacement must not trigger an automatic retry");
        } finally {
            harness.controller().close();
            harness.backend().finishAndReleaseAll();
            launcher.closeAll();
        }
    }

    @Test
    void concurrentRestartRequestsAreRejectedWithBusy() throws Exception {
        HyperConduitConfig first = config(false, "");
        installConfig(first);
        RecordingLauncher launcher = new RecordingLauncher(workingDirectory);
        Harness harness = start(launcher, first, false);
        try {
            launcher.gateNextCall(2);
            CompletableFuture<ServerTunnelController.Result> firstRequest = harness.controller().restart();
            assertTrue(waitFor(() -> launcher.calls() == 2));

            var busy = harness.controller().restart().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(ServerTunnelController.Status.BUSY, busy.status());
            assertEquals("busy", busy.code());
            assertEquals("[HyperConduit] 隧道程序正在启动或停止，请稍后再试。", busy.message());
            assertEquals(2, launcher.calls(), "a duplicate request must not launch another process");

            launcher.releaseGate();
            var result = firstRequest.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(ServerTunnelController.Status.RESTARTED, result.status());
            assertEquals(2, launcher.calls(), "only the first request may restart the sidecar");
        } finally {
            launcher.releaseGate();
            harness.controller().close();
            harness.backend().finishAndReleaseAll();
            launcher.closeAll();
        }
    }

    @Test
    void disabledConfigIsRejectedWithoutTouchingTheRunningTunnel() throws Exception {
        HyperConduitConfig first = config(false, "");
        installConfig(first);
        RecordingLauncher launcher = new RecordingLauncher(workingDirectory);
        Harness harness = start(launcher, first, false);
        SidecarProcess old = harness.controller().process();
        try {
            HyperConduitConfig disabled = first.copy();
            disabled.enabled = false;
            installConfig(disabled);

            var result = harness.controller().restart().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(ServerTunnelController.Status.INVALID_CONFIG, result.status());
            assertEquals("disabled", result.code());
            assertEquals("[HyperConduit] 无法重启：配置无效，当前隧道程序未停止。\n"
                    + "原因：HyperConduit 已禁用，需要重启服务端才能生效。", result.message());
            assertSame(old, harness.controller().process(), "an enable/disable change is not reloadable");
            assertTrue(old.isAlive());
            assertTrue(harness.backend().isOpen(), "the backend must stay until the server restarts");
            assertEquals(1, launcher.calls());
        } finally {
            harness.controller().close();
            harness.backend().finishAndReleaseAll();
            launcher.closeAll();
        }
    }

    @Test
    void closeStopsTheRunningChildAndBackend() throws Exception {
        HyperConduitConfig first = config(false, "");
        installConfig(first);
        RecordingLauncher launcher = new RecordingLauncher(workingDirectory);
        Harness harness = start(launcher, first, false);
        SidecarProcess process = harness.controller().process();
        try {
            harness.controller().close();
            assertNull(harness.controller().process());
            assertFalse(harness.backend().isOpen());
            assertTrue(waitFor(() -> !process.isAlive()));
            var result = harness.controller().restart().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(ServerTunnelController.Status.NOT_RUNNING, result.status());
            assertEquals(Capability.DISABLED, harness.registry().declaration().state(),
                    "a stopped controller must not be revived by a late sidecar callback");
        } finally {
            harness.controller().close();
            harness.backend().finishAndReleaseAll();
            launcher.closeAll();
        }
    }

    private Harness start(RecordingLauncher launcher, HyperConduitConfig config, boolean publicTcp) throws Exception {
        CapabilityListenerRegistry registry = new CapabilityListenerRegistry();
        EmbeddedChannel backend = new EmbeddedChannel();
        EmbeddedChannel publicChannel = publicTcp ? new EmbeddedChannel() : null;
        CapabilityListenerRegistry.Handle handle = publicChannel == null
                ? registry.add(null, config.disableBrutal, !config.serverToken.isEmpty())
                : registry.add(publicChannel.newSucceededFuture(), config.disableBrutal,
                        !config.serverToken.isEmpty());
        ServerTunnelController controller = new ServerTunnelController(backend, BACKEND, LISTEN,
                publicChannel != null, SECRET, handle, new AtomicReference<>(), launcher);
        controller.start(config);
        return new Harness(controller, backend, publicChannel, registry);
    }

    private void installConfig(HyperConduitConfig config) {
        config.save(workingDirectory);
        ConfigHolder.install(workingDirectory);
    }

    private void writeRawConfig(String json) {
        try {
            Files.writeString(workingDirectory.resolve("hyperconduit-hy2.json"), json, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static HyperConduitConfig config(boolean disableBrutal, String serverToken) {
        HyperConduitConfig config = new HyperConduitConfig();
        config.enabled = true;
        config.disableBrutal = disableBrutal;
        config.serverToken = serverToken;
        config.connectTimeoutMs = 5_000;
        return config;
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private record Harness(ServerTunnelController controller, EmbeddedChannel backend,
                           EmbeddedChannel publicTcp, CapabilityListenerRegistry registry) {
    }

    private static final class RecordingLauncher implements ServerTunnelController.Launcher {
        private final Path workingDirectory;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<SidecarProcess> started = new CopyOnWriteArrayList<>();
        private final List<String> secrets = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch gate;
        private volatile int gatedCall;
        private volatile int failingCall;

        private RecordingLauncher(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
        }

        @Override
        public SidecarProcess start(HyperConduitConfig config, InetSocketAddress listen,
                                    InetSocketAddress backend, String bridgeSecret,
                                    boolean tcpAllowed) throws IOException {
            int call = calls.incrementAndGet();
            secrets.add(bridgeSecret);
            CountDownLatch currentGate = gate;
            if (call == gatedCall && currentGate != null) {
                try {
                    if (!currentGate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IOException("gate_timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
            if (call == failingCall) throw new SidecarException("launch_failed");
            SidecarProcess process = ControllerProcesses.startServer(workingDirectory);
            started.add(process);
            return process;
        }

        private void gateNextCall(int call) {
            gatedCall = call;
            gate = new CountDownLatch(1);
        }

        private void releaseGate() {
            CountDownLatch current = gate;
            if (current != null) current.countDown();
        }

        private void failNextCall(int call) {
            failingCall = call;
        }

        private int calls() {
            return calls.get();
        }

        private List<SidecarProcess> started() {
            return started;
        }

        private List<String> secrets() {
            return secrets;
        }

        private void closeAll() {
            for (SidecarProcess process : started) {
                if (process.isAlive()) process.close();
            }
        }
    }
}
