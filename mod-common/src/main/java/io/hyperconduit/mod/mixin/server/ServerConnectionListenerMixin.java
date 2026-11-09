package io.hyperconduit.mod.mixin.server;

import io.hyperconduit.mod.bridge.TunnelService;
import io.hyperconduit.mod.bridge.TunnelMetrics;
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
import java.util.concurrent.TimeUnit;

@Mixin(ServerConnectionListener.class)
public abstract class ServerConnectionListenerMixin implements TunnelListenerState {
    @Unique private final List<ChannelFuture> hyperconduit$backends = new CopyOnWriteArrayList<>();
    @Unique private final List<SidecarProcess> hyperconduit$children = new CopyOnWriteArrayList<>();
    @Unique private final CapabilityListenerRegistry hyperconduit$listeners = new CapabilityListenerRegistry();

    @Override public boolean hyperconduit$acceptsTunnels() {
        return hyperconduit$listeners.anySidecarAlive();
    }

    @Override public CapabilityDetails hyperconduit$declaration() {
        return hyperconduit$listeners.declaration();
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
    private ChannelFuture hyperconduit$bindInternal(ServerBootstrap bootstrap) throws Exception {
        HyperConduitConfig current = ConfigHolder.get();
        // Domain sockets are not public Minecraft TCP endpoints, even with the tunnel disabled.
        if (!(bootstrap.config().localAddress() instanceof InetSocketAddress requested)) return bootstrap.bind();
        if (current == null || !current.enabled) {
            // Vanilla-only listener: the registry learns tcpAllowed only after bind succeeds.
            ChannelFuture vanilla = bootstrap.bind();
            hyperconduit$listeners.add(vanilla, false, false);
            return vanilla;
        }
        HyperConduitConfig config = current.copy();
        if (config.disableVanillaTcp && requested.getPort() == 0) {
            throw new SidecarException("listener_port_required");
        }
        ChannelFuture vanilla = null;
        ChannelFuture backend = null;
        SidecarProcess child = null;
        CapabilityListenerRegistry.Handle handle = null;
        try {
            if (!config.disableVanillaTcp) vanilla = bootstrap.bind().syncUninterruptibly();
            int port = vanilla == null ? requested.getPort() : ((InetSocketAddress) vanilla.channel().localAddress()).getPort();
            // Snapshot this listener's policy now; later config edits cannot change it.
            handle = hyperconduit$listeners.add(vanilla, config.disableBrutal, !config.serverToken.isEmpty());
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            String secret = HexFormat.of().formatHex(random);
            var processRef = new java.util.concurrent.atomic.AtomicReference<SidecarProcess>();
            var bridge = new BridgeInitializer(secret, bootstrap.config().childHandler());
            // Youer sets AUTO_READ=false on the public listener and re-enables it from
            // acceptConnections(); our private backend is not in its channel list, so it
            // must accept on its own. clone() otherwise preserves Paper's options.
            ServerBootstrap privateBootstrap = bootstrap.clone()
                    .childHandler(TunnelMetrics.serverInitializer(bridge, processRef))
                    .option(ChannelOption.AUTO_READ, true)
                    .localAddress(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            backend = privateBootstrap.bind().syncUninterruptibly();
            InetSocketAddress udp = new InetSocketAddress(requested.getAddress(), port);
            child = TunnelService.server(config, udp, (InetSocketAddress) backend.channel().localAddress(),
                    secret, vanilla != null);
            child.ready().get(config.connectTimeoutMs + 6000L, TimeUnit.MILLISECONDS);
            processRef.set(child);
            child.enableTelemetry();
            hyperconduit$backends.add(backend);
            hyperconduit$children.add(child);
            ChannelFuture ownedBackend = backend;
            SidecarProcess ownedChild = child;
            CapabilityListenerRegistry.Handle ownedHandle = handle;
            ownedHandle.observeSidecar(ownedChild::isAlive);
            child.exit().thenAccept(code -> {
                ownedBackend.channel().close();
                // Keep the public-TCP fact until its own closeFuture removes the entry;
                // only enabled must disappear here.
                ownedHandle.sidecarAlive(false);
            });
            backend.channel().closeFuture().addListener(future -> Thread.ofVirtual().start(ownedChild::close));
            if (vanilla != null) {
                vanilla.channel().closeFuture().addListener(future -> ownedBackend.channel().close());
                return vanilla;
            }
            // Minecraft/LAN still advertises the requested external port; its
            // private backend's actual random port is never published to peers.
            return backend;
        } catch (Exception ignored) {
            if (handle != null) handle.remove();
            if (child != null) child.close();
            if (backend != null) backend.channel().close();
            if (vanilla != null) vanilla.channel().close();
            throw new SidecarException("listener_failed");
        }
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void hyperconduit$stop(CallbackInfo ci) {
        hyperconduit$backends.forEach(future -> future.channel().close());
        hyperconduit$children.forEach(SidecarProcess::close);
        hyperconduit$backends.clear();
        hyperconduit$children.clear();
        hyperconduit$listeners.clear();
    }
}
