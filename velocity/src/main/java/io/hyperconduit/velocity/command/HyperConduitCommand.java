package io.hyperconduit.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import io.hyperconduit.sidecar.ProcessStatus;
import io.hyperconduit.velocity.config.VelocityConfig;
import io.hyperconduit.velocity.config.VelocityConfigStore;
import io.hyperconduit.velocity.status.StatusService;
import io.hyperconduit.velocity.tunnel.EndpointResolver;
import io.hyperconduit.velocity.tunnel.TunnelProcess;
import io.hyperconduit.velocity.tunnel.VelocityTunnelController;
import io.hyperconduit.velocity.watch.WatchService;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /hyperconduit tunnel ...} for Velocity.
 *
 * <p>All messages are Chinese and prefixed with {@code [HyperConduit]}. Configuration edits are
 * read-modify-write on disk and never restart the tunnel automatically. The server token is
 * never echoed.</p>
 */
public final class HyperConduitCommand implements SimpleCommand {

    public static final String PERMISSION = "hyperconduit.admin";
    private static final String PREFIX = "[HyperConduit] ";

    private final VelocityConfigStore store;
    private final VelocityTunnelController controller;
    private final StatusService statusService;
    private final WatchService watchService;

    public HyperConduitCommand(VelocityConfigStore store, VelocityTunnelController controller,
                               StatusService statusService, WatchService watchService) {
        this.store = Objects.requireNonNull(store, "store");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.statusService = Objects.requireNonNull(statusService, "statusService");
        this.watchService = Objects.requireNonNull(watchService, "watchService");
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!allowed(source)) {
            source.sendPlainMessage(PREFIX + TunnelConfigValues.permissionHint());
            return;
        }
        String[] args = invocation.arguments();
        switch (CommandRouting.route(args)) {
            case STATUS -> sendStatus(source);
            case WATCH -> {
                watchService.add(source);
                source.sendPlainMessage(PREFIX + "已开始每秒输出实时状态；使用 /hyperconduit watch stop 停止。");
            }
            case WATCH_STOP -> {
                watchService.remove(source);
                source.sendPlainMessage(PREFIX + "已停止实时状态输出。");
            }
            case TUNNEL_USAGE -> tunnelUsage(source);
            case TUNNEL -> tunnel(source, args);
            case USAGE -> usage(source);
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return allowed(invocation.source());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return CommandRouting.suggest(invocation.arguments());
    }

    private void sendStatus(CommandSource source) {
        try {
            for (String line : statusService.lines()) {
                source.sendPlainMessage(line);
            }
        } catch (RuntimeException unavailable) {
            source.sendPlainMessage(PREFIX + "状态暂不可用，请稍后再试。");
        }
    }

