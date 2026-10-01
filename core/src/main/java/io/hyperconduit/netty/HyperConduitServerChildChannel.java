package io.hyperconduit.netty;

import io.hyperconduit.conn.SessionEngine;
import io.hyperconduit.conn.SessionStats;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * The child channel a {@link HyperConduitServerChannel} hands to Minecraft's childHandler when a
 * tunnel session is accepted.
 *
 * <p>Unlike the client-side {@link HyperConduitChannel}, this channel does not own a UDP socket or
 * a driver thread: the server channel's single driver multiplexes all sessions and drives their
 * {@link SessionEngine}s directly. The child channel is purely the Netty view of one accepted
 * session — it maps Minecraft's writes onto the engine's send queue, and surfaces the engine's
 * readable data as Netty read events.
 *
 * <p>This separation matters because Minecraft's server-side childHandler expects an already
 * connected channel, not one that connects itself. The handshake is done by the time this channel
 * exists, so there is nothing to connect and no driver to start.
 */
public final class HyperConduitServerChildChannel extends AbstractChannel {

    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    private static final int READ_CHUNK = 64 * 1024;

    private final ChannelConfig config = new DefaultChannelConfig(this);
    private final SessionEngine engine;
    private final SocketAddress remoteAddress;

    private volatile boolean open = true;
    private volatile boolean readStarted;
    private volatile boolean readablePending;

    HyperConduitServerChildChannel(SessionEngine engine, SocketAddress remoteAddress) {
        super(null);
        this.engine = engine;
        this.remoteAddress = remoteAddress;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isActive() {
        return open && engine.state() != SessionEngine.State.CLOSED;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    public SessionStats stats() {
        return engine.stats();
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new HyperConduitChildUnsafe();
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    protected SocketAddress localAddress0() {
        return null;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return remoteAddress;
    }

    @Override
    protected void doBind(SocketAddress localAddress) {
        throw new UnsupportedOperationException("a child channel is not bound directly");
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() {
        open = false;
        engine.close(SessionEngine.CLOSE_REASON_LOCAL, "child channel closed");
    }

    @Override
    protected void doBeginRead() {
        readStarted = true;
        readablePending = false;
        doFireReadable();
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer out) throws Exception {
        if (engine == null || engine.state() == SessionEngine.State.CLOSED) {
            throw new IOException("child channel not connected");
        }
        ByteBuf buf;
        while ((buf = (ByteBuf) out.current()) != null) {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            engine.write(bytes, 0, bytes.length);
            out.remove();
        }
    }

    /**
     * Called by the server channel's driver when this session's engine has readable data. Fires
     * the data up Minecraft's pipeline, on the channel's event loop.
     */
    void fireReadable() {
        if (!readStarted) {
            readablePending = true;
            return;
        }
        EventLoop loop = eventLoop();
        if (loop == null) {
            readablePending = true;
            return;
        }
        if (loop.inEventLoop()) {
            doFireReadable();
        } else {
            loop.execute(this::doFireReadable);
        }
    }

    private void doFireReadable() {
        byte[] buffer = new byte[READ_CHUNK];
        List<ByteBuf> messages = new ArrayList<>();
        while (true) {
            int n = engine.read(buffer, 0, buffer.length);
            if (n <= 0) {
                break;
            }
            byte[] chunk = new byte[n];
            System.arraycopy(buffer, 0, chunk, 0, n);
            messages.add(Unpooled.wrappedBuffer(chunk));
        }
        for (ByteBuf msg : messages) {
            pipeline().fireChannelRead(msg);
        }
        if (!messages.isEmpty()) {
            pipeline().fireChannelReadComplete();
        }
    }

    private final class HyperConduitChildUnsafe extends AbstractUnsafe {
        @Override
        public void connect(SocketAddress remoteAddress, SocketAddress localAddress,
                            ChannelPromise promise) {
            promise.setFailure(new UnsupportedOperationException(
                    "a child channel does not connect"));
        }
    }
}
