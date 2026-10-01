package io.hyperconduit.netty;

import io.hyperconduit.conn.HandshakePacket;
import io.hyperconduit.conn.RetryCookie;
import io.hyperconduit.conn.SessionConfig;
import io.hyperconduit.conn.SessionEngine;
import io.hyperconduit.conn.SessionListener;
import io.hyperconduit.net.DriverWait;
import io.hyperconduit.util.Clock;
import io.netty.channel.AbstractServerChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Netty {@link AbstractServerChannel} that "listens" on a HyperConduit UDP tunnel instead of a
 * kernel TCP socket.
 *
 * <p>From Minecraft's point of view this behaves like a server socket channel: bind to an address,
 * and accept child channels. Underneath, the child channels are not NioSocketChannels produced by
 * a kernel accept() — they are {@link HyperConduitChannel} instances whose backing transport is a
 * {@link SessionEngine} tunnel session. The Minecraft {@code childHandler} therefore sees the
 * exact same Netty channel shape it expects, and its pipeline initialisation works unchanged.
 *
 * <p>The server channel owns one driver thread that multiplexes the UDP socket for all sessions
 * (same shape as {@code UdpServerEndpoint}), because the tunnel's clock-driven timers and pacing
 * have to run regardless of whether Minecraft's Netty event loop is doing anything.
 */
public final class HyperConduitServerChannel extends AbstractServerChannel {

    /** How the server channel should create its sessions. */
    public record ServerOptions(
            InetSocketAddress bindAddress,
            SessionConfig sessionConfig,
            Clock clock) {

        public ServerOptions(InetSocketAddress bindAddress, SessionConfig sessionConfig) {
            this(bindAddress, sessionConfig, Clock.SYSTEM);
        }
    }

    private static final int READ_BUFFER_SIZE = 64 * 1024;
    private static final int SOCKET_BUFFER_BYTES = 4 * 1024 * 1024;
    private static final Set<HyperConduitServerChannel> ACTIVE_LISTENERS = ConcurrentHashMap.newKeySet();

    private final ServerOptions options;
    private final ChannelConfig config = new DefaultChannelConfig(this);

    private volatile DatagramChannel datagramChannel;
    private volatile Selector selector;
    private volatile boolean open;
    private volatile boolean running;
    private volatile boolean accepting = true;
    private volatile long gracefulShutdownDeadlineNanos = Long.MAX_VALUE;
    private volatile Thread driverThread;
    private volatile SocketAddress localAddress;

    /** Active tunnel sessions, keyed by connection id. */
    private final Map<Integer, Session> sessions = new HashMap<>();
    private final RetryCookie retryCookie = new RetryCookie();

