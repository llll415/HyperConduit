package io.hyperconduit.velocity.tunnel;

import io.hyperconduit.sidecar.*;
import io.hyperconduit.velocity.config.VelocityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class VelocityTunnelControllerTest {
    @TempDir Path root;
    final EndpointResolver.Endpoint endpoint = EndpointResolver.resolve(
            new InetSocketAddress("127.0.0.1", 25565)).orElseThrow();

    @Test void restartNoOpThenSerialReplacementAndShutdown() throws Exception {
        VelocityConfig config = new VelocityConfig();
        config.enabled = true;
        AtomicInteger launches = new AtomicInteger();
        try (var controller = new VelocityTunnelController(root, config::copy, (c, e, p) -> {
            launches.incrementAndGet(); return new Fake(true);
        }, Executors.newSingleThreadExecutor())) {
            assertEquals(VelocityTunnelController.Status.NOT_RUNNING, controller.restart().get().status());
            assertEquals(0, launches.get());
            assertTrue(controller.startIfEnabled(endpoint).get(2, TimeUnit.SECONDS).started());
            var old = (Fake) controller.process();
            assertTrue(controller.restart().get(2, TimeUnit.SECONDS).started());
            assertFalse(old.alive);
            assertEquals(2, launches.get());
            config.maxConnections = -1;
            assertEquals(VelocityTunnelController.Status.INVALID_CONFIG,
                    controller.restart().get(2, TimeUnit.SECONDS).status());
            assertTrue(controller.isRunning());
        }
    }

    @Test void shutdownClosesProcessWaitingForReady() throws Exception {
        var config = new VelocityConfig(); config.enabled = true;
        var child = new Fake(false);
        var launched = new CountDownLatch(1);
        var controller = new VelocityTunnelController(root, config::copy, (c, e, p) -> {
            launched.countDown(); return child;
        }, Executors.newSingleThreadExecutor());
        var pending = controller.startIfEnabled(endpoint);
        assertTrue(launched.await(2, TimeUnit.SECONDS));
        controller.close();
        assertFalse(child.alive);
        assertTrue(pending.get(2, TimeUnit.SECONDS).status() != VelocityTunnelController.Status.STARTED);
        assertEquals(VelocityTunnelController.Status.STOPPING, controller.restart().get().status());
    }

    static final class Fake implements TunnelProcess {
        volatile boolean alive = true;
        final CompletableFuture<SidecarProcess.Ready> ready = new CompletableFuture<>();
        final CompletableFuture<Integer> exit = new CompletableFuture<>();
        Fake(boolean readyNow) {
            if (readyNow) ready.complete(new SidecarProcess.Ready(1, "server",
                    new InetSocketAddress("127.0.0.1", 25565), ""));
        }
        public CompletableFuture<SidecarProcess.Ready> ready() { return ready; }
        public CompletableFuture<Integer> exit() { return exit; }
        public boolean isAlive() { return alive; }
        public long pid() { return 123; }
        public ProcessStatus processStatus() { return null; }
        public boolean processStatusPending() { return false; }
        public void enableTelemetry() { }
        public TelemetrySnapshot telemetry() { return null; }
        public void close() {
            alive = false;
            ready.completeExceptionally(new CancellationException());
            exit.complete(0);
        }
    }
}
