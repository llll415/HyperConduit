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
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@EventBusSubscriber(modid = "hyperconduit")
public final class HyperConduitServerEvents {

    private HyperConduitServerEvents() {
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        HyperConduitServerChannel.beginGracefulShutdownAll();
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hc")
                .then(Commands.literal("status").executes(context -> sendStatus(context.getSource().getServer(),
                        context.getSource()))));
        event.getDispatcher().register(Commands.literal("hyperconduit")
                .then(Commands.literal("status").executes(context -> sendStatus(context.getSource().getServer(),
                        context.getSource()))));
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
            lines.add("[HyperConduit] " + player.getGameProfile().getName()
                    + " | RTT " + ms(stats.smoothedRttNanos()) + "ms p95 " + ms(recent.p95RttNanos())
                    + "ms | loss " + percent(recent.lossRate()) + " | retrans " + recent.retransmitsPerSecond()
                    + "/s | ↓ " + rate(recent.txBytesPerSecond()) + " ↑ " + rate(recent.rxBytesPerSecond()));
        }
        lines.add("[HyperConduit] 总计 | 隧道 " + tunnels + " | 原版TCP " + vanilla + " | 本地 " + local
                + " | ↓ " + rate(txRate) + " ↑ " + rate(rxRate)
                + " | retrans " + String.format(Locale.ROOT, "%.1f/s", retransRate));
        return lines;
    }

    private static String configStatus(HyperConduitConfig config) {
        if (config == null || !config.enabled) return "已禁用";
        return "已启用 | " + (config.brutal ? "Brutal " : "Paced ") + config.mbps
                + " Mbps | 原版TCP" + (config.disableVanillaTcp ? "禁止" : "允许");
    }

    private static long ms(long nanos) { return nanos / 1_000_000; }
    private static String percent(double value) { return String.format(Locale.ROOT, "%.1f%%", value * 100); }
    private static String rate(long bytesPerSecond) { return bytes(bytesPerSecond) + "/s"; }
    private static String bytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", value / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", value / (1024.0 * 1024.0));
    }
}
