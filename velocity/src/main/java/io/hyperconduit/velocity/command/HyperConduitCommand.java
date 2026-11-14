package io.hyperconduit.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import io.hyperconduit.sidecar.ProcessStatus;
import io.hyperconduit.velocity.config.VelocityConfig;
import io.hyperconduit.velocity.config.VelocityConfigStore;
import io.hyperconduit.velocity.tunnel.EndpointResolver;
import io.hyperconduit.velocity.tunnel.TunnelProcess;
import io.hyperconduit.velocity.tunnel.VelocityTunnelController;

import java.util.ArrayList;
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

    public HyperConduitCommand(VelocityConfigStore store, VelocityTunnelController controller) {
        this.store = Objects.requireNonNull(store, "store");
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!allowed(source)) {
            source.sendPlainMessage(PREFIX + TunnelConfigValues.permissionHint());
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0 || !"tunnel".equalsIgnoreCase(args[0])) {
            usage(source);
            return;
        }
        if (args.length == 1) {
            tunnelUsage(source);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "status" -> status(source);
            case "restart" -> restart(source);
            case "config" -> config(source, args);
            case "proxyprotocol" -> proxy(source, args);
            default -> tunnelUsage(source);
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return allowed(invocation.source());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            return List.of("tunnel");
        }
        if (!"tunnel".equalsIgnoreCase(args[0])) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("status", "restart", "config", "proxyprotocol");
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (sub.equals("config")) {
                return visibleKeys();
            }
            if (sub.equals("proxyprotocol")) {
                return List.of("on", "off", "input", "output");
            }
            return List.of();
        }
        if (args.length == 3) {
            if (sub.equals("config")) {
                return visibleKeys();
            }
            if (sub.equals("proxyprotocol")) {
                String direction = args[2].toLowerCase(Locale.ROOT);
                if (direction.equals("input") || direction.equals("output")) {
                    return List.of("on", "off");
                }
            }
            return List.of();
        }
        if (args.length == 4) {
            if (sub.equals("config")) {
                return TunnelConfigKey.fromKey(args[2])
                        .map(TunnelConfigKey::valueSuggestions)
                        .orElse(List.of());
            }
            if (sub.equals("proxyprotocol")) {
                return List.of("on", "off");
            }
        }
        return List.of();
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
                source.sendPlainMessage("disableVanillaTcp：已配置但无效（Velocity 下 tcpAllowed 始终为 true）");
            }
        }
        source.sendPlainMessage("tcpAllowed：true（Velocity 始终保留 TCP 监听）");
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
                + "（Velocity 下无效，tcpAllowed 始终为 true）");
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
        source.sendPlainMessage(PREFIX + "用法：/hyperconduit tunnel <status|restart|config|proxyprotocol>");
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

    private static List<String> visibleKeys() {
        List<String> keys = new ArrayList<>();
        for (TunnelConfigKey key : TunnelConfigKey.values()) {
            if (!key.secret()) {
                keys.add(key.key());
            }
        }
        return keys;
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
