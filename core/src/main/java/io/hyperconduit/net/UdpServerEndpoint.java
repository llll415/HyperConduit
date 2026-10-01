package io.hyperconduit.net;

import io.hyperconduit.ProtocolException;
import io.hyperconduit.conn.HandshakePacket;
import io.hyperconduit.conn.RetryCookie;
import io.hyperconduit.conn.SessionConfig;
import io.hyperconduit.conn.SessionEngine;
import io.hyperconduit.conn.SessionListener;
import io.hyperconduit.frame.PacketCodec;
import io.hyperconduit.util.Clock;

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

/**
 * Server front end: one datagram channel and one event-loop thread serving many sessions.
 *
 * <p>Datagrams are routed by the connection id, which is the reason that field is left in the clear
 * in the packet header — the endpoint cannot know a session's keys before it knows which session a
 * datagram belongs to.
 *
 * <p><b>Unauthenticated work is bounded.</b> For an unknown connection id, the endpoint only accepts
 * a handshake message 1 whose PSK authenticator checks out. That check is one HMAC over the
 * datagram; everything after it (an X25519 keypair and two DH operations per session) happens only
 * for a peer that has already proven it holds the PSK. Without this gate any host could make the
 * server do public-key work per packet.
 */
public final class UdpServerEndpoint implements AutoCloseable {

    private static final byte REJECT_UNAVAILABLE = 2;

    /** See {@link SocketBuffers}: Brutal's bursts overflow the OS default well below the target rate. */
    private static final int SOCKET_BUFFER_BYTES = 4 * 1024 * 1024;

    /** Notified of session lifecycle events. All callbacks run on the endpoint's event-loop thread. */
    public interface SessionHandler {

        void onSessionEstablished(SessionEngine session, SocketAddress peerAddress);

        /** In-order bytes are waiting; call {@link SessionEngine#read}. */
        default void onSessionReadable(SessionEngine session) {
        }

        /** The send buffer drained below its limit; the application may write again. */
        default void onSessionWritable(SessionEngine session) {
        }

        default void onSessionClosed(SessionEngine session, int reasonCode, String message) {
        }

        default void onSessionError(SessionEngine session, Throwable cause) {
        }
    }

    private final DatagramChannel channel;
    private final Selector selector;
    /** Read-only after construction: every session shares it, including the server's static key. */
    private final SessionConfig template;
    private final SessionHandler handler;
    private final Clock clock;
    private final RetryCookie retryCookie = new RetryCookie();
    private final Map<Integer, Session> sessions = new HashMap<>();
    private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(64 * 1024);

    private final int maxSessions;
    private volatile boolean running = true;
    private Thread thread;

    public UdpServerEndpoint(InetSocketAddress bind, SessionConfig template, SessionHandler handler)
            throws IOException {
        this(bind, template, handler, Clock.SYSTEM, 1024);
    }

    public UdpServerEndpoint(InetSocketAddress bind, SessionConfig template, SessionHandler handler,
                             Clock clock, int maxSessions) throws IOException {
        if (template.role() != SessionConfig.Role.SERVER) {
            throw new IllegalArgumentException("template config must have the SERVER role");
        }
        this.template = template;
        this.handler = handler;
        this.clock = clock;
        this.maxSessions = maxSessions;
        this.channel = DatagramChannel.open();
        this.channel.configureBlocking(false);
        SocketBuffers.apply(this.channel, SOCKET_BUFFER_BYTES, "server");
        this.channel.bind(bind);
        this.selector = Selector.open();
        this.channel.register(selector, SelectionKey.OP_READ);
    }

    /** The bound address, useful when binding to an ephemeral port. */
    public SocketAddress localAddress() throws IOException {
        return channel.getLocalAddress();
    }

    public int sessionCount() {
        return sessions.size();
    }

