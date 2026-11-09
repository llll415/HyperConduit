package io.hyperconduit.mod.client;

import io.hyperconduit.mod.bridge.ResolvedEndpoint;
import io.hyperconduit.mod.bridge.VanillaStatusQuery;
import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.probe.CapabilityJson;
import io.hyperconduit.sidecar.ProbeProcess;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;

import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded advisory discovery per attempt. It never uses credentials or writes trust. */
final class TransportDiscovery implements AutoCloseable {
    record Result(boolean declared, boolean enabled, boolean tcpAllowed) {
        static final Result UNKNOWN = new Result(false, false, false);
    }

    private static final ExecutorService WORKERS = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "hyperconduit-connect-discovery");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final CompletableFuture<Result> result = new CompletableFuture<>();
    private final AtomicReference<Socket> socket = new AtomicReference<>();
    private final AtomicReference<ProbeProcess> child = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<?>> pending = new AtomicReference<>();

    TransportDiscovery(ServerAddress server) {
        result.completeOnTimeout(Result.UNKNOWN, 6, TimeUnit.SECONDS);
        result.whenComplete((value, failure) -> release());
        try {
            WORKERS.execute(() -> check(server));
        } catch (RejectedExecutionException full) {
            result.complete(Result.UNKNOWN);
        }
    }

    CompletableFuture<Result> result() { return result; }

    private void check(ServerAddress server) {
        try {
            if (result.isDone()) return;
            var resolved = ServerNameResolver.DEFAULT.resolveAddress(server).map(ResolvedServerAddress::asInetSocketAddress);
            resolved.ifPresent(ResolvedEndpoint::consume);
            if (result.isDone()) return;
            if (resolved.isEmpty()) { result.complete(Result.UNKNOWN); return; }
            Socket tcp = new Socket();
            socket.set(tcp);
            if (result.isDone()) { release(); return; }
            try (tcp) {
                String raw = VanillaStatusQuery.query(tcp, resolved.get(), server.getHost(), server.getPort());
                var details = CapabilityJson.readDetails(raw);
                // An explicit incompatible vanilla declaration also wins over UDP discovery.
                if (details.state().declared()) {
                    result.complete(details.policyComplete()
                            ? new Result(true, details.enabled(), details.tcpAllowed()) : Result.UNKNOWN);
                    return;
                }
                // A reachable vanilla server with no usable declaration follows the current
                // setting. Do not add a needless QUIC timeout to ordinary vanilla joins.
                result.complete(Result.UNKNOWN);
                return;
            } catch (java.io.IOException unavailable) {
                // A refused/missing TCP listener can still advertise an enabled UDP tunnel.
            } finally {
                socket.compareAndSet(tcp, null);
            }
            if (result.isDone()) return;
            Path root = ConfigHolder.configDir().resolve("hyperconduit").toAbsolutePath();
            ProbeProcess probe = ProbeProcess.startBundled(root.resolve("bin"), Path.of("").toAbsolutePath());
            child.set(probe);
            if (result.isDone()) { release(); return; }
            var request = probe.probeDetails(resolved.get());
            pending.set(request);
            if (result.isDone()) { request.cancel(true); return; }
            request.whenComplete((details, error) -> result.complete(error == null ? fromProbe(details) : Result.UNKNOWN));
        } catch (Exception unavailable) {
            result.complete(Result.UNKNOWN);
        }
    }

    private static Result fromProbe(ProbeProcess.Details details) {
        return details != null && details.capability() != null && details.capability().compatible()
                && details.state().equals(details.capability().enabled() ? "enabled" : "disabled")
                ? new Result(true, details.capability().enabled(), details.capability().tcpAllowed()) : Result.UNKNOWN;
    }

    private void release() {
        Socket tcp = socket.getAndSet(null);
        if (tcp != null) try { tcp.close(); } catch (java.io.IOException ignored) {}
        var request = pending.getAndSet(null);
        if (request != null && !request.isDone()) request.cancel(true);
        ProbeProcess probe = child.getAndSet(null);
        if (probe != null) Thread.ofVirtual().name("hyperconduit-preflight-stop").start(probe::close);
    }

    @Override public void close() {
        result.cancel(true);
        release();
    }
}
