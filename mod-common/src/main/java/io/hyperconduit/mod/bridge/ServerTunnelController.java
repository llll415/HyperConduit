package io.hyperconduit.mod.bridge;

import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import io.hyperconduit.mod.probe.CapabilityListenerRegistry;
import io.hyperconduit.sidecar.SidecarErrorText;
import io.hyperconduit.sidecar.SidecarException;
import io.hyperconduit.sidecar.SidecarProcess;
import io.netty.channel.Channel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns one server listener's private loopback backend and the sidecar that feeds it.
 *
 * <p>The private backend and the bridge secret belong to the controller and survive a controlled
 * restart. Only the sidecar process is replaced: the old process is stopped first (which drops its
 * bridged connections), then the new process is launched with a fresh snapshot of
 * {@link ConfigHolder#get()}. The capability registry is updated only after the new process is
 * {@code READY}, so discovery never advertises a policy that is not actually running.
 *
 * <p>Restart is intentionally opt-in: {@link #restart()} never starts a sidecar that was not
 * already running. A server shutdown marks the controller stopped before closing anything, so an
 * in-flight restart can neither publish a new process nor leak one.
 */
public final class ServerTunnelController {

    private static final System.Logger LOGGER = System.getLogger("io.hyperconduit.HyperConduit");
    private static final long READY_MARGIN_MS = 6_000L;

    public enum Status {
        /** A new sidecar reached READY and replaced the old one. */
        RESTARTED,
        /** There was no live sidecar; nothing was started. */
        NOT_RUNNING,
        /** The new configuration failed validation or is outside the reloadable scope. */
        INVALID_CONFIG,
        /** The old process was stopped but the replacement could not be started. */
        FAILED,
        /** The server is stopping (or already stopped), so restart was refused. */
        SERVER_STOPPING,
        /** Another restart is already in flight; this request did nothing. */
        BUSY
    }

    /** Code-only outcome; {@link #message()} is safe to show to a command sender. */
    public record Result(Status status, String code, String message) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }

        public boolean restarted() {
            return status == Status.RESTARTED;
        }
    }

    /** Seam used by tests; production always delegates to {@link TunnelService#server}. */
    @FunctionalInterface
    interface Launcher {
        SidecarProcess start(HyperConduitConfig config, InetSocketAddress listen, InetSocketAddress backend,
                             String bridgeSecret, boolean tcpAllowed) throws IOException;
    }

    private final Channel backend;
    private final InetSocketAddress backendAddress;
    private final InetSocketAddress listenAddress;
    private final boolean publicTcpAllowed;
    private final String bridgeSecret;
    private final CapabilityListenerRegistry.Handle handle;
    private final AtomicReference<SidecarProcess> process;
    private final Launcher launcher;
    private final Object lifecycle = new Object();
    /** Processes we are deliberately stopping; their exit must not close the shared backend. */
    private final Set<SidecarProcess> controlledExits = ConcurrentHashMap.newKeySet();

    private volatile HyperConduitConfig runningConfig;
    private volatile SidecarProcess starting;
    private volatile CompletableFuture<Result> pendingRestart;
    private volatile boolean stopped;

    ServerTunnelController(Channel backend, InetSocketAddress backendAddress, InetSocketAddress listenAddress,
                           boolean publicTcpAllowed, String bridgeSecret,
                           CapabilityListenerRegistry.Handle handle,
                           AtomicReference<SidecarProcess> process, Launcher launcher) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.backendAddress = Objects.requireNonNull(backendAddress, "backendAddress");
        this.listenAddress = Objects.requireNonNull(listenAddress, "listenAddress");
        this.publicTcpAllowed = publicTcpAllowed;
        this.bridgeSecret = Objects.requireNonNull(bridgeSecret, "bridgeSecret");
        this.handle = Objects.requireNonNull(handle, "handle");
        this.process = Objects.requireNonNull(process, "process");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        // A public listener close (LAN close or server stop) closes the backend. Stop the child,
        // but leave the capability entry alone: a still-open public TCP listener must keep its
        // tcpAllowed fact, and a tunnel-only entry removes itself on sidecarAlive(false).
        backend.closeFuture().addListener(future -> Thread.ofVirtual().start(this::onBackendClosed));
    }

    /** Production factory: the controller takes ownership of an already-bound private backend. */
    public static ServerTunnelController create(Channel backend, InetSocketAddress listenAddress,
                                                boolean publicTcpAllowed, String bridgeSecret,
                                                CapabilityListenerRegistry.Handle handle,
                                                AtomicReference<SidecarProcess> process) {
        return new ServerTunnelController(backend, (InetSocketAddress) backend.localAddress(), listenAddress,
                publicTcpAllowed, bridgeSecret, handle, process, TunnelService::server);
    }

    /**
     * Starts the first sidecar for this listener. Throws the underlying safe error code instead of
     * replacing it with a generic listener failure, so the caller can surface the real reason.
     */
    public void start(HyperConduitConfig config) throws IOException {
        Objects.requireNonNull(config, "config");
        HyperConduitConfig snapshot = config.copy();
        try {
            snapshot.validate();
        } catch (RuntimeException invalid) {
            String code = safeCode(invalid, "config_invalid");
            LOGGER.log(System.Logger.Level.WARNING,
                    "[HyperConduit] 隧道进程启动失败。原因：" + configReason(code));
            throw new SidecarException(code);
        }
        try {
            launch(snapshot);
        } catch (SidecarException failure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "[HyperConduit] 隧道进程启动失败。原因：" + startupReason(failure.code()));
            throw failure;
        }
    }

    /**
     * Restarts the running sidecar from the current on-disk configuration. The call is
     * asynchronous; the returned future never completes exceptionally. A request that arrives
     * while another restart is in flight is rejected with {@link Status#BUSY} and never starts a
     * second process.
     */
    public CompletableFuture<Result> restart() {
        synchronized (lifecycle) {
            CompletableFuture<Result> pending = pendingRestart;
            if (pending != null && !pending.isDone()) {
                return CompletableFuture.completedFuture(busy());
            }
            SidecarProcess current = process.get();
            if (current == null || !current.isAlive()) {
                return CompletableFuture.completedFuture(notRunning());
            }
            if (stopped) return CompletableFuture.completedFuture(stopping());
            CompletableFuture<Result> future = new CompletableFuture<>();
            pendingRestart = future;
            Thread.ofVirtual().name("hyperconduit-server-restart").start(() -> runRestart(future, current));
            return future;
        }
    }

    /** The current managed process, or {@code null} while stopped/restarting. */
    public SidecarProcess process() {
        return process.get();
    }

    public boolean isRunning() {
        SidecarProcess current = process.get();
        return current != null && current.isAlive();
    }

    /** True while a restart is in flight; read-only and safe to poll from commands. */
    public boolean isRestarting() {
        CompletableFuture<Result> pending = pendingRestart;
        return pending != null && !pending.isDone();
    }

    /** Snapshot of the configuration that produced the current process; null when not running. */
    public HyperConduitConfig runningConfig() {
        HyperConduitConfig current = runningConfig;
        return current == null ? null : current.copy();
    }

    /** Public address peers are told to use; the private backend port is never exposed. */
    public InetSocketAddress listenAddress() {
        return listenAddress;
    }

    /** Loopback backend address that the bridge secret protects. */
    public InetSocketAddress backendAddress() {
        return backendAddress;
    }

    public boolean publicTcpAllowed() {
        return publicTcpAllowed;
    }

    /**
     * Server-stop path: refuse future restarts and clear the published process synchronously,
     * then close the private backend. Child shutdown is bounded by {@link SidecarProcess#close()};
     * if called from the backend's Netty event loop it is moved to a virtual thread so the loop is
     * never blocked.
     */
    public void close() {
        SidecarProcess current;
        SidecarProcess pending;
        synchronized (lifecycle) {
            if (stopped) return;
            stopped = true;
            current = process.getAndSet(null);
            pending = starting;
            starting = null;
            runningConfig = null;
        }
        closeBackend();
        if (backend.eventLoop().inEventLoop()) {
            Thread.ofVirtual().name("hyperconduit-controller-close").start(() -> finishClose(pending, current));
        } else {
            finishClose(pending, current);
        }
    }

    private void finishClose(SidecarProcess pending, SidecarProcess current) {
        closeControlled(pending);
        closeControlled(current);
        handle.remove();
    }

    private void runRestart(CompletableFuture<Result> future, SidecarProcess expectedOld) {
        Result result;
        try {
            result = doRestart(expectedOld);
        } catch (RuntimeException unexpected) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "[HyperConduit] 隧道进程重启异常，请查看日志。");
            result = failed("restart_failed");
        } finally {
            synchronized (lifecycle) {
                if (pendingRestart == future) pendingRestart = null;
            }
        }
        future.complete(result);
    }

    private Result doRestart(SidecarProcess expectedOld) {
        HyperConduitConfig requested;
        try {
            HyperConduitConfig loaded = ConfigHolder.get();
            if (loaded == null) return invalid("config_unavailable");
            requested = loaded.copy();
            requested.validate();
        } catch (RuntimeException invalid) {
            // Validation happens before the old process is touched.
            return invalid(safeCode(invalid, "config_invalid"));
        }

        if (!requested.enabled) {
            // Enabling/disabling is a server-start decision, not part of the sidecar reload
            // scope. Keep the running tunnel untouched and tell the caller to restart the server.
            return invalid("disabled");
        }

        synchronized (lifecycle) {
            if (stopped) return stopping();
            SidecarProcess current = process.get();
            if (current == null || current != expectedOld || !current.isAlive()) return notRunning();
            controlledExits.add(current);
            process.set(null);
            runningConfig = null;
            // Deliberately do NOT call handle.sidecarAlive(false): a tunnel-only entry would
            // remove itself and could never be declared again after the replacement is READY.
            // The handle's probe sees process == null, so discovery is temporarily disabled
            // without deleting the entry; observeSidecar(true) restores it after READY.
        }
        closeControlled(expectedOld);

        synchronized (lifecycle) {
            if (stopped) return stopping();
        }
        try {
            launch(requested);
            synchronized (lifecycle) {
                if (stopped) return stopping();
            }
            return restarted();
        } catch (SidecarException failure) {
            synchronized (lifecycle) {
                if (stopped) return stopping();
            }
            LOGGER.log(System.Logger.Level.WARNING,
                    "[HyperConduit] 隧道进程启动失败。原因：" + startupReason(failure.code()));
            return failed(failure.code());
        }
    }

    /**
     * Launches and publishes one process. The caller must not hold {@link #lifecycle}: waiting for
     * READY and closing a failed child can block, and {@link #close()} must be able to interleave.
     */
    private SidecarProcess launch(HyperConduitConfig config) throws SidecarException {
        SidecarProcess child;
        try {
            child = launcher.start(config, listenAddress, backendAddress, bridgeSecret, publicTcpAllowed);
        } catch (SidecarException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new SidecarException("launch_failed");
        }

        boolean aborted;
        synchronized (lifecycle) {
            aborted = stopped;
            if (!aborted) starting = child;
        }
        if (aborted) {
            closeControlled(child);
            throw new SidecarException("canceled");
        }

        try {
            child.ready().get(config.connectTimeoutMs + READY_MARGIN_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            cleanupFailed(child);
            throw new SidecarException("startup_timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cleanupFailed(child);
            throw new SidecarException("canceled");
        } catch (CancellationException canceled) {
            cleanupFailed(child);
            throw new SidecarException("canceled");
        } catch (ExecutionException failure) {
            cleanupFailed(child);
            Throwable cause = failure.getCause();
            if (cause instanceof SidecarException sidecar) throw sidecar;
            throw new SidecarException("listener_failed");
        }

        synchronized (lifecycle) {
            aborted = stopped;
            if (!aborted) {
                starting = null;
                process.set(child);
                // The Java listener cannot be reloaded. Report the actual public TCP state, not a
                // pending disk value that the running process was never told to apply.
                HyperConduitConfig running = config.copy();
                running.disableVanillaTcp = !publicTcpAllowed;
                runningConfig = running;
            }
        }
        if (aborted) {
            cleanupFailed(child);
            throw new SidecarException("canceled");
        }

        try {
            // READY first, then telemetry and policy: discovery must never lead the process.
            child.enableTelemetry();
            handle.policy(config.disableBrutal, !config.serverToken.isEmpty());
            handle.observeSidecar(this::isRunning);
            child.exit().thenAccept(code -> onExit(child, code));
        } catch (RuntimeException failure) {
            synchronized (lifecycle) {
                process.compareAndSet(child, null);
                if (starting == child) starting = null;
                runningConfig = null;
            }
            cleanupFailed(child);
            throw new SidecarException("listener_failed");
        }

        boolean published;
        synchronized (lifecycle) {
            published = !stopped && process.get() == child && child.isAlive();
        }
        if (!published) {
            // close() won the race after READY but before telemetry/policy finished.
            cleanupFailed(child);
            throw new SidecarException("canceled");
        }
        // The process is READY and still the published one. Log the mode it actually started
        // with, never a disk config that is still waiting for a restart.
        if (config.proxyProtocolV2) {
            LOGGER.log(System.Logger.Level.INFO,
                    "[HyperConduit] Proxy Protocol v2 接收支持已开启，仅接受可信转发来源的数据，"
                            + "新转发会话必须提供合法的 Proxy Protocol v2 头部。");
        } else {
            LOGGER.log(System.Logger.Level.INFO,
                    "[HyperConduit] Proxy Protocol v2 接收支持已关闭，使用普通 QUIC 接收。");
        }
        return child;
    }

    private void onExit(SidecarProcess child, int exitCode) {
        boolean expected;
        boolean current;
        synchronized (lifecycle) {
            expected = controlledExits.remove(child);
            current = process.compareAndSet(child, null);
            if (current) runningConfig = null;
            if (expected || stopped || !current) return;
        }
        // Unexpected exit: stop advertising, then release the private backend. The public TCP
        // entry (if any) stays because its channel is still alive.
        handle.sidecarAlive(false);
        LOGGER.log(System.Logger.Level.WARNING,
                "[HyperConduit] 隧道进程意外退出（退出码 " + exitCode + "），已清理私有后端。");
        closeBackend();
    }

    private void onBackendClosed() {
        SidecarProcess current;
        SidecarProcess pending;
        synchronized (lifecycle) {
            if (stopped) return;
            stopped = true;
            current = process.getAndSet(null);
            pending = starting;
            starting = null;
            runningConfig = null;
        }
        closeControlled(pending);
        closeControlled(current);
        // Do not remove the capability handle here. A public TCP listener may still be alive, and
        // its own close callback owns removing that fact.
    }

    private void cleanupFailed(SidecarProcess child) {
        synchronized (lifecycle) {
            if (starting == child) starting = null;
            process.compareAndSet(child, null);
        }
        closeControlled(child);
    }

    private void closeControlled(SidecarProcess child) {
        if (child == null) return;
        synchronized (lifecycle) {
            controlledExits.add(child);
        }
        try {
            child.close();
        } catch (RuntimeException ignored) {
            // SidecarProcess.close() is best effort and never blocks the caller forever.
        }
    }

    private void closeBackend() {
        if (backend.isOpen()) backend.close();
    }

    private static final String PREFIX = "[HyperConduit] ";

    private static Result notRunning() {
        return new Result(Status.NOT_RUNNING, "not_running", PREFIX + "隧道程序当前未运行。");
    }

    private static Result stopping() {
        return new Result(Status.SERVER_STOPPING, "server_stopping", PREFIX + "服务端正在停止，未执行重启。");
    }

    private static Result busy() {
        return new Result(Status.BUSY, "busy", PREFIX + "隧道程序正在启动或停止，请稍后再试。");
    }

    private static Result invalid(String code) {
        return new Result(Status.INVALID_CONFIG, code,
                PREFIX + "无法重启：配置无效，当前隧道程序未停止。\n原因：" + configReason(code));
    }

    private static Result failed(String code) {
        return new Result(Status.FAILED, code,
                PREFIX + "隧道程序启动失败。\n原因：" + startupReason(code));
    }

    private static Result restarted() {
        return new Result(Status.RESTARTED, "restarted", PREFIX + "隧道程序已就绪。");
    }

    /** Maps config codes to Chinese without ever echoing file contents or paths. */
    private static String configReason(String code) {
        if (code == null) return "配置无效，请检查配置文件。";
        return switch (code) {
            case "disabled" -> "HyperConduit 已禁用，需要重启服务端才能生效。";
            case "config_unavailable", "config_read" -> "无法读取配置文件，请检查文件权限。";
            case "config_invalid", "config_duplicate_field", "config_unknown_field" ->
                    "配置文件格式无效，请检查 JSON 内容。";
            case "config_too_large" -> "配置文件过大。";
            case "config_up_mbps", "config_down_mbps" -> "带宽配置超出允许范围。";
            case "config_congestion" -> "拥塞控制配置无效，只能为 bbr 或 reno。";
            case "config_client_token", "config_server_token" -> "口令配置无效，请检查字符与长度。";
            case "config_connect_timeout" -> "连接超时配置超出允许范围。";
            case "config_max_connections" -> "最大连接数配置超出允许范围。";
            case "config_directory", "config_write", "config_atomic_replace_unavailable",
                    "config_permissions" -> "无法写入配置文件，请检查目录权限。";
            case "config_conflict" -> "配置文件已被其他操作修改，请重新读取后再试。";
            default -> "配置无效，请检查配置文件。";
        };
    }

    private static String startupReason(String code) {
        if (code != null && code.startsWith("config")) return configReason(code);
        String reason = withoutPrefix(SidecarErrorText.describe(code));
        String wrapper = "隧道程序启动失败。\n原因：";
        return reason.startsWith(wrapper) ? reason.substring(wrapper.length()) : reason;
    }

    private static String withoutPrefix(String text) {
        return text.startsWith(PREFIX) ? text.substring(PREFIX.length()) : text;
    }

    private static String safeCode(Throwable failure, String fallback) {
        if (failure instanceof SidecarException sidecar) return sidecar.code();
        String message = failure.getMessage();
        return message != null && message.matches("[a-z][a-z0-9_]{0,63}") ? message : fallback;
    }
}
