package io.hyperconduit.neoforge.client;

import com.mojang.brigadier.Command;
import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import io.hyperconduit.mod.bridge.TunnelMetrics;
import io.hyperconduit.mod.bridge.TelemetryText;
import io.hyperconduit.neoforge.HyperConduitServerEvents;
import io.hyperconduit.sidecar.TunnelAttributes;
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

import java.util.ArrayList;
import java.util.List;

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
        event.getLeft().addAll(clientStatusLines(activeConnection(), ConfigHolder.get()));
    }

    @SubscribeEvent
    public static void registerClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hyperconduit")
                .then(Commands.literal("status").executes(context -> {
                    for (String line : clientStatusLines(activeConnection(), ConfigHolder.get())) {
                        context.getSource().sendSuccess(() -> Component.literal(line), false);
                    }
                    return Command.SINGLE_SUCCESS;
                })));
    }

    @SubscribeEvent
    public static void announceTunnel(ClientPlayerNetworkEvent.LoggingIn event) {
        Connection connection = event.getConnection();
        if (!isTunnel(connection)) {
            return;
        }
        event.getPlayer().sendSystemMessage(Component.literal("[HyperConduit] 隧道已连接"));
    }

    static List<String> clientStatusLines(Connection connection, HyperConduitConfig config) {
        if (!isTunnel(connection)) {
            String state = config != null && config.enabled ? "§e[未使用隧道]§r" : "§8[已禁用]§r";
            return List.of("§b[HyperConduit]§r " + state);
        }
        List<String> lines = new ArrayList<>();
        lines.add("§b[HyperConduit]§r §a[隧道已连接]§r");
        lines.add("§f[传输]§r " + TelemetryText.describe(TunnelMetrics.connection(connection.channel())));
        lines.add("§7速率含 QUIC 重传与协议开销，非游戏有效载荷§r");
        return lines;
    }

    private static boolean isTunnel(Connection connection) {
        return connection != null && connection.channel() != null && connection.channel().isActive()
                && Boolean.TRUE.equals(connection.channel().attr(TunnelAttributes.TUNNEL).get());
    }

    private static Connection activeConnection() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return null;
        }
        return minecraft.getConnection().getConnection();
    }
}
