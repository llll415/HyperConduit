package io.hyperconduit.mod.mixin.server;

import io.hyperconduit.mod.bridge.ServerTunnelController;
import io.hyperconduit.mod.bridge.TunnelMetrics;
import io.hyperconduit.mod.client.LanStartupFailure;
import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import io.hyperconduit.mod.probe.CapabilityDetails;
import io.hyperconduit.mod.probe.CapabilityListenerRegistry;
import io.hyperconduit.mod.probe.TunnelListenerState;
import io.hyperconduit.sidecar.BridgeInitializer;
import io.hyperconduit.sidecar.SidecarException;
import io.hyperconduit.sidecar.SidecarProcess;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

@Mixin(ServerConnectionListener.class)
public abstract class ServerConnectionListenerMixin implements TunnelListenerState {
    @Unique private final CapabilityListenerRegistry hyperconduit$listeners = new CapabilityListenerRegistry();
    @Unique private final List<ServerTunnelController> hyperconduit$controllers = new CopyOnWriteArrayList<>();

    @Override public boolean hyperconduit$acceptsTunnels() {
        return hyperconduit$listeners.anySidecarAlive();
    }

    @Override public CapabilityDetails hyperconduit$declaration() {
        return hyperconduit$listeners.declaration();
    }

    @Override public List<ServerTunnelController> hyperconduit$controllers() {
        return List.copyOf(hyperconduit$controllers);
    }

    /**
     * Vanilla NeoForge keeps the bind call in {@code startTcpServerListener}. Youer/Paper
     * moves it into {@code bind(SocketAddress)}, so one call-site selector alone would scan
     * zero targets on one of the two runtimes. Both hooks share a group so exactly one must
     * install; a silent miss or a double hook aborts startup instead of disabling the tunnel.
     */
    @Group(name = "hyperconduit_bind", min = 1, max = 1)
    @Redirect(method = "startTcpServerListener(Ljava/net/InetAddress;I)V",
            at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/ServerBootstrap;bind()Lio/netty/channel/ChannelFuture;"),
            require = 0)
    private ChannelFuture hyperconduit$bindVanilla(ServerBootstrap bootstrap) throws Exception {
        return hyperconduit$bindInternal(bootstrap);
    }

    @Group(name = "hyperconduit_bind", min = 1, max = 1)
    @Redirect(method = "bind(Ljava/net/SocketAddress;)V",
            at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/ServerBootstrap;bind()Lio/netty/channel/ChannelFuture;"),
            require = 0, remap = false)
    private ChannelFuture hyperconduit$bindYouer(ServerBootstrap bootstrap) throws Exception {
        return hyperconduit$bindInternal(bootstrap);
    }

    @Unique
    private SidecarException hyperconduit$failure(String code) {
        SidecarException failure = new SidecarException(code);
        LanStartupFailure.record(failure);
        return failure;
    }

