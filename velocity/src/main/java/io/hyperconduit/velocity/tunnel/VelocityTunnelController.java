package io.hyperconduit.velocity.tunnel;

import io.hyperconduit.sidecar.ProcessStatus;
import io.hyperconduit.sidecar.SidecarErrorText;
import io.hyperconduit.sidecar.SidecarException;
import io.hyperconduit.sidecar.SidecarProcess;
import io.hyperconduit.velocity.config.VelocityConfig;
import io.hyperconduit.velocity.config.VelocityConfigStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the single server-mode sidecar used by the Velocity plugin.
 *
 * <p>All start/restart/close work runs on one daemon executor, so two commands can never launch
 * overlapping processes. A restart never starts a process that was not already running. A proxy
 * shutdown marks the controller stopped before closing anything, so an in-flight start can
 * neither publish a new process nor leak one.</p>
 *
 * <p>The controller is deliberately free of Velocity types so its lifecycle can be tested with a
 * fake {@link TunnelProcess}.</p>
 */
public final class VelocityTunnelController implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger("io.hyperconduit.HyperConduit");
    private static final long READY_MARGIN_MS = 6_000L;
    private static final String PREFIX = "[HyperConduit] ";
    private static final String LIFECYCLE_THREAD = "hyperconduit-velocity-lifecycle";

    public enum Status {
        /** A first sidecar reached READY. */
        STARTED,
        /** A replacement sidecar reached READY. */
        RESTARTED,
        /** There was no live sidecar; nothing was started. */
        NOT_RUNNING,
        /** The new configuration failed validation; the old process was left untouched. */
        INVALID_CONFIG,
        /** The old process was stopped but the replacement could not be started. */
        FAILED,
        /** The proxy is stopping (or already stopped). */
        STOPPING,
        /** Another start/restart is already in flight. */
        BUSY,
        /** The plugin is disabled in the configuration. */
        DISABLED,
        /** A process is already alive. */
        ALREADY_RUNNING
    }

    /** Code-only outcome; {@link #message()} is safe to show to a command sender. */
    public record Result(Status status, String code, String message) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }

        public boolean started() {
            return status == Status.STARTED || status == Status.RESTARTED;
        }
    }

    /** Seam used by tests; production delegates to {@link #launch}. */
    @FunctionalInterface
    public interface Launcher {
        TunnelProcess start(VelocityConfig config, EndpointResolver.Endpoint endpoint, Path dataDirectory)
                throws IOException;
    }

    /** Seam used by tests; production reads the on-disk configuration. */
    @FunctionalInterface
    public interface ConfigSource {
        VelocityConfig load();
    }

    private final Path dataDirectory;
    private final ConfigSource configSource;
    private final Launcher launcher;
    private final ExecutorService executor;
    private final AtomicReference<TunnelProcess> process = new AtomicReference<>();
    private final AtomicReference<VelocityConfig> runningConfig = new AtomicReference<>();
    private final AtomicReference<EndpointResolver.Endpoint> runningEndpoint = new AtomicReference<>();
    /** Processes we are deliberately stopping; their exit must not be reported as unexpected. */
    private final Set<TunnelProcess> controlledExits = ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();

    private volatile TunnelProcess starting;
    private volatile CompletableFuture<Result> pendingStart;
    private volatile CompletableFuture<Result> pendingRestart;
    private volatile boolean stopped;

    /** Production factory. The caller must {@link #close()} it on proxy shutdown. */
    public static VelocityTunnelController create(Path dataDirectory, VelocityConfigStore store) {
        Objects.requireNonNull(store, "store");
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, LIFECYCLE_THREAD);
            thread.setDaemon(true);
            return thread;
        });
        return new VelocityTunnelController(dataDirectory, store::load, VelocityTunnelController::launch,
                executor);
    }

    VelocityTunnelController(Path dataDirectory, ConfigSource configSource, Launcher launcher,
                             ExecutorService executor) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath()
                .normalize();
        this.configSource = Objects.requireNonNull(configSource, "configSource");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Starts the sidecar only when the on-disk configuration enables it. Called after Velocity
     * has bound its Minecraft listener.
     */
    public CompletableFuture<Result> startIfEnabled(EndpointResolver.Endpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        synchronized (lifecycle) {
            if (stopped) {
                return completed(stopping());
            }
            if (isPending(pendingStart) || isPending(pendingRestart) || starting != null) {
                return completed(busy());
            }
            TunnelProcess current = process.get();
            if (current != null && current.isAlive()) {
                return completed(alreadyRunning());
            }
            CompletableFuture<Result> future = new CompletableFuture<>();
            pendingStart = future;
            try {
                executor.execute(() -> runStart(future, endpoint));
            } catch (RejectedExecutionException rejected) {
                pendingStart = null;
                return completed(stopping());
            }
            return future;
        }
    }

    /**
     * Restarts the running sidecar from the current on-disk configuration. The returned future
     * never completes exceptionally. A request while another start/restart is in flight is
     * rejected with {@link Status#BUSY}; a stopped controller never starts a new process.
     */
    public CompletableFuture<Result> restart() {
        synchronized (lifecycle) {
            if (stopped) {
                return completed(stopping());
            }
            if (isPending(pendingRestart) || isPending(pendingStart) || starting != null) {
                return completed(busy());
            }
            TunnelProcess current = process.get();
            if (current == null || !current.isAlive()) {
                return completed(notRunning());
            }
            CompletableFuture<Result> future = new CompletableFuture<>();
            pendingRestart = future;
            try {
                executor.execute(() -> runRestart(future, current));
            } catch (RejectedExecutionException rejected) {
                pendingRestart = null;
                return completed(stopping());
            }
            return future;
        }
    }

    public TunnelProcess process() {
        return process.get();
    }

    public boolean isRunning() {
        TunnelProcess current = process.get();
        return current != null && current.isAlive();
    }

    public boolean isRestarting() {
        return isPending(pendingRestart);
    }

    public boolean isStopped() {
        return stopped;
    }

    /** Snapshot of the configuration that produced the current process; null when not running. */
    public VelocityConfig runningConfig() {
        VelocityConfig current = runningConfig.get();
        return current == null ? null : current.copy();
    }

    public EndpointResolver.Endpoint runningEndpoint() {
        return runningEndpoint.get();
    }

    /**
     * Proxy-stop path. Marks the controller stopped synchronously, then closes any child on the
     * serial executor. The caller waits briefly so the JVM does not exit with a live sidecar.
     */
    @Override
    public void close() {
        TunnelProcess current;
        TunnelProcess pending;
        synchronized (lifecycle) {
            if (stopped) {
                return;
            }
            stopped = true;
            current = process.getAndSet(null);
            pending = starting;
            starting = null;
            runningConfig.set(null);
            runningEndpoint.set(null);
        }
        // Cleanup must not queue behind a task waiting for READY: close releases that wait.
        closeControlled(pending);
        closeControlled(current);
        executor.shutdown();
        if (!isLifecycleThread()) {
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runStart(CompletableFuture<Result> future, EndpointResolver.Endpoint endpoint) {
        Result result;
        try {
            VelocityConfig config = configSource.load();
            config.validate();
            if (!config.enabled) {
                result = disabled();
            } else {
                result = launchAndWait(config, endpoint, false);
            }
        } catch (RuntimeException failure) {
            result = failed(safeCode(failure, "config_invalid"));
        } finally {
            synchronized (lifecycle) {
                if (pendingStart == future) {
                    pendingStart = null;
                }
            }
        }
        future.complete(result);
    }

    private void runRestart(CompletableFuture<Result> future, TunnelProcess expectedOld) {
        Result result;
        try {
            result = doRestart(expectedOld);
        } catch (RuntimeException unexpected) {
            LOGGER.log(System.Logger.Level.WARNING, PREFIX + "隧道进程重启异常，请查看日志。");
            result = failed("restart_failed");
        } finally {
            synchronized (lifecycle) {
                if (pendingRestart == future) {
                    pendingRestart = null;
                }
            }
        }
        future.complete(result);
    }

    private Result doRestart(TunnelProcess expectedOld) {
        VelocityConfig requested;
        try {
            VelocityConfig loaded = configSource.load();
            loaded.validate();
            requested = loaded;
        } catch (RuntimeException invalid) {
            // Validation happens before the old process is touched.
            return invalid(safeCode(invalid, "config_invalid"));
        }
        if (!requested.enabled) {
            return invalid("disabled");
        }

        EndpointResolver.Endpoint endpoint = runningEndpoint.get();
        if (endpoint == null) {
            return notRunning();
        }

        synchronized (lifecycle) {
            if (stopped) {
                return stopping();
            }
            TunnelProcess current = process.get();
            if (current == null || current != expectedOld || !current.isAlive()) {
                return notRunning();
            }
            controlledExits.add(current);
            process.set(null);
            runningConfig.set(null);
            runningEndpoint.set(null);
        }
        closeControlled(expectedOld);

        synchronized (lifecycle) {
            if (stopped) {
                return stopping();
            }
        }
        return launchAndWait(requested, endpoint, true);
    }

    private Result launchAndWait(VelocityConfig config, EndpointResolver.Endpoint endpoint,
                                 boolean restart) {
        TunnelProcess child;
        try {
            child = launcher.start(config, endpoint, dataDirectory);
        } catch (SidecarException failure) {
            return failed(failure.code());
        } catch (IOException | RuntimeException failure) {
            return failed("launch_failed");
        }

        boolean aborted;
        synchronized (lifecycle) {
            aborted = stopped;
            if (!aborted) {
                starting = child;
            }
        }
        if (aborted) {
            closeControlled(child);
            return stopping();
        }

        try {
            child.ready().get(config.connectTimeoutMs + READY_MARGIN_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            cleanupFailed(child);
            return failed("startup_timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cleanupFailed(child);
            return failed("interrupted");
        } catch (CancellationException canceled) {
            cleanupFailed(child);
            return failed("canceled");
        } catch (ExecutionException failure) {
            cleanupFailed(child);
            Throwable cause = failure.getCause();
            if (cause instanceof SidecarException sidecar) {
                return failed(sidecar.code());
            }
            return failed("listener_failed");
        }

        synchronized (lifecycle) {
            aborted = stopped;
            if (!aborted) {
                starting = null;
                process.set(child);
                runningConfig.set(config.copy());
                runningEndpoint.set(endpoint);
            }
        }
        if (aborted) {
            cleanupFailed(child);
            return stopping();
        }

        try {
            // READY first, then telemetry and the exit watcher.
            child.enableTelemetry();
            child.exit().thenAccept(code -> onExit(child, code));
        } catch (RuntimeException failure) {
            synchronized (lifecycle) {
                process.compareAndSet(child, null);
                if (starting == child) {
                    starting = null;
                }
                runningConfig.set(null);
                runningEndpoint.set(null);
            }
            cleanupFailed(child);
            return failed("listener_failed");
        }

        logRunningPolicy(config);
        return restart ? restarted() : started();
    }

    private void onExit(TunnelProcess child, int exitCode) {
        boolean expected;
        boolean current;
        synchronized (lifecycle) {
            expected = controlledExits.remove(child);
            current = process.compareAndSet(child, null);
            if (current) {
                runningConfig.set(null);
                runningEndpoint.set(null);
            }
            if (starting == child) {
                starting = null;
            }
            if (expected || stopped || !current) {
                return;
            }
        }
        LOGGER.log(System.Logger.Level.WARNING,
                PREFIX + "隧道进程意外退出（退出码 " + exitCode + "），已清理运行状态。");
    }

    private void cleanupFailed(TunnelProcess child) {
        synchronized (lifecycle) {
            if (starting == child) {
                starting = null;
            }
            process.compareAndSet(child, null);
        }
        closeControlled(child);
    }

    private void closeControlled(TunnelProcess child) {
        if (child == null) {
            return;
        }
        controlledExits.add(child);
        try {
            child.close();
        } catch (RuntimeException ignored) {
            // SidecarProcess.close() is best effort and never blocks forever.
        }
    }

    private void logRunningPolicy(VelocityConfig config) {
        if (config.proxyProtocolV2Output) {
            LOGGER.log(System.Logger.Level.INFO,
                    PREFIX + "已开启向 Velocity 监听器发送 Proxy Protocol v2 头部；"
                            + "请确认 velocity.toml 的 haproxy-protocol = true，否则玩家连接会被拒绝。");
        }
        if (config.proxyProtocolV2) {
            LOGGER.log(System.Logger.Level.INFO,
                    PREFIX + "已开启 Proxy Protocol v2 输入（FRP），仅接受可信转发来源的数据。");
        }
        if (config.disableVanillaTcp) {
            LOGGER.log(System.Logger.Level.WARNING,
                    PREFIX + "配置项 disableVanillaTcp 在 Velocity 下无效："
                            + "插件不会关闭 Velocity 的 TCP 监听。");
        }
    }

    private static boolean isPending(CompletableFuture<Result> future) {
        return future != null && !future.isDone();
    }

    private static boolean isLifecycleThread() {
        return LIFECYCLE_THREAD.equals(Thread.currentThread().getName());
    }

    static TunnelProcess launch(VelocityConfig config, EndpointResolver.Endpoint endpoint, Path dataDirectory)
            throws IOException {
        Path root = dataDirectory.resolve("hyperconduit").toAbsolutePath().normalize();
        Files.createDirectories(dataDirectory);
        Map<String, Object> values = TunnelSettings.serverConfig(config, endpoint, root);
        SidecarProcess child = SidecarProcess.startConfigured(
                root.resolve("bin"),
                root.resolve("launch"),
                "server",
                values,
                dataDirectory,
                Duration.ofMillis(config.connectTimeoutMs + 5_000L),
                Duration.ofSeconds(2));
        return new SidecarTunnelProcess(child);
    }

    private static CompletableFuture<Result> completed(Result result) {
        return CompletableFuture.completedFuture(result);
    }

    private static Result started() {
        return new Result(Status.STARTED, "started", PREFIX + "隧道程序已就绪。");
    }

    private static Result restarted() {
        return new Result(Status.RESTARTED, "restarted", PREFIX + "隧道程序已就绪。");
    }

    private static Result notRunning() {
        return new Result(Status.NOT_RUNNING, "not_running", PREFIX + "隧道程序当前未运行。");
    }

    private static Result stopping() {
        return new Result(Status.STOPPING, "stopping", PREFIX + "代理正在停止，未执行操作。");
    }

    private static Result busy() {
        return new Result(Status.BUSY, "busy", PREFIX + "隧道程序正在启动或停止，请稍后再试。");
    }

    private static Result disabled() {
        return new Result(Status.DISABLED, "disabled",
                PREFIX + "HyperConduit 未启用，未启动隧道程序。");
    }

    private static Result alreadyRunning() {
        return new Result(Status.ALREADY_RUNNING, "already_running",
                PREFIX + "隧道程序已在运行。");
    }

    private static Result invalid(String code) {
        return new Result(Status.INVALID_CONFIG, code,
                PREFIX + "无法重启：配置无效，当前隧道程序未停止。\n原因：" + configReason(code));
    }

    private static Result failed(String code) {
        return new Result(Status.FAILED, code,
                PREFIX + "隧道程序启动失败。\n原因：" + startupReason(code));
    }

    /** Maps configuration codes to Chinese without ever echoing file contents or paths. */
    public static String configReason(String code) {
        if (code == null) {
            return "配置无效，请检查配置文件。";
        }
        return switch (code) {
            case "disabled" -> "HyperConduit 已禁用，需要重启代理才能生效。";
            case "config_unavailable", "config_read" -> "无法读取配置文件，请检查文件权限。";
            case "config_invalid", "config_duplicate_field", "config_unknown_field" ->
                    "配置文件格式无效，请检查 JSON 内容。";
            case "config_too_large" -> "配置文件过大。";
            case "config_up_mbps", "config_down_mbps" -> "带宽配置超出允许范围。";
            case "config_congestion" -> "拥塞控制配置无效，只能为 bbr 或 reno。";
            case "config_server_token" -> "服务器口令配置无效，请检查字符与长度。";
            case "config_connect_timeout" -> "连接超时配置超出允许范围。";
            case "config_max_connections" -> "最大连接数配置超出允许范围。";
            case "config_proxy_protocol_source" ->
                    "可信来源必须是 IP 或 CIDR 字面值（不支持域名），用逗号分隔。";
            case "config_proxy_protocol_sources_required" ->
                    "启用 Proxy Protocol v2 输入时至少需要一个可信来源。";
            case "config_proxy_protocol_sources_limit" -> "可信来源最多 128 条。";
            case "config_directory", "config_write", "config_atomic_replace_unavailable",
                    "config_permissions" -> "无法写入配置文件，请检查目录权限。";
            default -> "配置无效，请检查配置文件。";
        };
    }

    private static String startupReason(String code) {
        if (code != null && code.startsWith("config")) {
            return configReason(code);
        }
        String reason = withoutPrefix(SidecarErrorText.describe(code));
        String wrapper = "隧道程序启动失败。\n原因：";
        return reason.startsWith(wrapper) ? reason.substring(wrapper.length()) : reason;
    }

    private static String withoutPrefix(String text) {
        return text.startsWith(PREFIX) ? text.substring(PREFIX.length()) : text;
    }

    private static String safeCode(Throwable failure, String fallback) {
        if (failure instanceof SidecarException sidecar) {
            return sidecar.code();
        }
        String message = failure.getMessage();
        return message != null && message.matches("[a-z][a-z0-9_]{0,63}") ? message : fallback;
    }
}
