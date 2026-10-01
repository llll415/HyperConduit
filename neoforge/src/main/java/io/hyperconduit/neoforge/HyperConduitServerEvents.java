package io.hyperconduit.neoforge;

import com.mojang.brigadier.Command;
import io.hyperconduit.conn.RollingMetrics;
import io.hyperconduit.conn.SessionStats;
import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import io.hyperconduit.netty.HyperConduitServerChannel;
import io.hyperconduit.netty.HyperConduitServerChildChannel;
import io.netty.channel.Channel;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = "hyperconduit")
public final class HyperConduitServerEvents {

    private static final Set<net.minecraft.commands.CommandSourceStack> WATCHERS = ConcurrentHashMap.newKeySet();
    private static int watchTicks;

    private HyperConduitServerEvents() {
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        HyperConduitServerChannel.beginGracefulShutdownAll();
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hyperconduit")
                .then(Commands.literal("status").executes(context -> sendStatus(context.getSource().getServer(),
                        context.getSource())))
                .then(Commands.literal("watch").executes(context -> {
                    WATCHERS.add(context.getSource());
                    context.getSource().sendSuccess(() -> Component.literal(
                            "[HyperConduit] 已开始每秒输出实时状态；使用 /hyperconduit watch stop 停止。"), false);
                    return Command.SINGLE_SUCCESS;
                }).then(Commands.literal("stop").executes(context -> {
                    WATCHERS.remove(context.getSource());
                    context.getSource().sendSuccess(() -> Component.literal("[HyperConduit] 已停止实时状态输出。"), false);
                    return Command.SINGLE_SUCCESS;
                }))));
    }

    @SubscribeEvent
    public static void updateWatchers(ServerTickEvent.Post event) {
        if (++watchTicks < 20 || WATCHERS.isEmpty()) return;
        watchTicks = 0;
        List<String> lines = statusLines(event.getServer());
        for (net.minecraft.commands.CommandSourceStack watcher : WATCHERS) {
            for (String line : lines) watcher.sendSuccess(() -> Component.literal(line), false);
        }
    }

    private static int sendStatus(MinecraftServer server, net.minecraft.commands.CommandSourceStack source) {
        for (String line : statusLines(server)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    public static List<String> statusLines(MinecraftServer server) {
        HyperConduitConfig config = ConfigHolder.get();
        List<String> lines = new ArrayList<>();
        lines.add("[HyperConduit] 服务端 | " + configStatus(config));
        if (server == null) return lines;

        long txRate = 0, rxRate = 0;
        double retransRate = 0;
        int tunnels = 0, vanilla = 0, local = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Channel channel = player.connection.getConnection().channel();
            if (channel instanceof io.netty.channel.local.LocalChannel) {
                local++;
                continue;
            }
            if (!(channel instanceof HyperConduitServerChildChannel tunnel)) {
                vanilla++;
                continue;
            }
            tunnels++;
            SessionStats stats = tunnel.stats();
            RollingMetrics recent = stats.recent();
            txRate += recent.txBytesPerSecond();
            rxRate += recent.rxBytesPerSecond();
            retransRate += recent.retransmitsPerSecond();
            lines.add("§b[HyperConduit]§r §f[" + player.getGameProfile().getName() + "]§r"
                    + " §fRTT §b" + ms(stats.smoothedRttNanos()) + "ms§r §7p95(10s) §f"
                    + ms(recent.p95RttNanos()) + "ms§r " + lossTag(recent.lossRate()) + " "
                    + retransTag(recent.retransmitsPerSecond()) + " §a↓ " + rate(recent.txBytesPerSecond())
                    + "§r §b↑ " + rate(recent.rxBytesPerSecond()) + "§r");
        }
        lines.add("[HyperConduit] 总计 | 隧道 " + tunnels + " | 原版TCP " + vanilla + " | 本地 " + local
                + " | 实时 ↓ " + rate(txRate) + " ↑ " + rate(rxRate)
                + " | 实时重传 " + String.format(Locale.ROOT, "%.1f/s", retransRate));
        return lines;
    }

    private static String configStatus(HyperConduitConfig config) {
        if (config == null || !config.enabled) return "已禁用";
        return "已启用 | " + (config.brutal ? "Brutal " : "Paced ") + config.mbps
                + " Mbps | 原版TCP" + (config.disableVanillaTcp ? "禁止" : "允许");
    }

    private static long ms(long nanos) { return nanos / 1_000_000; }
    private static String lossTag(double lossRate) {
        return (lossRate >= 0.025 ? "§c" : "§8") + "丢包(10s) " + percent(lossRate) + "§r";
    }
    private static String retransTag(double retransmitsPerSecond) {
        return (retransmitsPerSecond > 0 ? "§e" : "§8") + "重传 "
                + String.format(Locale.ROOT, "%.1f/s", retransmitsPerSecond) + "§r";
    }
    private static String percent(double value) { return String.format(Locale.ROOT, "%.1f%%", value * 100); }
    private static String rate(long bytesPerSecond) { return bytes(bytesPerSecond) + "/s"; }
    private static String bytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", value / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", value / (1024.0 * 1024.0));
    }
}
