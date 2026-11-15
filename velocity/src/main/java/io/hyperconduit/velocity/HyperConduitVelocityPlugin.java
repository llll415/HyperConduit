package io.hyperconduit.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ListenerBoundEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.network.ListenerType;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.hyperconduit.velocity.command.HyperConduitCommand;
import io.hyperconduit.velocity.command.TunnelConfigValues;
import io.hyperconduit.velocity.config.VelocityConfig;
import io.hyperconduit.velocity.config.VelocityConfigStore;
import io.hyperconduit.velocity.status.PacketEventsStatusListener;
import io.hyperconduit.velocity.status.StatusService;
import io.hyperconduit.velocity.tunnel.EndpointResolver;
import io.hyperconduit.velocity.tunnel.VelocityTunnelController;
import io.hyperconduit.velocity.watch.WatchService;
import org.slf4j.Logger;

import com.google.inject.Inject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * HyperConduit's Velocity entry point.
 *
 * <p>The plugin is server-side only: Velocity's Minecraft listener remains the public TCP
 * endpoint, and a managed sidecar listens on the same address/port over UDP. The sidecar forwards
 * tunnel streams to Velocity's loopback TCP listener. No Velocity TCP stack, status packet or
 * private implementation is modified.</p>
 */
@Plugin(
        id = "hyperconduit",
        name = "HyperConduit",
        version = "1.1.0",
        description = "QUIC/Hysteria 2 transport for Minecraft behind a Velocity proxy",
        authors = {"HyperConduit"},
        dependencies = @Dependency(id = "packetevents"))
public final class HyperConduitVelocityPlugin {

    private static final String PREFIX = "[HyperConduit] ";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private VelocityConfigStore store;
    private VelocityTunnelController controller;
    private StatusService statusService;
    private WatchService watchService;
    private PacketListenerCommon statusListener;

    @Inject
    public HyperConduitVelocityPlugin(ProxyServer server, Logger logger,
                                      @DataDirectory Path dataDirectory) {
        this.server = Objects.requireNonNull(server, "server");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath()
                .normalize();
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException failure) {
            logger.error(PREFIX + "无法创建插件数据目录，配置与身份可能无法保存。");
        }
        store = new VelocityConfigStore(dataDirectory);
        controller = VelocityTunnelController.create(dataDirectory, store);
        statusService = new StatusService(server, controller);
        watchService = new WatchService(server, this, statusService::lines);
        registerCommand();
        registerStatusAugmentation();
        watchService.start();
        logConfigurationState();
    }

    @Subscribe
    public void onListenerBound(ListenerBoundEvent event) {
        if (event.getListenerType() != ListenerType.MINECRAFT || controller == null) {
            return;
        }
        InetSocketAddress bound = server.getBoundAddress();
        if (bound == null) {
            bound = event.getAddress();
        }
        if (bound == null) {
            logger.warn(PREFIX + "无法从 Velocity 获取已绑定的 Minecraft 监听地址，未启动隧道程序。");
            return;
        }

        InetSocketAddress address = bound;
        EndpointResolver.resolve(address).ifPresentOrElse(endpoint -> {
            logger.info(PREFIX + "检测到 Minecraft 监听器 " + EndpointResolver.format(endpoint.listen())
                    + "，准备启动隧道程序。");
            controller.startIfEnabled(endpoint).whenComplete((result, failure) -> {
                if (failure != null) {
                    logger.warn(PREFIX + "隧道程序启动请求失败，请查看日志。");
                    return;
                }
                if (result == null) {
                    return;
                }
                if (result.started()) {
                    logger.info(result.message());
                } else if (result.status() == VelocityTunnelController.Status.DISABLED) {
                    logger.info(result.message());
                } else {
                    logger.warn(result.message());
                }
            });
        }, () -> logger.warn(PREFIX + "监听地址不是可用的 IP:端口，未启动隧道程序。"));
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        WatchService currentWatch = watchService;
        if (currentWatch != null) {
            currentWatch.close();
        }
        unregisterStatusAugmentation();
        VelocityTunnelController current = controller;
        if (current != null) {
            current.close();
            logger.info(PREFIX + "隧道程序已停止。");
        }
    }

    private void registerCommand() {
        CommandManager manager = server.getCommandManager();
        manager.register(
                manager.metaBuilder("hyperconduit").plugin(this).build(),
                new HyperConduitCommand(store, controller, statusService, watchService));
    }

    /**
     * PacketEvents is a declared prerequisite and remains a separate GPL-3.0
     * plugin. Only its public listener API is used; a missing or incompatible
     * version leaves status rendering functional without the extra JSON field.
     */
    private void registerStatusAugmentation() {
        try {
            PacketEventsAPI<?> api = PacketEvents.getAPI();
            if (api == null || !api.isInitialized()) {
                logger.warn(PREFIX + "PacketEvents 尚未初始化，状态 JSON 不会附加 hyperconduit 字段。");
                return;
            }
            statusListener = api.getEventManager().registerListener(
                    new PacketEventsStatusListener(controller::runningConfig));
        } catch (RuntimeException | LinkageError unavailable) {
            logger.warn(PREFIX + "PacketEvents 状态扩展不可用；原版状态响应保持原样。");
        }
    }

    private void unregisterStatusAugmentation() {
        PacketListenerCommon current = statusListener;
        statusListener = null;
        if (current == null) {
            return;
        }
        try {
            PacketEventsAPI<?> api = PacketEvents.getAPI();
            if (api != null) {
                api.getEventManager().unregisterListener(current);
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // Proxy shutdown must continue even if PacketEvents already stopped.
        }
    }

    private void logConfigurationState() {
        try {
            VelocityConfig config = store.load();
            if (!config.enabled) {
                logger.info(PREFIX + "HyperConduit 未启用；编辑 plugins/hyperconduit/config.json 后重启代理。");
            }
            if (config.disableVanillaTcp) {
                logger.warn(PREFIX + "配置项 disableVanillaTcp 在 Velocity 下无效："
                        + "插件不会关闭 Velocity 的 TCP 监听。");
            }
            if (config.proxyProtocolV2Output) {
                logger.info(PREFIX + "已配置 Proxy Protocol v2 输出；"
                        + "请确认 velocity.toml 的 haproxy-protocol = true。");
            }
        } catch (RuntimeException failure) {
            logger.warn(PREFIX + "配置文件无效：" + TunnelConfigValues.explain(failure));
        }
    }
}
