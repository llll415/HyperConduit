package io.hyperconduit.mod.mixin;

import io.hyperconduit.mod.bridge.ConnectionIdentity;
import io.hyperconduit.mod.bridge.ConnectionAttemptScope;
import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.probe.StatusProbeScope;
import io.hyperconduit.sidecar.TunnelAttributes;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.net.SocketAddress;
import io.netty.channel.Channel;

@Mixin(Connection.class)
public abstract class ConnectionAddressMixin implements ConnectionIdentity {
    @Unique private String hyperconduit$endpoint;

    @Override public void hyperconduit$endpoint(String endpoint) { this.hyperconduit$endpoint = endpoint; }
    @Override public String hyperconduit$endpoint() { return hyperconduit$endpoint; }

    @Redirect(method = "channelActive", at = @At(value = "INVOKE", target = "Lio/netty/channel/Channel;remoteAddress()Ljava/net/SocketAddress;"))
    private SocketAddress hyperconduit$restorePeer(Channel channel) {
        SocketAddress peer = channel.attr(TunnelAttributes.PEER).get();
        return peer != null ? peer : channel.remoteAddress();
    }

    @ModifyVariable(method = "initiateServerboundConnection", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private String hyperconduit$handshakeHost(String host) {
        if (StatusProbeScope.active()) return host;
        var config = ConnectionAttemptScope.snapshot();
        if (config == null) config = ConfigHolder.getForConnection();
        if (hyperconduit$endpoint == null || !config.enabled) return host;
        int colon = hyperconduit$endpoint.lastIndexOf(':');
        String value = hyperconduit$endpoint.substring(0, colon);
        return value.startsWith("[") && value.endsWith("]") ? value.substring(1, value.length() - 1) : value;
    }

    @ModifyVariable(method = "initiateServerboundConnection", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int hyperconduit$handshakePort(int port) {
        if (StatusProbeScope.active()) return port;
        var config = ConnectionAttemptScope.snapshot();
        if (config == null) config = ConfigHolder.getForConnection();
        return hyperconduit$endpoint == null || !config.enabled ? port : Integer.parseInt(hyperconduit$endpoint.substring(hyperconduit$endpoint.lastIndexOf(':') + 1));
    }
}
