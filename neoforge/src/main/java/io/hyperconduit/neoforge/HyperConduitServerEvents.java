package io.hyperconduit.neoforge;

import com.mojang.brigadier.Command;
import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import io.hyperconduit.mod.bridge.TunnelMetrics;
import io.hyperconduit.mod.bridge.TelemetryText;
import io.hyperconduit.sidecar.TunnelAttributes;
import io.netty.channel.Channel;
import io.netty.channel.local.LocalChannel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = "hyperconduit")
public final class HyperConduitServerEvents {

    private static final Set<CommandSourceStack> WATCHERS = ConcurrentHashMap.newKeySet();
    private static int watchTicks;

    private HyperConduitServerEvents() {
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // The listener mixin owns the sidecar processes and backend sockets.
        WATCHERS.clear();
        watchTicks = 0;
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hyperconduit")
                .requires(source -> source.hasPermission(2))
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
        WATCHERS.removeIf(source -> source.getPlayer() != null
                && (!source.getPlayer().connection.isAcceptingMessages() || !source.hasPermission(2)));
        if (++watchTicks < 20 || WATCHERS.isEmpty()) return;
        watchTicks = 0;
        List<String> lines = statusLines(event.getServer());
        for (CommandSourceStack watcher : WATCHERS) {
            for (String line : lines) watcher.sendSuccess(() -> Component.literal(line), false);
        }
    }

    private static int sendStatus(MinecraftServer server, CommandSourceStack source) {
        for (String line : statusLines(server)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    public static List<String> statusLines(MinecraftServer server) {
        HyperConduitConfig config = ConfigHolder.get();
        List<String> lines = new ArrayList<>();
        lines.add("[HyperConduit] 服务端配置（下次监听应用） | " + configStatus(config));
        if (server == null) return lines;

        int tunnels = 0, vanilla = 0, local = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Channel channel = player.connection.getConnection().channel();
            if (channel == null || !channel.isActive()) continue;
            if (channel instanceof LocalChannel) {
                local++;
                continue;
            }
            if (!Boolean.TRUE.equals(channel.attr(TunnelAttributes.TUNNEL).get())) {
                vanilla++;
                continue;
            }
            tunnels++;
            InetSocketAddress peer = channel.attr(TunnelAttributes.PEER).get();
            lines.add("§b[HyperConduit]§r §f[" + player.getGameProfile().getName() + "]§r"
                    + " §a隧道§r §7源 " + sourceAddress(peer) + "§r | "
                    + TelemetryText.describe(TunnelMetrics.connection(channel)));
        }
        lines.add("[HyperConduit] 总计 | 隧道 " + tunnels + " | 原版TCP " + vanilla + " | 本地 " + local);
        if (tunnels > 0) lines.add("[HyperConduit] 速率为本端 QUIC 连接统计，含协议开销与重传；共享连接不代表逐玩家有效载荷。");
        return lines;
    }

    private static String sourceAddress(InetSocketAddress peer) {
        if (peer == null) return "未知";
        return peer.getAddress() == null ? peer.getHostString() : peer.getAddress().getHostAddress();
    }

    private static String configStatus(HyperConduitConfig config) {
        if (config == null || !config.enabled) return "已禁用";
        return "已启用 | " + (config.disableBrutal ? "Brutal 禁用" : "允许协商 Brutal")
                + " | 原版TCP" + (config.disableVanillaTcp ? "禁止" : "允许");
    }
}