    private void tunnel(CommandSource source, String[] args) {
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "status" -> status(source);
            case "restart" -> restart(source);
            case "config" -> config(source, args);
            case "proxyprotocol" -> proxy(source, args);
            default -> tunnelUsage(source);
        }
    }

    private void status(CommandSource source) {
        if (controller.isRestarting()) {
            source.sendPlainMessage(PREFIX + "隧道程序：正在重启");
            return;
        }
        TunnelProcess process = controller.process();
        if (process == null || !controller.isRunning()) {
            source.sendPlainMessage(PREFIX + "隧道程序：未运行");
            return;
        }

        ProcessStatus status = process.processStatus();
        source.sendPlainMessage(PREFIX + "隧道程序：" + (status.running() ? "运行中" : "未运行"));
        EndpointResolver.Endpoint endpoint = controller.runningEndpoint();
        if (endpoint != null) {
            source.sendPlainMessage("监听地址：" + EndpointResolver.format(endpoint.listen()) + "（UDP）");
            source.sendPlainMessage("后端目标：" + EndpointResolver.format(endpoint.target()) + "（TCP）");
        }
        source.sendPlainMessage("进程 PID：" + status.pid());
        long seconds = status.uptime() == null ? -1 : status.uptime().toSeconds();
        source.sendPlainMessage("运行时间：" + (seconds < 0 ? "不可用" : formatUptime(seconds)));
        source.sendPlainMessage("内存（OS RSS）：" + (status.residentMemoryBytes() == null
                ? "不可用"
                : String.format(Locale.ROOT, "%.1f MiB", status.residentMemoryBytes() / 1048576.0)));
        source.sendPlainMessage("CPU：" + (status.cpuPercent() == null
                ? (process.processStatusPending() ? "采样中" : "不可用")
                : String.format(Locale.ROOT, "%.1f%%", status.cpuPercent())));

        VelocityConfig running = controller.runningConfig();
        if (running != null) {
            source.sendPlainMessage("Proxy Protocol v2 输入（FRP）："
                    + (running.proxyProtocolV2 ? "开启" : "关闭"));
            source.sendPlainMessage("Proxy Protocol v2 输出（Velocity）："
                    + (running.proxyProtocolV2Output ? "开启" : "关闭"));
            if (running.disableVanillaTcp) {
                source.sendPlainMessage("disableVanillaTcp：已配置但无效（Velocity 下 TCP 监听始终开放）");
            }
        }
        source.sendPlainMessage("TCP 监听：始终开放；tcpAllowed 只描述该事实，不是访问控制策略");
    }

    private void restart(CommandSource source) {
        CompletableFuture<VelocityTunnelController.Result> restart = controller.restart();
        if (restart.isDone()) {
            sendResult(source, completed(restart));
            return;
        }
        source.sendPlainMessage(PREFIX + "正在重启隧道程序，会断开 HyperConduit 隧道连接；其他 TCP 连接不受影响。");
        restart.whenComplete((result, failure) -> {
            if (failure != null) {
                source.sendPlainMessage(PREFIX + "隧道程序重启请求失败。");
            } else {
                sendResult(source, result);
            }
        });
    }

    private void config(CommandSource source, String[] args) {
        if (args.length == 2) {
            list(source);
            return;
        }
        TunnelConfigKey key;
        try {
            key = requireKey(args[2]);
        } catch (TunnelCommandException failure) {
            fail(source, failure.getMessage());
            return;
        }
        if (args.length == 3) {
            VelocityConfig config;
            try {
                config = store.load();
            } catch (RuntimeException failure) {
                fail(source, TunnelConfigValues.explain(failure));
                return;
            }
            success(source, key.key() + " = " + TunnelConfigValues.describe(config, key));
            return;
        }

        String value = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        TunnelConfigValues.Change[] change = new TunnelConfigValues.Change[1];
        try {
            store.update(config -> change[0] = TunnelConfigValues.apply(config, key, value));
        } catch (TunnelCommandException failure) {
            fail(source, failure.getMessage());
            return;
        } catch (RuntimeException failure) {
            fail(source, TunnelConfigValues.explain(failure));
            return;
        }
        sendChange(source, change[0]);
    }

    private void list(CommandSource source) {
        VelocityConfig config;
        try {
            config = store.load();
        } catch (RuntimeException failure) {
            fail(source, TunnelConfigValues.explain(failure));
            return;
        }
        for (TunnelConfigKey key : TunnelConfigKey.values()) {
            source.sendPlainMessage(key.key() + " = " + TunnelConfigValues.describe(config, key));
        }
        source.sendPlainMessage("enabled = " + config.enabled + "（仅配置文件）");
        source.sendPlainMessage("disableVanillaTcp = " + config.disableVanillaTcp
                + "（Velocity 下无效，TCP 监听始终开放）");
    }

    private void proxy(CommandSource source, String[] args) {
        if (args.length < 3) {
            proxyUsage(source);
            return;
        }
        String direction = args[2].toLowerCase(Locale.ROOT);
        boolean enabled;
        if (direction.equals("on") || direction.equals("off")) {
            enabled = direction.equals("on");
            applyProxy(source, true, enabled);
            return;
        }
        if ((direction.equals("input") || direction.equals("output")) && args.length == 4) {
            String state = args[3].toLowerCase(Locale.ROOT);
            if (!state.equals("on") && !state.equals("off")) {
                proxyUsage(source);
                return;
            }
            applyProxy(source, direction.equals("input"), state.equals("on"));
            return;
        }
        proxyUsage(source);
    }

    private void applyProxy(CommandSource source, boolean input, boolean enabled) {
        TunnelConfigValues.Change[] change = new TunnelConfigValues.Change[1];
        try {
            store.update(config -> change[0] = input
                    ? TunnelConfigValues.applyProxyProtocolV2(config, enabled)
                    : TunnelConfigValues.applyProxyProtocolV2Output(config, enabled));
        } catch (TunnelCommandException failure) {
            fail(source, failure.getMessage());
            return;
        } catch (RuntimeException failure) {
            fail(source, TunnelConfigValues.explain(failure));
            return;
        }
        sendChange(source, change[0]);
    }

    private void sendChange(CommandSource source, TunnelConfigValues.Change change) {
        if (change != null && change.detail() != null && !change.detail().isEmpty()) {
            success(source, change.detail());
        }
        if (change != null && change.warning() != null) {
            success(source, change.warning());
        }
        source.sendPlainMessage(TunnelConfigValues.SAVE_NOTICE);
    }

    private void usage(CommandSource source) {
        source.sendPlainMessage(PREFIX + "用法：");
        source.sendPlainMessage("/hyperconduit status");
        source.sendPlainMessage("/hyperconduit watch");
        source.sendPlainMessage("/hyperconduit watch stop");
        source.sendPlainMessage("/hyperconduit tunnel <status|restart|config|proxyprotocol>");
    }

    private void tunnelUsage(CommandSource source) {
        source.sendPlainMessage(PREFIX + "用法：");
        source.sendPlainMessage("/hyperconduit tunnel status");
        source.sendPlainMessage("/hyperconduit tunnel restart");
        source.sendPlainMessage("/hyperconduit tunnel config [配置项] [值]");
        source.sendPlainMessage("/hyperconduit tunnel proxyprotocol on|off");
        source.sendPlainMessage("/hyperconduit tunnel proxyprotocol input|output on|off");
    }

    private void proxyUsage(CommandSource source) {
        success(source, "用法：/hyperconduit tunnel proxyprotocol on|off。"
                + "on/off 控制接收 FRP 的 Proxy Protocol v2 头部；"
                + "输出（Velocity）请使用 proxyprotocol output on|off。"
                + "保存后请执行 /hyperconduit tunnel restart 应用配置。");
    }

    private static TunnelConfigKey requireKey(String rawKey) {
        return TunnelConfigKey.fromKey(rawKey)
                .orElseThrow(() -> new TunnelCommandException(
                        "未知配置项。可用配置项：" + TunnelConfigKey.keyList() + "。"));
    }

    private static VelocityTunnelController.Result completed(
            CompletableFuture<VelocityTunnelController.Result> future) {
        try {
            return future.join();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static void sendResult(CommandSource source, VelocityTunnelController.Result result) {
        if (result == null) {
            source.sendPlainMessage(PREFIX + "隧道程序重启请求失败。");
            return;
        }
        if (result.started()) {
            success(source, result.message());
        } else {
            fail(source, result.message());
        }
    }

    private static boolean allowed(CommandSource source) {
        return source instanceof ConsoleCommandSource || source.hasPermission(PERMISSION);
    }

    private static String formatUptime(long seconds) {
        long total = Math.max(0, seconds);
        return total / 3600 + " 小时 " + total / 60 % 60 + " 分钟 " + total % 60 + " 秒";
    }

    private static void success(CommandSource source, String message) {
        source.sendPlainMessage(message.startsWith(PREFIX) ? message : PREFIX + message);
    }

    private static void fail(CommandSource source, String message) {
        source.sendPlainMessage(message.startsWith(PREFIX) ? message : PREFIX + message);
    }
}
