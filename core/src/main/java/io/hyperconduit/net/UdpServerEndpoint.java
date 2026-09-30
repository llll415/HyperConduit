package io.hyperconduit.net;

import io.hyperconduit.ProtocolException;
import io.hyperconduit.conn.HandshakePayload;
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
    /**
     * Cached at construction. {@link SessionConfig#psk()} clones, and this is consulted once per
     * inbound datagram from an unauthenticated peer, so cloning per packet would hand an attacker a
     * cheap allocation amplifier.
     */
    private final byte[] psk;
    private final SessionHandler handler;
    private final Clock clock;
    private final PacketCodec handshakeCodec;
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
        this.psk = template.psk();
        this.handshakeCodec = PacketCodec.forHandshake(HandshakePayload.maskKey(psk));
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
        if (datagram.length < PacketCodec.HEADER_LEN) {
            return;
        }
        int connectionId = PacketCodec.peekConnectionId(datagram, 0, datagram.length);
        Session existing = sessions.get(connectionId);
        if (existing != null) {
            existing.engine.onDatagramReceived(datagram, 0, datagram.length);
            return;
        }
        acceptNewSession(datagram, connectionId, from);
    }

    private void acceptNewSession(byte[] datagram, int connectionId, SocketAddress from) {
        PacketCodec.Decoded decoded;
        try {
            decoded = handshakeCodec.decode(datagram, 0, datagram.length);
        } catch (ProtocolException e) {
            return; // not ours, or not decodable with the PSK-derived mask key: drop silently
        }
        if (decoded.flags() != PacketCodec.FLAG_HANDSHAKE) {
            return; // data for a session that does not exist
        }
        // One HMAC before any session state is allocated.
        if (!SessionEngine.verifyInitiator(psk, decoded.plaintext())) {
            return;
        }
        if (sessions.size() >= maxSessions) {
            // Telling a valid peer we are full is worth the packet; telling an invalid one is not,
            // which is why this sits after the authenticator check.
            sendReject(connectionId, from, REJECT_UNAVAILABLE);
            return;
        }
        Session session = new Session(connectionId, from);
        sessions.put(connectionId, session);
        session.engine.onDatagramReceived(datagram, 0, datagram.length);
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

    /** Exposed for tests and for a session cap; {@link #REJECT_UNAVAILABLE} is the reason code. */
    private void sendReject(int connectionId, SocketAddress peer, byte reason) {
        try {
            byte[] datagram = handshakeCodec.encode(PacketCodec.FLAG_REJECT, connectionId, 0,
                    new byte[]{reason}, 0, 1);
            channel.send(ByteBuffer.wrap(datagram), peer);
        } catch (IOException ignored) {
            // a rejection is best-effort; the peer will time out regardless
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
