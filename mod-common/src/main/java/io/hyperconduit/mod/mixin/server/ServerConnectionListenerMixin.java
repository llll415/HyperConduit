package io.hyperconduit.mod.mixin.server;

import io.hyperconduit.mod.bridge.TunnelService;
import io.hyperconduit.mod.bridge.TunnelMetrics;
import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import io.hyperconduit.sidecar.BridgeInitializer;
import io.hyperconduit.sidecar.SidecarProcess;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
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
public abstract class ServerConnectionListenerMixin implements io.hyperconduit.mod.probe.TunnelListenerState {
    @Unique private final List<ChannelFuture> hyperconduit$backends = new CopyOnWriteArrayList<>();
    @Unique private final List<SidecarProcess> hyperconduit$children = new CopyOnWriteArrayList<>();

    @Override public boolean hyperconduit$acceptsTunnels() {
        return hyperconduit$children.stream().anyMatch(SidecarProcess::isAlive);
    }

    @Redirect(method = "startTcpServerListener(Ljava/net/InetAddress;I)V",
            at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/ServerBootstrap;bind()Lio/netty/channel/ChannelFuture;"))
    private ChannelFuture hyperconduit$bind(ServerBootstrap bootstrap) throws Exception {
        HyperConduitConfig current = ConfigHolder.get();
        if (current == null || !current.enabled) return bootstrap.bind();
        HyperConduitConfig config = current.copy();
        InetSocketAddress requested = (InetSocketAddress) bootstrap.config().localAddress();
        if (config.disableVanillaTcp && requested.getPort() == 0) {
            throw new java.io.IOException("HyperConduit tunnel-only mode requires a fixed advertised port");
        }
        ChannelFuture vanilla = null;
        ChannelFuture backend = null;
        SidecarProcess child = null;
        try {
            if (!config.disableVanillaTcp) vanilla = bootstrap.bind().syncUninterruptibly();
            int port = vanilla == null ? requested.getPort() : ((InetSocketAddress) vanilla.channel().localAddress()).getPort();
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            String secret = HexFormat.of().formatHex(random);
            var processRef = new java.util.concurrent.atomic.AtomicReference<SidecarProcess>();
            var bridge = new BridgeInitializer(secret, bootstrap.config().childHandler());
            ServerBootstrap privateBootstrap = bootstrap.clone()
                    .childHandler(TunnelMetrics.serverInitializer(bridge, processRef))
                    .localAddress(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            backend = privateBootstrap.bind().syncUninterruptibly();
            InetSocketAddress udp = new InetSocketAddress(requested.getAddress(), port);
            child = TunnelService.server(config, udp, (InetSocketAddress) backend.channel().localAddress(), secret);
            child.ready().get(config.connectTimeoutMs + 6000L, TimeUnit.MILLISECONDS);
            processRef.set(child);
            child.enableTelemetry();
            hyperconduit$backends.add(backend);
            hyperconduit$children.add(child);
            ChannelFuture ownedBackend = backend;
            SidecarProcess ownedChild = child;
            child.exit().thenAccept(code -> ownedBackend.channel().close());
            backend.channel().closeFuture().addListener(future -> Thread.ofVirtual().start(ownedChild::close));
            if (vanilla != null) {
                vanilla.channel().closeFuture().addListener(future -> ownedBackend.channel().close());
                return vanilla;
            }
            // Minecraft/LAN still advertises the requested external port; its
            // private backend's actual random port is never published to peers.
            return backend;
        } catch (Exception failure) {
            if (child != null) child.close();
            if (backend != null) backend.channel().close();
            if (vanilla != null) vanilla.channel().close();
            throw new java.io.IOException("HyperConduit listener failed", failure);
        }
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void hyperconduit$stop(CallbackInfo ci) {
        hyperconduit$backends.forEach(future -> future.channel().close());
        hyperconduit$children.forEach(SidecarProcess::close);
        hyperconduit$backends.clear();
        hyperconduit$children.clear();
    }
}
