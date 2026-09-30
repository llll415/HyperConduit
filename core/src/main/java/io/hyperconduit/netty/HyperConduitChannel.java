package io.hyperconduit.netty;

import io.hyperconduit.conn.SessionConfig;
import io.hyperconduit.conn.SessionEngine;
import io.hyperconduit.conn.SessionListener;
import io.hyperconduit.net.DriverWait;
import io.hyperconduit.util.Clock;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import io.netty.channel.RecvByteBufAllocator;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.List;

/**
 * A Netty {@link AbstractChannel} whose backing transport is a HyperConduit reliable-UDP session
 * instead of a kernel TCP socket.
 *
 * <p>From Minecraft's point of view this behaves exactly like a connection-oriented byte-stream
 * channel: connect, write bytes, read bytes, close. Underneath those bytes are the payload of a
 * {@link SessionEngine} tunnel. MC's own pipeline (VarInt framing, compression, login encryption,
 * packet handlers) stays unchanged — it just sees a different transport below it.
 *
 * <p>Threading: the channel owns one driver thread that multiplexes the UDP socket and the
 * tunnel's clock-driven timers (pacing, loss detection, retransmission). The driver is what turns
 * the engine's "give me the next datagram / how long can I sleep" contract into Netty events, and
 * it is what wakes the channel up when bytes from MC need to go out.
 */
public final class HyperConduitChannel extends AbstractChannel {

    /**
     * How the bridge should create its session.
     *
     * <p>{@code peer} may be null, meaning "resolve the tunnel target at connect time from the
     * address Minecraft passes in" — that is how the client mixin uses it when the user has not
     * configured an explicit tunnel server. An explicit peer always wins.
     */
    public record ChannelOptions(
            InetSocketAddress peer,
            SessionConfig sessionConfig,
            Clock clock) {

        public ChannelOptions(InetSocketAddress peer, SessionConfig sessionConfig) {
            this(peer, sessionConfig, Clock.SYSTEM);
        }

        /** Peer is resolved at connect time from the address Minecraft passes in. */
        public ChannelOptions(SessionConfig sessionConfig, Clock clock) {
            this(null, sessionConfig, clock);
        }
    }

    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    private static final int READ_BUFFER_SIZE = 64 * 1024;
    private static final int SOCKET_BUFFER_BYTES = 4 * 1024 * 1024;

    private final ChannelOptions options;
    private final ChannelConfig config = new DefaultChannelConfig(this);

    private volatile DatagramChannel datagramChannel;
    private volatile Selector selector;
    private volatile SessionEngine engine;
    private volatile Thread driverThread;
    private volatile boolean running;
    private volatile boolean open = true;
    private volatile SocketAddress remoteAddress;
    /** Where the tunnel actually sends: an explicit peer, or the resolved Minecraft target. */
    private volatile InetSocketAddress tunnelPeer;
    private volatile boolean handshaken;

    public HyperConduitChannel(ChannelOptions options) {
        super(null);
        this.options = options;
        this.remoteAddress = options.peer();
        this.tunnelPeer = options.peer();
    }

    @Override
    public boolean isActive() {
        return handshaken;
    }