    public HyperConduitServerChannel(ServerOptions options) {
        super();
        this.options = options;
        this.localAddress = options.bindAddress();
        this.open = true;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isActive() {
        return open && running;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new HyperConduitServerUnsafe();
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    protected SocketAddress localAddress0() {
        return localAddress;
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        DatagramChannel channel = DatagramChannel.open();
        Selector newSelector = null;
        try {
            channel.configureBlocking(false);
            io.hyperconduit.net.SocketBuffers.apply(channel, SOCKET_BUFFER_BYTES, "hyperconduit-server");
            channel.bind(localAddress);
            newSelector = Selector.open();
            channel.register(newSelector, SelectionKey.OP_READ);
            datagramChannel = channel;
            selector = newSelector;
            this.localAddress = channel.getLocalAddress();
            running = true;
            ACTIVE_LISTENERS.add(this);
            driverThread = new Thread(this::runDriver, "hyperconduit-server-driver");
            driverThread.setDaemon(true);
            driverThread.start();
        } catch (Throwable t) {
            if (newSelector != null) {
                newSelector.close();
            }
            channel.close();
            throw t;
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        beginGracefulShutdown();
        if (driverThread != null && driverThread != Thread.currentThread()) {
            driverThread.join(2200);
        }
        forceCloseResources();
    }

    /** Requests a bounded CLOSE-frame drain from every active HyperConduit listener. */
    public static void beginGracefulShutdownAll() {
        for (HyperConduitServerChannel listener : ACTIVE_LISTENERS) {
            listener.beginGracefulShutdown();
        }
    }

    /** Requests a bounded CLOSE-frame drain from the UDP driver before resources are released. */
    public void beginGracefulShutdown() {
        accepting = false;
        gracefulShutdownDeadlineNanos = options.clock().nanoTime() + 1_500_000_000L;
        if (selector != null) {
            selector.wakeup();
        }
    }

    private void forceCloseResources() {
        open = false;
        running = false;
        ACTIVE_LISTENERS.remove(this);
        sessions.clear();
        try {
            if (selector != null) selector.close();
        } catch (IOException ignored) {
        }
        try {
            if (datagramChannel != null) datagramChannel.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    protected void doBeginRead() {
        // Reads are driven by the tunnel, not by Netty's select loop.
    }

    @Override
    protected void doWrite(io.netty.channel.ChannelOutboundBuffer out) throws Exception {
        // A server channel has no meaningful outbound writes of its own; child channels do their own.
        out.remove();
    }

    @Override
    public io.netty.channel.ChannelMetadata metadata() {
        return new io.netty.channel.ChannelMetadata(false);
    }

    private void runDriver() {
        try {
            ByteBuffer readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE);
            while (running && open) {
                long now = options.clock().nanoTime();
                long waitNanos = nextWaitNanos(now);
                DriverWait.await(selector, waitNanos);
                selector.selectedKeys().clear();
                receiveDatagrams(readBuffer);
                if (!accepting) {
                    for (Session session : sessions.values()) {
                        session.engine.close(SessionEngine.CLOSE_REASON_SERVER_SHUTDOWN,
                                "[HyperConduit] Server is shutting down");
                    }
                }
                transmitAll();
                evictClosed();
                if (!accepting && (sessions.isEmpty() || options.clock().nanoTime() >= gracefulShutdownDeadlineNanos)) {
                    running = false;
                }
            }
            forceCloseResources();
        } catch (Throwable t) {
            pipeline().fireExceptionCaught(t);
        }
    }

    private long nextWaitNanos(long now) {
        long wait = Long.MAX_VALUE;
        for (Session session : sessions.values()) {
            wait = Math.min(wait, session.engine.nanosUntilNextEvent(now));
        }
        return wait;
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
            dispatch(datagram, from);
        }
    }

    private void dispatch(byte[] datagram, SocketAddress from) {
        if (HandshakePacket.isHandshake(datagram, 0, datagram.length)) {
            acceptHandshake(datagram, from);
            return;
        }
        if (datagram.length < io.hyperconduit.frame.PacketCodec.HEADER_LEN) {
            return;
        }
        Session existing = sessions.get(io.hyperconduit.frame.PacketCodec.peekConnectionId(datagram, 0, datagram.length));
        if (existing != null) {
            existing.engine.onDatagramReceived(datagram, 0, datagram.length);
        }
    }

    private void acceptHandshake(byte[] datagram, SocketAddress from) {
        HandshakePacket.Decoded packet;
        try {
            packet = HandshakePacket.decode(datagram, 0, datagram.length);
        } catch (io.hyperconduit.ProtocolException e) {
            return;
        }
        Session existing = sessions.get(packet.connectionId());
        if (existing != null) {
            existing.engine.onDatagramReceived(datagram, 0, datagram.length);
            return;
        }
        if (!accepting || packet.type() != HandshakePacket.TYPE_INITIAL) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!retryCookie.verify(from, packet.connectionId(), packet.cookie(), now)) {
            sendHandshake(HandshakePacket.retry(packet.connectionId(),
                    retryCookie.mint(from, packet.connectionId(), now + 15_000L)), from);
            return;
        }
        try {
            Session session = new Session(packet.connectionId(), from);
            session.engine.acceptInitiatorMessage1(packet.payload());
            sessions.put(packet.connectionId(), session);
        } catch (io.hyperconduit.ProtocolException | io.hyperconduit.crypto.NoiseXx.HandshakeException ignored) {
            // Invalid Noise message 1 does not retain state.
        }
    }

    private void sendHandshake(byte[] datagram, SocketAddress peer) {
        try {
            datagramChannel.send(ByteBuffer.wrap(datagram), peer);
        } catch (IOException ignored) {
            // Retry is best effort; the client will retransmit its Initial.
        }
    }

    private void transmitAll() throws IOException {
        for (Session session : sessions.values()) {
            while (true) {
                byte[] datagram = session.engine.nextDatagram();
                if (datagram == null) {
                    break;
                }
                datagramChannel.send(ByteBuffer.wrap(datagram), session.peer);
            }
        }
    }

    private void evictClosed() {
        for (Iterator<Map.Entry<Integer, Session>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Session> entry = it.next();
            if (entry.getValue().engine.state() == SessionEngine.State.CLOSED) {
                it.remove();
            }
        }
    }

    /** One tunnel session on the server side: the engine plus the child channel it produced. */
    private final class Session {

        final SocketAddress peer;
        final SessionEngine engine;
        volatile Channel childChannel;

        Session(int connectionId, SocketAddress peer) {
            this.peer = peer;
            this.engine = SessionEngine.server(options.sessionConfig(), connectionId, options.clock(),
                    new SessionListener() {
                        @Override
                        public void onEstablished() {
                            // The tunnel handshake is done: this is the server's "accept" event.
                            // Produce a child channel and hand it to Minecraft's childHandler.
                            fireChildChannel();
                        }

                        @Override
                        public void onReadable() {
                            if (childChannel != null) {
                                ((HyperConduitServerChildChannel) childChannel).fireReadable();
                            }
                        }

                        @Override
                        public void onWritable() {
                            if (childChannel != null) {
                                childChannel.pipeline().fireChannelWritabilityChanged();
                            }
                        }

                        @Override
                        public void onClosed(int reasonCode, String message) {
                            if (childChannel != null) {
                                childChannel.pipeline().fireChannelInactive();
                                childChannel.close();
                            }
                        }

                        @Override
                        public void onError(Throwable cause) {
                            if (childChannel != null) {
                                childChannel.pipeline().fireExceptionCaught(cause);
                                childChannel.close();
                            }
                        }
                    });
            engine.setWakeupHook(selector::wakeup);
        }

        private void fireChildChannel() {
            // Create the child channel Minecraft's childHandler expects. It is NOT a SocketChannel
            // and it does not connect or drive I/O itself — it is the Netty view of this accepted
            // session, and the server channel's single driver feeds it.
            HyperConduitServerChildChannel child = new HyperConduitServerChildChannel(engine, peer);
            childChannel = child;
            // Hand it up to Netty's ServerBootstrapAcceptor by firing it as a read event on the
            // server channel's pipeline. The acceptor (which Netty installed when Minecraft called
            // ServerBootstrap.childHandler(...)) will call Minecraft's childHandler.initChannel(child)
            // and register the child to the child event loop — exactly as if it had been accept()ed
            // from a kernel socket.
            EventLoop loop = eventLoop();
            if (loop != null) {
                loop.execute(() -> {
                    try {
                        pipeline().fireChannelRead(child);
                        pipeline().fireChannelReadComplete();
                    } catch (Throwable t) {
                        pipeline().fireExceptionCaught(t);
                        child.close();
                    }
                });
            }
        }
    }

    private final class HyperConduitServerUnsafe extends AbstractUnsafe {
        @Override
        public void connect(SocketAddress remoteAddress, SocketAddress localAddress,
                            ChannelPromise promise) {
            promise.setFailure(new UnsupportedOperationException(
                    "a server channel does not connect"));
        }
    }
}