    @Unique
    private ChannelFuture hyperconduit$bindInternal(ServerBootstrap bootstrap) throws Exception {
        // Domain sockets are not public Minecraft TCP endpoints, even with the tunnel disabled.
        if (!(bootstrap.config().localAddress() instanceof InetSocketAddress requested)) return bootstrap.bind();
        HyperConduitConfig current;
        try {
            current = ConfigHolder.get();
        } catch (RuntimeException failure) {
            // ConfigHolder may read from disk. Never echo file contents or paths; keep the
            // user-facing text Chinese and expose only the safe config error code.
            System.getLogger("io.hyperconduit.HyperConduit").log(System.Logger.Level.WARNING,
                    "[HyperConduit] 无法读取服务端配置，隧道未启动。");
            throw hyperconduit$failure("config");
        }
        if (current == null || !current.enabled) {
            // Vanilla-only listener: the registry learns tcpAllowed only after bind succeeds.
            ChannelFuture vanilla = bootstrap.bind();
            hyperconduit$listeners.add(vanilla, false, false);
            return vanilla;
        }
        HyperConduitConfig config = current.copy();
        if (config.disableVanillaTcp && requested.getPort() == 0) {
            throw hyperconduit$failure("listener_port_required");
        }
        ChannelFuture vanilla = null;
        ChannelFuture backend = null;
        CapabilityListenerRegistry.Handle handle = null;
        ServerTunnelController controller = null;
        try {
            if (!config.disableVanillaTcp) vanilla = bootstrap.bind().syncUninterruptibly();
            int port = vanilla == null ? requested.getPort() : ((InetSocketAddress) vanilla.channel().localAddress()).getPort();
            // Snapshot this listener's policy now; later config edits cannot change it until a
            // controlled restart publishes the replacement process's READY policy.
            handle = hyperconduit$listeners.add(vanilla, config.disableBrutal, !config.serverToken.isEmpty());
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            String secret = HexFormat.of().formatHex(random);
            AtomicReference<SidecarProcess> processRef = new AtomicReference<>();
            BridgeInitializer bridge = new BridgeInitializer(secret, bootstrap.config().childHandler());
            // Youer sets AUTO_READ=false on the public listener and re-enables it from
            // acceptConnections(); our private backend is not in its channel list, so it
            // must accept on its own. clone() otherwise preserves Paper's options.
            ServerBootstrap privateBootstrap = bootstrap.clone()
                    .childHandler(TunnelMetrics.serverInitializer(bridge, processRef))
                    .option(ChannelOption.AUTO_READ, true)
                    .localAddress(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            backend = privateBootstrap.bind().syncUninterruptibly();
            InetSocketAddress udp = new InetSocketAddress(requested.getAddress(), port);
            controller = ServerTunnelController.create(backend.channel(), udp, vanilla != null, secret,
                    handle, processRef);
            // The controller owns the private backend and bridge secret across restarts.
            controller.start(config);
            hyperconduit$controllers.add(controller);
            if (vanilla != null) {
                ChannelFuture ownedBackend = backend;
                vanilla.channel().closeFuture().addListener(future -> ownedBackend.channel().close());
                return vanilla;
            }
            // Minecraft/LAN still advertises the requested external port; its
            // private backend's actual random port is never published to peers.
            return backend;
        } catch (SidecarException failure) {
            // Publish the safe Chinese reason to the client LAN attempt, if one is active.
            // Dedicated servers have no active attempt, so this call is a no-op there.
            LanStartupFailure.record(failure);
            hyperconduit$cleanup(controller, handle, backend, vanilla);
            // Keep the safe, code-only reason (for example binary_unavailable or authentication)
            // instead of flattening every failure into listener_failed.
            throw failure;
        } catch (Exception failure) {
            SidecarException safe = hyperconduit$failure("listener_failed");
            hyperconduit$cleanup(controller, handle, backend, vanilla);
            throw safe;
        }
    }

    @Unique
    private void hyperconduit$cleanup(ServerTunnelController controller,
                                      CapabilityListenerRegistry.Handle handle,
                                      ChannelFuture backend, ChannelFuture vanilla) {
        // Best effort: never let cleanup hide the original, safe startup error.
        if (controller != null) {
            try {
                controller.close();
            } catch (RuntimeException ignored) {
                // The original startup failure is more useful than a cleanup failure.
            }
        }
        if (handle != null) {
            try {
                handle.remove();
            } catch (RuntimeException ignored) {
                // Registry cleanup is idempotent and cannot repair a failed bind.
            }
        }
        if (backend != null) backend.channel().close();
        if (vanilla != null) vanilla.channel().close();
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void hyperconduit$stop(CallbackInfo ci) {
        // Mark every controller stopped before blocking on child shutdown, so an in-flight
        // restart cannot publish or leak a replacement process.
        for (ServerTunnelController controller : hyperconduit$controllers) {
            controller.close();
        }
        hyperconduit$controllers.clear();
        hyperconduit$listeners.clear();
    }
}
