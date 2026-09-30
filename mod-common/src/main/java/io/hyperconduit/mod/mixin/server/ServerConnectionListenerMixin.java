package io.hyperconduit.mod.mixin.server;

import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import io.hyperconduit.netty.HyperConduitServerChannel;
import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.bootstrap.ServerBootstrapConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.net.SocketAddress;
import java.util.List;

/**
 * Adds a HyperConduit UDP listener beside Minecraft's normal TCP listener.
 *
 * <p>TCP and UDP may bind the same address and port. By default that keeps vanilla clients
 * compatible while HyperConduit clients use UDP. Setting {@code disableVanillaTcp} switches the
 * bootstrap channel itself, so only the tunnel listener is created.
 */
@Mixin(ServerConnectionListener.class)
public abstract class ServerConnectionListenerMixin {

    @Shadow @Final private List<ChannelFuture> channels;

    @Redirect(
            method = "startTcpServerListener(Ljava/net/InetAddress;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/netty/bootstrap/ServerBootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;"
            )
    )
    private AbstractBootstrap hyperconduit$selectServerChannel(
            ServerBootstrap bootstrap,
            Class<? extends ServerChannel> originalChannelClass) {
        HyperConduitConfig config = ConfigHolder.get();
        if (config == null || !config.enabled || !config.disableVanillaTcp) {
            return bootstrap.channel(originalChannelClass);
        }
        return bootstrap.channelFactory(() -> new HyperConduitServerChannel(
                new HyperConduitServerChannel.ServerOptions(null, config.serverSessionConfig())));
    }

    @Redirect(
            method = "startTcpServerListener(Ljava/net/InetAddress;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/netty/bootstrap/ServerBootstrap;bind()Lio/netty/channel/ChannelFuture;"
            )
    )
    private ChannelFuture hyperconduit$bindListeners(ServerBootstrap bootstrap) {
        HyperConduitConfig config = ConfigHolder.get();
        if (config == null || !config.enabled || config.disableVanillaTcp) {
            return bootstrap.bind();
        }

        ServerBootstrapConfig bootstrapConfig = bootstrap.config();
        SocketAddress localAddress = bootstrapConfig.localAddress();
        EventLoopGroup parentGroup = bootstrapConfig.group();
        EventLoopGroup childGroup = bootstrapConfig.childGroup();
        ServerBootstrap tunnelBootstrap = new ServerBootstrap()
                .group(parentGroup, childGroup)
                .channelFactory(() -> new HyperConduitServerChannel(
                        new HyperConduitServerChannel.ServerOptions(null, config.serverSessionConfig())))
                .childHandler(bootstrapConfig.childHandler())
                .localAddress(localAddress);
        ChannelFuture tunnelFuture = tunnelBootstrap.bind().syncUninterruptibly();
        channels.add(tunnelFuture);
        ChannelFuture vanillaFuture = bootstrap.bind();
        vanillaFuture.addListener(future -> {
            if (!future.isSuccess()) {
                tunnelFuture.channel().close();
            }
        });
        return vanillaFuture;
    }
}