    public void start() {
        if (thread != null) {
            throw new IllegalStateException("already started");
        }
        thread = new Thread(this::loop, "hyperconduit-server");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        running = false;
        selector.wakeup();
        try {
            if (thread != null) {
                thread.join(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            selector.close();
        } catch (IOException ignored) {
            // nothing useful to do while tearing down
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // nothing useful to do while tearing down
        }
    }

    private void loop() {
        try {
            while (running) {
                DriverWait.await(selector, selectWaitNanos(clock.nanoTime()));
                selector.selectedKeys().clear();
                receive();
                transmitAll();
                evictClosed();
            }
        } catch (IOException e) {
            if (running) {
                throw new IllegalStateException("server event loop failed", e);
            }
        }
    }

    private long selectWaitNanos(long now) {
        long wait = Long.MAX_VALUE;
        for (Session session : sessions.values()) {
            wait = Math.min(wait, session.engine.nanosUntilNextEvent(now));
        }
        return wait;
    }

    private void receive() throws IOException {
        while (true) {
            readBuffer.clear();
            SocketAddress from = channel.receive(readBuffer);
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
        if (datagram.length < PacketCodec.HEADER_LEN) {
            return;
        }
        Session existing = sessions.get(PacketCodec.peekConnectionId(datagram, 0, datagram.length));
        if (existing != null) {
            existing.engine.onDatagramReceived(datagram, 0, datagram.length);
        }
    }

    private void acceptHandshake(byte[] datagram, SocketAddress from) {
        HandshakePacket.Decoded packet;
        try {
            packet = HandshakePacket.decode(datagram, 0, datagram.length);
        } catch (ProtocolException e) {
            return;
        }
        Session existing = sessions.get(packet.connectionId());
        if (existing != null) {
            existing.engine.onDatagramReceived(datagram, 0, datagram.length);
            return;
        }
        if (packet.type() != HandshakePacket.TYPE_INITIAL) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!retryCookie.verify(from, packet.connectionId(), packet.cookie(), now)) {
            send(HandshakePacket.retry(packet.connectionId(), retryCookie.mint(from, packet.connectionId(), now + 15_000L)), from);
            return;
        }
        if (sessions.size() >= maxSessions) {
            send(HandshakePacket.reject(packet.connectionId(), REJECT_UNAVAILABLE), from);
            return;
        }
        try {
            Session session = new Session(packet.connectionId(), from);
            session.engine.acceptInitiatorMessage1(packet.payload());
            sessions.put(packet.connectionId(), session);
        } catch (ProtocolException | io.hyperconduit.crypto.NoiseXx.HandshakeException ignored) {
            // Invalid Noise message 1 does not retain any server state.
        }
    }

    private void transmitAll() throws IOException {
        for (Session session : sessions.values()) {
            while (true) {
                byte[] datagram = session.engine.nextDatagram();
                if (datagram == null) {
                    break;
                }
                channel.send(ByteBuffer.wrap(datagram), session.peer);
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

    private void send(byte[] datagram, SocketAddress peer) {
        try {
            channel.send(ByteBuffer.wrap(datagram), peer);
        } catch (IOException ignored) {
            // Retry and rejection packets are best effort.
        }
    }

    private final class Session {

        final SocketAddress peer;
        final SessionEngine engine;

        Session(int connectionId, SocketAddress peer) {
            this.peer = peer;
            this.engine = SessionEngine.server(template, connectionId, clock, new SessionListener() {
                @Override
                public void onEstablished() {
                    handler.onSessionEstablished(engine, peer);
                }

                @Override
                public void onReadable() {
                    handler.onSessionReadable(engine);
                }

                @Override
                public void onWritable() {
                    handler.onSessionWritable(engine);
                }

                @Override
                public void onClosed(int reasonCode, String message) {
                    handler.onSessionClosed(engine, reasonCode, message);
                }

                @Override
                public void onError(Throwable cause) {
                    handler.onSessionError(engine, cause);
                }
            });
            // An application write from another thread must break the event loop out of select.
            this.engine.setWakeupHook(selector::wakeup);
        }
    }
}
