package io.hyperconduit.mod.bridge;

import io.hyperconduit.mod.config.HyperConduitConfig;
import io.hyperconduit.sidecar.SidecarProcess;
import io.hyperconduit.sidecar.TunnelAttributes;
import io.netty.channel.*;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Defers only the socket dial. Minecraft still sends its original handshake. */
public final class ClientTunnelInitializer extends ChannelInitializer<Channel> {
    private final ChannelHandler minecraft;
    private final HyperConduitConfig config;
    private final String endpoint;

    public ClientTunnelInitializer(ChannelHandler minecraft, HyperConduitConfig config, String endpoint) {
        this.minecraft = minecraft;
        this.config = config;
        this.endpoint = endpoint;
    }

    @Override protected void initChannel(Channel ch) {
        ch.pipeline().addLast("hyperconduit-dial", new Dial());
        ch.pipeline().addLast(minecraft);
    }

    private final class Dial extends ChannelOutboundHandlerAdapter {
        private final AtomicReference<SidecarProcess> child = new AtomicReference<>();
        private final AtomicBoolean canceled = new AtomicBoolean();

        private void onLoop(ChannelHandlerContext ctx, Runnable action) {
            try { ctx.executor().execute(action); }
            catch (java.util.concurrent.RejectedExecutionException stopped) { stop(); }
        }

        @Override public void handlerAdded(ChannelHandlerContext ctx) {
            ctx.channel().closeFuture().addListener(future -> stop());
        }

        private void stop() {
            canceled.set(true);
            SidecarProcess process = child.get();
            if (process != null) Thread.ofVirtual().start(process::close);
        }

        @Override public void connect(ChannelHandlerContext ctx, SocketAddress remote, SocketAddress local, ChannelPromise promise) {
            if (!(remote instanceof InetSocketAddress target) || target.isUnresolved()) {
                promise.tryFailure(new IllegalArgumentException("Unresolved server address"));
                ctx.close(); return;
            }
            promise.addListener(future -> { if (!future.isSuccess()) { stop(); ctx.close(); } });
            Thread.ofVirtual().name("hyperconduit-client-start").start(() -> {
                try {
                    if (canceled.get() || promise.isCancelled()) return;
                    String trustEndpoint = endpoint == null ? TunnelService.format(target) : endpoint;
                    SidecarProcess process = TunnelService.client(config, target, trustEndpoint);
                    child.set(process);
                    if (canceled.get() || !ctx.channel().isOpen()) { process.close(); return; }
                    process.exit().thenAccept(code -> onLoop(ctx, () -> ctx.close()));
                    process.ready().whenComplete((ready, failure) -> onLoop(ctx, () -> {
                        if (failure != null) { promise.tryFailure(failure); ctx.close(); return; }
                        if (canceled.get() || promise.isCancelled() || !ctx.channel().isOpen()) { stop(); return; }
                        ctx.channel().attr(TunnelAttributes.PEER).set(target);
                        ctx.channel().attr(TunnelAttributes.TUNNEL).set(true);
                        // The original optional local bind may target an external
                        // interface; the bridge must use a compatible loopback bind.
                        ctx.connect(ready.listen(), null, promise);
                    }));
                } catch (Exception failure) {
                    onLoop(ctx, () -> { promise.tryFailure(failure); ctx.close(); });
                }
            });
        }
    }
}
