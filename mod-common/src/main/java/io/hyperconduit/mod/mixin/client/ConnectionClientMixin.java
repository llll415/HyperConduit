package io.hyperconduit.mod.mixin.client;

import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import io.hyperconduit.netty.HyperConduitChannel;
import net.minecraft.network.Connection;
import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Intercepts the client-side connection bootstrap in
 * {@link Connection#connect(InetSocketAddress, boolean, Connection)}.
 *
 * <p>The redirect sits on {@code Bootstrap.channel(Class)} — the call that installs Minecraft's
 * NIO/epoll channel factory. Replacing it here, rather than later at {@code connect()}, is not a
 * stylistic choice: Netty's {@code channelFactory()} throws {@code IllegalStateException} if the
 * factory is already set, so a later swap cannot work at all. At this point the factory is still
 * unset, and swapping it in one call keeps Minecraft's own handler and pipeline attached to the
 * Bootstrap untouched.
 *
 * <p>The channel itself resolves the actual tunnel target at connect time: the configured
 * {@code tunnelServer} when one is set (passed as an explicit peer), or the address from the
 * player's server list when it is blank. Loopback targets are refused there, not here, because
 * the address is not available at this injection point.
 *
 * <p>When the mod is disabled the original channel class passes through untouched, which is what
 * makes "disabled" mean plain vanilla TCP.
 */
@Mixin(Connection.class)
public abstract class ConnectionClientMixin {

    @Redirect(
            method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;"
            )
    )
    private static AbstractBootstrap hyperconduit$swapChannel(
            Bootstrap bootstrap,
            Class<? extends Channel> originalChannelClass) {

        HyperConduitConfig config = ConfigHolder.get();
        if (config == null || !config.enabled) {
            return bootstrap.channel(originalChannelClass);
        }

        // A null peer means the channel resolves the target at connect time: the configured
        // tunnelServer if one is set, otherwise whatever address Minecraft is connecting to.
        java.net.InetSocketAddress explicitPeer = null;
        if (config.hasExplicitTunnelServer()) {
            HyperConduitConfig.TunnelAddress configured = config.parseTunnelServerAddress();
            explicitPeer = new java.net.InetSocketAddress(configured.host(), configured.port());
        }
        final java.net.InetSocketAddress peer = explicitPeer;
        return bootstrap.channelFactory(() -> new HyperConduitChannel(
                new HyperConduitChannel.ChannelOptions(peer, config.clientSessionConfig())));
    }
}
