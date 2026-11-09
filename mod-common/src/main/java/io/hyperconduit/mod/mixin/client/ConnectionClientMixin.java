package io.hyperconduit.mod.mixin.client;

import io.hyperconduit.mod.bridge.ClientTunnelInitializer;
import io.hyperconduit.mod.bridge.ConnectionIdentity;
import io.hyperconduit.mod.bridge.ResolvedEndpoint;
import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandler;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import io.netty.channel.ChannelFuture;

import java.net.InetSocketAddress;

@Mixin(Connection.class)
public abstract class ConnectionClientMixin {
    @Inject(method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;", at = @At("HEAD"))
    private static void hyperconduit$captureEndpoint(InetSocketAddress target, boolean epoll, Connection connection,
                                                       CallbackInfoReturnable<ChannelFuture> ci) {
        ((ConnectionIdentity) connection).hyperconduit$endpoint(ResolvedEndpoint.consume(target));
    }

    @Redirect(method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
            at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;handler(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/AbstractBootstrap;"))
    private static AbstractBootstrap hyperconduit$wrapInitializer(Bootstrap bootstrap, ChannelHandler initializer,
                                                                  InetSocketAddress target, boolean epoll, Connection connection) {
        HyperConduitConfig config = ConfigHolder.get();
        if (config == null || !config.enabled || io.hyperconduit.mod.probe.StatusProbeScope.active()) return bootstrap.handler(initializer);
        String endpoint = ((ConnectionIdentity) connection).hyperconduit$endpoint();
        return bootstrap.handler(new ClientTunnelInitializer(initializer, config.copy(), endpoint));
    }
}
