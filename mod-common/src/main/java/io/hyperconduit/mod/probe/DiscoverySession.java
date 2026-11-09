package io.hyperconduit.mod.probe;

import io.hyperconduit.mod.bridge.ResolvedEndpoint;
import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.sidecar.ProbeProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;

import java.nio.file.Path;
import java.util.concurrent.*;

/** One multiplayer-screen generation owns one reusable, advisory-only sidecar. */
public final class DiscoverySession implements AutoCloseable {
    private static final ExecutorService RESOLVER = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(512), r -> {
        Thread thread = new Thread(r, "hyperconduit-discovery"); thread.setDaemon(true); return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private final ProbeBatch<ServerData> batch;
    private final CompletableFuture<ProbeProcess> process = new CompletableFuture<>();
    private boolean started;
    private volatile boolean closed;

    public DiscoverySession() {
        batch = new ProbeBatch<>(4, 512, this::check, Minecraft.getInstance()::execute,
                (server, details) -> ((CapabilityEntry) server).hyperconduit$probeDetails(details));
    }

    public void submit(ServerData server) { batch.submit(server); }

    private synchronized void startProcess() {
        if (started || closed) return;
        started = true;
        Thread.ofVirtual().name("hyperconduit-discovery-start").start(() -> {
            try {
                Path root = ConfigHolder.configDir().resolve("hyperconduit").toAbsolutePath();
                ProbeProcess child = ProbeProcess.startBundled(root.resolve("bin"), Path.of("").toAbsolutePath());
                boolean dispose;
                synchronized (this) {
                    dispose = closed;
                    if (!dispose) process.complete(child);
                }
                if (dispose) child.close();
            } catch (Exception unavailable) { process.completeExceptionally(unavailable); }
        });
    }

    private CompletableFuture<CapabilityDetails> check(ServerData server) {
        String endpoint = server.ip;
        CompletableFuture<CapabilityDetails> result = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<CompletableFuture<ProbeProcess.Details>> request =
                new java.util.concurrent.atomic.AtomicReference<>();
        result.whenComplete((value, error) -> {
            var pending = request.get(); if (error != null && pending != null) pending.cancel(true);
        });
        CompletableFuture.delayedExecutor(400, TimeUnit.MILLISECONDS).execute(() -> {
            try {
                RESOLVER.execute(() -> {
                    try {
                        if (result.isDone() || closed) return;
                        CapabilityDetails known = ((CapabilityEntry) server).hyperconduit$details();
                        if (known.state().declared()) { result.complete(known); return; }
                        startProcess();
                        var address = ServerNameResolver.DEFAULT.resolveAddress(ServerAddress.parseString(endpoint))
                                .map(ResolvedServerAddress::asInetSocketAddress);
                        // Resolver's original-login identity must never survive a discovery task.
                        address.ifPresent(ResolvedEndpoint::consume);
                        if (address.isEmpty()) { result.complete(CapabilityDetails.UNKNOWN); return; }
                        process.whenComplete((child, failure) -> {
                            if (result.isDone()) return;
                            if (failure != null) { result.complete(CapabilityDetails.UNKNOWN); return; }
                            try {
                                var future = child.probeDetails(address.get()); request.set(future);
                                if (result.isDone()) future.cancel(true);
                                future.whenComplete((details, error) -> result.complete(error == null
                                        ? fromProbe(details)
                                        : CapabilityDetails.UNKNOWN));
                            } catch (RuntimeException failed) { result.complete(CapabilityDetails.UNKNOWN); }
                        });
                    } catch (Exception failed) { result.complete(CapabilityDetails.UNKNOWN); }
                });
            } catch (RejectedExecutionException full) { result.complete(CapabilityDetails.UNKNOWN); }
        });
        return result.orTimeout(8, TimeUnit.SECONDS);
    }

    private static CapabilityDetails fromProbe(ProbeProcess.Details details) {
        ProbeProcess.Declaration capability = details.capability();
        if (capability == null) return CapabilityDetails.of(Capability.fromProbe(details.state()));
        return CapabilityDetails.fromProbe(details.state(), capability.tcpAllowed(),
                capability.disableBrutal(), capability.tokenRequired());
    }

    @Override public void close() {
        synchronized (this) { closed = true; }
        batch.close();
        process.whenComplete((child, error) -> { if (child != null) Thread.ofVirtual().start(child::close); });
        process.completeExceptionally(new CancellationException());
    }
}