    @Override
    public boolean isOpen() {
        return open;
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
    protected AbstractUnsafe newUnsafe() {
        return new HyperConduitUnsafe();
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
        throw new UnsupportedOperationException("HyperConduitChannel is connection-initiated only");
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        open = false;
        running = false;
        if (selector != null) {
            selector.wakeup();
        }
        if (driverThread != null) {
            driverThread.join(2000);
        }
        if (engine != null) {
            engine.close(SessionEngine.CLOSE_REASON_LOCAL, "channel closed");
        }
        closeDatagramChannel();
    }

    @Override
    protected void doBeginRead() {
        // Reads are event-driven from the driver thread.
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer out) throws Exception {
        if (engine == null) {
            throw new IOException("channel not connected");
        }
        ByteBuf buf;
        while ((buf = (ByteBuf) out.current()) != null) {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            engine.write(bytes, 0, bytes.length);
            out.remove();
        }
    }

    public SessionEngine engine() {
        return engine;
    }

    public boolean isHandshaken() {
        return engine != null && engine.state() == SessionEngine.State.ESTABLISHED;
    }

    /**
     * No public entry point: Minecraft drives this channel through the standard Netty Bootstrap
     * contract, so connection setup happens inside {@link AbstractUnsafe#connect}, where the
     * handshake completes the connect promise only when the tunnel is actually usable.
     */
    private void startDriver(ChannelPromise connectPromise) {
        running = true;
        driverThread = new Thread(() -> runDriver(connectPromise), "hyperconduit-driver");
        driverThread.setDaemon(true);
        driverThread.start();
    }

    private void runDriver(ChannelPromise connectPromise) {
        try {
            datagramChannel = DatagramChannel.open();
            datagramChannel.configureBlocking(false);
            // Brutal's bursts overflow the OS default UDP buffers well below the configured rate;
            // the resulting self-inflicted drops are indistinguishable from network loss.
            io.hyperconduit.net.SocketBuffers.apply(datagramChannel, SOCKET_BUFFER_BYTES, "hyperconduit");
            selector = Selector.open();
            datagramChannel.register(selector, SelectionKey.OP_READ);

            engine = SessionEngine.client(options.sessionConfig(), options.clock(), new SessionListener() {
                @Override
                public void onPeerConfirmed() {
                    handshaken = true;
                    EventLoop loop = eventLoop();
                    if (loop != null && loop.inEventLoop()) {
                        fireChannelActiveNow();
                    } else if (loop != null) {
                        loop.execute(this::fireChannelActiveNow);
                    } else {
                        fireChannelActiveNow();
                    }
                }

                private void fireChannelActiveNow() {
                    pipeline().fireChannelActive();
                    if (!connectPromise.isDone()) {
                        connectPromise.setSuccess();
                    }
                }

                @Override
                public void onReadable() {
                    EventLoop loop = eventLoop();
                    if (loop != null && loop.inEventLoop()) {
                        fireReadable();
                    } else if (loop != null) {
                        loop.execute(() -> fireReadable());
                    } else {
                        fireReadable();
                    }
                }

                @Override
                public void onWritable() {
                    EventLoop loop = eventLoop();
                    if (loop != null && loop.inEventLoop()) {
                        pipeline().fireChannelWritabilityChanged();
                    } else if (loop != null) {
                        loop.execute(() -> pipeline().fireChannelWritabilityChanged());
                    }
                }

                @Override
                public void onClosed(int reasonCode, String message) {
                    EventLoop loop = eventLoop();
                    Runnable task = () -> {
                        if (!connectPromise.isDone()) {
                            connectPromise.setFailure(
                                    new IOException("tunnel closed before handshake: " + message));
                        }
                        pipeline().fireChannelInactive();
                        close();
                    };
                    if (loop != null && loop.inEventLoop()) {
                        task.run();
                    } else if (loop != null) {
                        loop.execute(task);
                    } else {
                        task.run();
                    }
                }

                @Override
                public void onError(Throwable cause) {
                    EventLoop loop = eventLoop();
                    Runnable task = () -> {
                        if (!connectPromise.isDone()) {
                            connectPromise.setFailure(cause);
                        } else {
                            pipeline().fireExceptionCaught(cause);
                        }
                        close();
                    };
                    if (loop != null && loop.inEventLoop()) {
                        task.run();
                    } else if (loop != null) {
                        loop.execute(task);
                    } else {
                        task.run();
                    }
                }
            });
            engine.setWakeupHook(selector::wakeup);

            ByteBuffer readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE);
            while (running && engine.state() != SessionEngine.State.CLOSED) {
                long now = options.clock().nanoTime();
                long waitNanos = engine.nanosUntilNextEvent(now);
                DriverWait.await(selector, waitNanos);
                selector.selectedKeys().clear();
                receiveDatagrams(readBuffer);
                transmitDatagrams();
            }
        } catch (Throwable t) {
            if (!connectPromise.isDone()) {
                connectPromise.setFailure(t);
            } else {
                pipeline().fireExceptionCaught(t);
            }
        } finally {
            closeDatagramChannel();
        }
    }

