package io.hyperconduit.mod.bridge;

import io.hyperconduit.sidecar.SidecarProcess;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryInitializerTest {
    @Test void installsSourceBeforeBridgeAndClearsItOnClose() {
        var ref = new AtomicReference<SidecarProcess>();
        var seen = new java.util.concurrent.atomic.AtomicBoolean();
        var bridge = new ChannelInboundHandlerAdapter() {
            @Override public void handlerAdded(ChannelHandlerContext context) {
                assertNotNull(context.channel().attr(TunnelMetrics.SOURCE).get());
                assertNull(context.channel().attr(TunnelMetrics.SOURCE).get().get());
                seen.set(true);
            }
        };
        EmbeddedChannel channel = new EmbeddedChannel(TunnelMetrics.serverInitializer(bridge, ref));
        assertTrue(seen.get());
        channel.close();
        assertNull(channel.attr(TunnelMetrics.SOURCE).get());
        channel.finishAndReleaseAll();
    }

    @Test void optionalBrokenMetricsNeverBreaksTheGameChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(io.hyperconduit.sidecar.TunnelAttributes.TUNNEL).set(true);
        channel.attr(TunnelMetrics.SOURCE).set(() -> { throw new IllegalStateException("metrics unavailable"); });
        assertNull(TunnelMetrics.connection(channel));
        assertTrue(channel.isActive());
        channel.finishAndReleaseAll();
    }
}
