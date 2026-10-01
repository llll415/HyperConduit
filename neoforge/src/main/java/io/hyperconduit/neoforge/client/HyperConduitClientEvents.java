package io.hyperconduit.neoforge.client;

import com.mojang.brigadier.Command;
import io.hyperconduit.conn.RollingMetrics;
import io.hyperconduit.conn.SessionStats;
import io.hyperconduit.neoforge.HyperConduitServerEvents;
import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import io.hyperconduit.netty.HyperConduitChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.commands.Commands;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.Locale;

@EventBusSubscriber(modid = "hyperconduit", value = Dist.CLIENT)
public final class HyperConduitClientEvents {

    private HyperConduitClientEvents() {
    }

    @SubscribeEvent
    public static void addScreenControls(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen instanceof JoinMultiplayerScreen) {
            event.addListener(Button.builder(Component.literal("HyperConduit…"), button ->
                            Minecraft.getInstance().setScreen(new HyperConduitConfigScreen(screen)))
                    .bounds(6, 6, 110, 20).build());
        } else if (screen instanceof ShareToLanScreen) {
            event.addListener(Button.builder(Component.literal("HyperConduit 主机设置…"), button ->
                            Minecraft.getInstance().setScreen(new HyperConduitConfigScreen(screen)))
                    .bounds(6, 6, 150, 20).build());
        }
    }

    @SubscribeEvent
    public static void addDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.hasSingleplayerServer()) {
            event.getLeft().addAll(HyperConduitServerEvents.statusLines(minecraft.getSingleplayerServer()));
            return;
        }
        HyperConduitChannel channel = activeTunnel();
        HyperConduitConfig config = ConfigHolder.get();
        if (channel == null) {
            String state = config != null && config.enabled ? "未建立隧道" : "已禁用";
            event.getLeft().add("[HyperConduit] " + state);
            return;
        }
        event.getLeft().addAll(clientStatusLines(channel, config));
    }

    @SubscribeEvent
    public static void registerClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hyperconduit")
                .then(Commands.literal("status").executes(context -> {
                    for (String line : clientStatusLines(activeTunnel(), ConfigHolder.get())) {
                        context.getSource().sendSuccess(() -> Component.literal(line), false);
                    }
                    return Command.SINGLE_SUCCESS;
                })));
    }

    @SubscribeEvent
    public static void announceTunnel(ClientPlayerNetworkEvent.LoggingIn event) {
        if (event.getConnection().channel() instanceof HyperConduitChannel channel) {
            SessionStats stats = channel.engine().stats();
            HyperConduitConfig config = ConfigHolder.get();
            String controller = config != null && config.brutal ? "Brutal" : "Paced";
            int rate = config == null ? 0 : config.mbps;
            event.getPlayer().sendSystemMessage(Component.literal(
                    "[HyperConduit] 隧道已建立 · " + controller + " " + rate
                            + " Mbps · RTT " + millis(stats.smoothedRttNanos()) + " ms"));
        }
    }

    static java.util.List<String> clientStatusLines(HyperConduitChannel channel, HyperConduitConfig config) {
        if (channel == null) {
            return java.util.List.of("§b[HyperConduit]§r " + (config != null && config.enabled
                    ? "§e[已启用]§r 当前连接未使用隧道" : "§8[已禁用]§r"));
        }
        SessionStats stats = channel.engine().stats();
        RollingMetrics recent = stats.recent();
        String controller = config != null && config.brutal ? "Brutal" : "Paced";
        int rate = config == null ? 0 : config.mbps;
        return java.util.List.of(
                "§b[HyperConduit]§r §a[运行中]§r §e[" + controller + " " + rate + " Mbps]§r",
                "§f[链路]§r RTT §b" + millis(stats.smoothedRttNanos()) + "ms§r  §7p95(10s) §f"
                        + millis(recent.p95RttNanos()) + "ms§r  " + lossTag(recent.lossRate()) + "  "
                        + retransTag(recent.retransmitsPerSecond()),
                "§f[速率]§r §a↓ " + rate(recent.rxBytesPerSecond()) + "§r  §b↑ "
                        + rate(recent.txBytesPerSecond()) + "§r  §7抖动(10s) "
                        + millis(recent.jitterNanos()) + "ms§r");
    }

    private static HyperConduitChannel activeTunnel() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return null;
        }
        Connection connection = minecraft.getConnection().getConnection();
        return connection.channel() instanceof HyperConduitChannel channel ? channel : null;
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000;
    }

    private static String lossTag(double lossRate) {
        String color = lossRate >= 0.025 ? "§c" : "§8";
        return color + "丢包(10s) " + percent(lossRate) + "§r";
    }

    private static String retransTag(double retransmitsPerSecond) {
        String color = retransmitsPerSecond > 0 ? "§e" : "§8";
        return color + "重传 " + String.format(Locale.ROOT, "%.1f/s", retransmitsPerSecond) + "§r";
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String rate(long bytesPerSecond) {
        return bytes(bytesPerSecond) + "/s";
    }

    private static String bytes(long value) {
        if (value < 1024) {
            return value + " B";
        }
        if (value < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", value / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", value / (1024.0 * 1024.0));
    }
}