    private void receiveDatagrams(ByteBuffer readBuffer) throws IOException {
        while (true) {
            readBuffer.clear();
            SocketAddress from = datagramChannel.receive(readBuffer);
            if (from == null) {
                return;
            }
            readBuffer.flip();
            byte[] datagram = new byte[readBuffer.remaining()];
            readBuffer.get(datagram);
            engine.onDatagramReceived(datagram, 0, datagram.length);
        }
    }

    private void transmitDatagrams() throws IOException {
        while (true) {
            byte[] datagram = engine.nextDatagram();
            if (datagram == null) {
                return;
            }
            datagramChannel.send(ByteBuffer.wrap(datagram), tunnelPeer);
        }
    }

    private void fireReadable() {
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        List<ByteBuf> messages = new ArrayList<>();
        while (true) {
            int n = engine.read(buffer, 0, buffer.length);
            if (n <= 0) {
                break;
            }
            // Unpooled.wrappedBuffer does not copy: sharing this buffer across multiple ByteBufs
            // would let the next read overwrite what the previous one already handed out.
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

    private void closeDatagramChannel() {
        try {
            if (selector != null) {
                selector.close();
            }
        } catch (IOException ignored) {
            // nothing useful to do while tearing down
        }
        try {
            if (datagramChannel != null) {
                datagramChannel.close();
            }
        } catch (IOException ignored) {
            // nothing useful to do while tearing down
        }
    }

    private final class HyperConduitUnsafe extends AbstractUnsafe {
        @Override
        public void connect(SocketAddress remoteAddress, SocketAddress localAddress,
                            ChannelPromise promise) {
            HyperConduitChannel.this.remoteAddress = remoteAddress;
            InetSocketAddress target = resolveTunnelTarget(remoteAddress);
            if (target == null) {
                // Refusing rather than tunneling: a loopback target would mean shipping the
                // player's own local traffic out to a remote server and back. Minecraft's
                // single-player path never reaches here (it uses LocalChannel), so this only
                // fires when someone points a server list entry at localhost with the mod on.
                promise.setFailure(new java.net.ConnectException(
                        "HyperConduit refuses to tunnel a loopback connection: " + remoteAddress));
                return;
            }
            HyperConduitChannel.this.tunnelPeer = target;
            // The whole point of this class: the connect promise is NOT completed here. It is
            // completed by the driver's SessionListener when the handshake actually finishes, so
            // "connect succeeded" means "the tunnel can carry bytes", which is what MC assumes
            // when it writes its handshake packet immediately after.
            startDriver(promise);
        }
    }

    /**
     * Picks the address the tunnel should dial.
     *
     * <p>An explicit peer (a configured tunnel server) always wins. Otherwise the peer is whatever
     * Minecraft was asked to connect to, which is what makes "leave tunnelServer blank in the
     * config" mean "tunnel to the address in my server list". Returns null for a loopback or
     * wildcard target, which the caller treats as a hard refusal.
     */
    private InetSocketAddress resolveTunnelTarget(SocketAddress minecraftTarget) {
        if (options.peer() != null) {
            return options.peer();
        }
        if (minecraftTarget instanceof InetSocketAddress inet && !inet.isUnresolved()) {
            java.net.InetAddress address = inet.getAddress();
            if (address != null && (address.isLoopbackAddress() || address.isAnyLocalAddress())) {
                return null;
            }
            return inet;
        }
        return null;
    }
}
