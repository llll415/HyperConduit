package io.hyperconduit.mod.bridge;

import io.hyperconduit.sidecar.TelemetrySnapshot;
import io.hyperconduit.sidecar.SidecarProcess;
import io.hyperconduit.sidecar.TunnelAttributes;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.util.AttributeKey;
import java.util.function.Supplier;

/** Each channel selects only its listener's process and authenticated connection ID. */
public final class TunnelMetrics {
    private TunnelMetrics() {}
    public static final AttributeKey<Supplier<TelemetrySnapshot>> SOURCE = AttributeKey.valueOf("hyperconduit.telemetry");
    public static final AttributeKey<Boolean> CLIENT = AttributeKey.valueOf("hyperconduit.telemetry.client");

    public static ChannelHandler serverInitializer(ChannelHandler bridge, java.util.concurrent.atomic.AtomicReference<SidecarProcess> processRef) {
        return new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                channel.attr(SOURCE).set(() -> {
                    SidecarProcess process = processRef.get();
                    return process == null ? null : process.telemetry();
                });
                channel.closeFuture().addListener(future -> channel.attr(SOURCE).set(null));
                channel.pipeline().addLast(bridge);
            }
        };
    }

    public static TelemetrySnapshot.Connection connection(Channel channel) {
        if (channel == null || !channel.isActive() || !Boolean.TRUE.equals(channel.attr(TunnelAttributes.TUNNEL).get())) return null;
        Supplier<TelemetrySnapshot> source = channel.attr(SOURCE).get();
        if (source == null) return null;
        TelemetrySnapshot snapshot;
        try { snapshot = source.get(); }
        catch (RuntimeException unavailable) { return null; }
        if (snapshot == null || !snapshot.available()) return null;
        Long id = channel.attr(TunnelAttributes.CONNECTION_ID).get();
        if (id != null) return snapshot.connections().get(id);
        // A client role owns exactly one QUIC connection. Never guess among server peers.
        return Boolean.TRUE.equals(channel.attr(CLIENT).get()) && snapshot.connections().size() == 1
                ? snapshot.connections().values().iterator().next() : null;
    }
}
