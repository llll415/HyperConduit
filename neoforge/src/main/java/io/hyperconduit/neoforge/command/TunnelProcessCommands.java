package io.hyperconduit.neoforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.hyperconduit.mod.bridge.ServerTunnelController;
import io.hyperconduit.mod.bridge.TunnelService;
import io.hyperconduit.mod.probe.TunnelListenerState;
import io.hyperconduit.sidecar.ProcessStatus;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

/** Commands inspect only the processes owned by this Minecraft server. */
public final class TunnelProcessCommands {
    private TunnelProcessCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> status() {
        return Commands.literal("status").executes(context -> {
            var source = context.getSource();
            if (!TunnelConfigCommands.ensureAdmin(source)) return 0;
            var controller = controller(source);
            if (controller == null) {
                send(source, "[HyperConduit] 隧道程序：未运行");
                return 0;
            }
            if (controller.isRestarting()) {
                send(source, "[HyperConduit] 隧道程序：正在重启");
                return Command.SINGLE_SUCCESS;
            }
            var process = controller.process();
            if (process == null || !controller.isRunning()) {
                send(source, "[HyperConduit] 隧道程序：未运行");
                return 0;
            }
            ProcessStatus status = process.processStatus();
            send(source, "[HyperConduit] 隧道程序：" + (status.running() ? "运行中" : "未运行"));
            send(source, "监听地址：" + TunnelService.format(controller.listenAddress()) + "（UDP）");
            send(source, "进程 PID：" + status.pid());
            long seconds = status.uptime() == null ? -1 : status.uptime().toSeconds();
            send(source, "运行时间：" + (seconds < 0 ? "不可用" : seconds / 3600 + " 小时 " + seconds / 60 % 60 + " 分钟 " + seconds % 60 + " 秒"));
            send(source, "内存（工作集）：" + (status.residentMemoryBytes() == null ? "不可用"
                    : String.format(Locale.ROOT, "%.1f MiB", status.residentMemoryBytes() / 1048576.0)));
            send(source, "CPU：" + (status.cpuPercent() == null ? (process.processStatusPending() ? "采样中" : "不可用")
                    : String.format(Locale.ROOT, "%.1f%%", status.cpuPercent())));
            var running = controller.runningConfig();
            if (running != null) send(source, "Proxy Protocol v2 接收支持：" + (running.proxyProtocolV2 ? "开启" : "关闭"));
            return Command.SINGLE_SUCCESS;
        });
    }

    public static LiteralArgumentBuilder<CommandSourceStack> restart() {
        return Commands.literal("restart").executes(context -> {
            var source = context.getSource();
            if (!TunnelConfigCommands.ensureAdmin(source)) return 0;
            var controller = controller(source);
            if (controller == null) {
                send(source, "[HyperConduit] 隧道程序当前未运行。");
                return 0;
            }

            // Let the controller decide NOT_RUNNING/BUSY/SERVER_STOPPING instead of
            // pre-filtering on isRunning(): during a restart the process is briefly absent.
            CompletableFuture<ServerTunnelController.Result> restart = controller.restart();
            if (restart.isDone()) {
                sendResult(source, completedResult(restart));
                return Command.SINGLE_SUCCESS;
            }

            send(source, "[HyperConduit] 正在重启隧道程序……");
            MinecraftServer server = source.getServer();
            restart.whenComplete((result, failure) -> notifyRestart(source, server, result, failure));
            return Command.SINGLE_SUCCESS;
        });
    }

    private static ServerTunnelController.Result completedResult(
            CompletableFuture<ServerTunnelController.Result> restart) {
        try {
            return restart.join();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * Delivers the asynchronous restart outcome on the server thread. A server that stopped while
     * the restart was in flight has nobody left to notify, and a rejected submission is dropped
     * instead of escaping the discarded {@code whenComplete} future.
     */
    private static void notifyRestart(CommandSourceStack source, MinecraftServer server,
                                      ServerTunnelController.Result result, Throwable failure) {
        if (server == null || !server.isRunning() || server.isStopped()) return;
        try {
            server.executeIfPossible(() -> {
                if (!server.isRunning() || server.isStopped()) return;
                if (failure != null) {
                    source.sendFailure(Component.literal("[HyperConduit] 隧道程序重启请求失败。"));
                    return;
                }
                sendResult(source, result);
            });
        } catch (RejectedExecutionException stopped) {
            // The server stopped between the check and the submission; the message is moot.
        }
    }

    private static void sendResult(CommandSourceStack source, ServerTunnelController.Result result) {
        if (result == null) {
            source.sendFailure(Component.literal("[HyperConduit] 隧道程序重启请求失败。"));
            return;
        }
        if (result.status() == ServerTunnelController.Status.RESTARTED) {
            source.sendSuccess(() -> Component.literal(result.message()), false);
        } else {
            source.sendFailure(Component.literal(result.message()));
        }
    }

    private static ServerTunnelController controller(CommandSourceStack source) {
        if (source.getServer().getConnection() instanceof TunnelListenerState state) {
            List<ServerTunnelController> controllers = state.hyperconduit$controllers();
            ServerTunnelController fallback = null;
            for (ServerTunnelController controller : controllers) {
                if (controller.isRunning() || controller.isRestarting()) return controller;
                if (fallback == null) fallback = controller;
            }
            return fallback;
        }
        return null;
    }

    private static void send(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
    }
}
