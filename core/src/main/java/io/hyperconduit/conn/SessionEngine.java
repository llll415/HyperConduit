package io.hyperconduit.conn;

import io.hyperconduit.ProtocolException;
import io.hyperconduit.cc.RttStats;
import io.hyperconduit.crypto.AeadCipher;
import io.hyperconduit.crypto.NoiseXx;
import io.hyperconduit.crypto.TransportKeys;
import io.hyperconduit.crypto.X25519;
import io.hyperconduit.frame.Frame;
import io.hyperconduit.frame.FrameCodec;
import io.hyperconduit.frame.PacketCodec;
import io.hyperconduit.util.Clock;
import io.hyperconduit.util.OutBuffer;
import io.hyperconduit.util.VarInt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One tunnel session as a pure state machine: no sockets, no threads, no timers of its own.
 *
 * <p>A driver owns the clock and the network and calls three methods in a loop —
 * {@link #nextDatagram()} to get something to send, {@link #nanosUntilNextEvent(long)} to learn how
 * long it may sleep, and {@link #onDatagramReceived} to feed arrivals. Keeping I/O out of the engine
 * is what makes the reliability logic testable against a deterministic fake clock and a simulated
 * lossy link, and it is also how the Netty channel integration drives it later: Netty becomes the
 * driver instead of a bespoke thread.
 *
 * <p>All public methods are {@code synchronized}. The application thread writes and reads; the
 * driver thread packs, sends, and processes arrivals. Contention is negligible next to the crypto.
 *
 * <p>One session carries exactly one ordered byte stream. Multiple peers are multiple sessions, each
 * with its own pacer, so a lossy player cannot stall the others.
 */
public final class SessionEngine {

    public enum State {
        HANDSHAKE,
        ESTABLISHED,
        CLOSING,
        CLOSED
    }

    /** Application-initiated shutdown. */
    public static final int CLOSE_REASON_LOCAL = 1;
    /** Peer sent a CLOSE frame. */
    public static final int CLOSE_REASON_REMOTE = 2;
    /** Handshake timed out, authentication failed, or a protocol error tore the session down. */
    public static final int CLOSE_REASON_ERROR = 3;

    private static final byte REJECT_BAD_AUTH = 1;
    private static final byte REJECT_UNAVAILABLE = 2;

    private static final long HANDSHAKE_RETRY_INITIAL_NANOS = 250_000_000L;
    private static final long HANDSHAKE_RETRY_MAX_NANOS = 4_000_000_000L;
    /** Stateless and immutable, so one instance can be shared. */
    private static final Frame.Ping PING_FRAME = new Frame.Ping();

    private final SessionConfig config;
    private final SessionConfig.Role role;
    private final int connectionId;
    private final Clock clock;
    private final SessionListener listener;

    private final RttStats rttStats = new RttStats();
    private final SentPacketHandler sentPackets;
    private final ReceivedPacketTracker receivedPackets = new ReceivedPacketTracker();
    private final ReceiveBuffer receiveBuffer;
    private final NoiseXx noise;
    private final PacketCodec handshakeCodec;
    private final OutBuffer sizeProbe = new OutBuffer(64);

    private final ArrayDeque<byte[]> sendQueue = new ArrayDeque<>();
    private final ArrayDeque<Frame> pendingRetransmit = new ArrayDeque<>();
    private final ArrayDeque<byte[]> handshakeOutbound = new ArrayDeque<>();

    private State state = State.HANDSHAKE;
    private PacketCodec sendCodec;
    private PacketCodec recvCodec;
    private int payloadCapacity;

    private long nextPacketNumber;
    private int sendHeadOffset;
    private long queuedBytes;
    private long nextSendOffset;

    /** Highest exclusive stream offset the peer has granted us. */
    private long peerMaxOffset;
    /** Highest exclusive offset we have granted the peer, so we know when to send more credit. */
    private long advertisedMaxOffset;

    private boolean ackPending;
    private long ackDeadlineNanos;

    private boolean peerConfirmed;
    private byte[] retryMessage;
    /** Responder only: message 2, kept so a retransmitted message 1 can be answered idempotently. */
    private byte[] cachedMessage2;
    private long retryAtNanos;
    private long retryDelayNanos = HANDSHAKE_RETRY_INITIAL_NANOS;
    private long handshakeDeadlineNanos;

    private Frame.Close pendingClose;
    private long closeDeadlineNanos;

    /**
     * Responder-side establishment confirmation. The initiator keeps retransmitting message 3 until
     * it sees a transport packet from us, so we must keep saying something until it does; a single
     * PING that gets lost would leave it retrying until its handshake deadline.
     */
    private boolean confirmPingPending;
    private long confirmPingAtNanos;
    private long confirmPingDelayNanos = HANDSHAKE_RETRY_INITIAL_NANOS;

    private long packetsSent;
    private long packetsReceived;
    private long packetsRetransmitted;
    private long bytesSent;
    private long bytesReceived;
    private long bytesDelivered;

    /**
     * Run when the engine acquires work it did not have, so a driver blocked in select/poll wakes up
     * rather than waiting out its timeout. Without this an application write can sit unsent for the
     * whole select timeout: measured at ~500 ms on average with a 1 s timeout, which showed up as
     * absurd round-trip times on a loopback link.
     */
    private volatile Runnable wakeupHook;

    private SessionEngine(SessionConfig config, int connectionId, Clock clock, SessionListener listener) {
        this.config = config;
        this.role = config.role();
        this.connectionId = connectionId;
        this.clock = clock;
        this.listener = listener == null ? new SessionListener() {
        } : listener;
        this.payloadCapacity = config.payloadCapacity();

        this.rttStats.setMaxAckDelayNanos(config.maxAckDelayNanos());
        this.sentPackets = new SentPacketHandler(config.newCongestionController(), rttStats);
        this.sentPackets.setMaxDatagramSize(config.maxDatagramSize());
        this.receiveBuffer = new ReceiveBuffer(config.receiveWindowBytes());
        this.advertisedMaxOffset = config.receiveWindowBytes();
        this.handshakeCodec = PacketCodec.forHandshake(HandshakePayload.maskKey(config.pskRef()));
        this.noise = role == SessionConfig.Role.CLIENT
                ? NoiseXx.initiator(config.staticKey(), config.pskRef())
                : NoiseXx.responder(config.staticKey(), config.pskRef());

        long now = clock.nanoTime();
        this.handshakeDeadlineNanos = now + config.handshakeTimeoutNanos();

        if (role == SessionConfig.Role.CLIENT) {
            try {
                byte[] authenticator = HandshakePayload.authenticator(config.pskRef(), noise.ephemeralPublicKey());
                byte[] payload = HandshakePayload.encodeClientPayload(authenticator, negotiation());
                byte[] message = noise.writeMessage(payload);
                this.retryMessage = handshakeCodec.encode(PacketCodec.FLAG_HANDSHAKE, connectionId, 0,
                        message, 0, message.length);
                // Due immediately; the retry timer only governs the second and later attempts.
                this.retryAtNanos = now;
            } catch (NoiseXx.HandshakeException e) {
                throw new IllegalStateException("cannot start the handshake", e);
            }
        }
    }

    public static SessionEngine client(SessionConfig config, Clock clock, SessionListener listener) {
        if (config.role() != SessionConfig.Role.CLIENT) {
            throw new IllegalArgumentException("config is not a client config");
        }
        return new SessionEngine(config, config.connectionId(), clock, listener);
    }

    /**
     * Builds the responder for an incoming message 1. The caller must already have verified the PSK
     * authenticator; see {@link #verifyInitiator(byte[], byte[])}.
     */
    public static SessionEngine server(SessionConfig config, int connectionId, Clock clock,
                                       SessionListener listener) {
        if (config.role() != SessionConfig.Role.SERVER) {
            throw new IllegalArgumentException("config is not a server config");
        }
        return new SessionEngine(config, connectionId, clock, listener);
    }

    /**
     * Checks message 1's PSK authenticator without constructing a session.
     *
     * <p>This works because in XX the initiator's first message is sent before any key exists, so
     * its payload is plaintext: the layout is {@code ephemeral(32) || authenticator(32) || negotiation}.
     * A server front end should call this before creating an engine, because accepting the handshake
     * costs an X25519 keypair generation plus two DH operations, while this costs one HMAC. Without
     * it, any peer could make the server do that work per packet without knowing the PSK.
     *
     * @param message1 the Noise message, i.e. the datagram body of a {@code FLAG_HANDSHAKE} packet
     * @return true if the sender knows the PSK
     */
    public static boolean verifyInitiator(byte[] psk, byte[] message1) {
        if (message1.length < HandshakePayload.AUTHENTICATOR_LEN + X25519.KEY_LEN) {
            return false;
        }
        byte[] ephemeral = Arrays.copyOfRange(message1, 0, X25519.KEY_LEN);
        byte[] authenticator = Arrays.copyOfRange(message1, X25519.KEY_LEN,
                X25519.KEY_LEN + HandshakePayload.AUTHENTICATOR_LEN);
        return HandshakePayload.verify(psk, ephemeral, authenticator);
    }

    // --- driver interface --------------------------------------------------------

    /** Builds the next datagram to transmit, or {@code null} if there is nothing to send now. */
    public synchronized byte[] nextDatagram() {
        long now = clock.nanoTime();
        if (state == State.CLOSED) {
            return null;
        }
        runDueTimers(now);
        if (state == State.CLOSED) {
            return null; // a timer may have torn the session down
        }
        if (state == State.HANDSHAKE && now >= handshakeDeadlineNanos) {
            fail(new ProtocolException("handshake timed out"));
            return null;
        }
        if (!handshakeOutbound.isEmpty()) {
            return handshakeOutbound.poll();
        }
        if (retryMessage != null && now >= handshakeDeadlineNanos) {
            // Established locally but never confirmed: message 3 kept being lost, or the peer went
            // away. Without this the client would trickle retransmissions forever.
            fail(new ProtocolException("handshake was never confirmed by the peer"));
            return null;
        }
        if (retryMessage != null && now >= retryAtNanos) {
            retryAtNanos = now + retryDelayNanos;
            retryDelayNanos = Math.min(retryDelayNanos * 2, HANDSHAKE_RETRY_MAX_NANOS);
            return retryMessage;
        }
        if (state == State.HANDSHAKE) {
            return null;
        }
        return nextTransportDatagram(now);
    }

    /**
     * How long the driver may sleep before it must call {@link #nextDatagram()} again. Returns
     * {@code 0} when something is already due and {@link Long#MAX_VALUE} when idle.
     */
    public synchronized long nanosUntilNextEvent(long nowNanos) {
        if (state == State.CLOSED) {
            return Long.MAX_VALUE;
        }
        if (!handshakeOutbound.isEmpty()) {
            return 0;
        }
        long wait = Long.MAX_VALUE;
        if (retryMessage != null) {
            wait = Math.min(wait, Math.max(0, retryAtNanos - nowNanos));
        }
        if (state == State.HANDSHAKE) {
            return Math.min(wait, Math.max(0, handshakeDeadlineNanos - nowNanos));
        }
        if (sentPackets.hasAlarm()) {
            wait = Math.min(wait, Math.max(0, sentPackets.alarmNanos() - nowNanos));
        }
        if (ackPending) {
            wait = Math.min(wait, Math.max(0, ackDeadlineNanos - nowNanos));
        }
        if (state == State.CLOSING) {
            wait = Math.min(wait, Math.max(0, closeDeadlineNanos - nowNanos));
        }
        if (role == SessionConfig.Role.SERVER && !peerConfirmed) {
            wait = Math.min(wait, Math.max(0, confirmPingAtNanos - nowNanos));
        }
        if (hasWorkToSend()) {
            wait = Math.min(wait, sentPackets.timeUntilSendNanos(nowNanos));
        }
        return wait;
    }

    /** Feeds one received datagram. Anything that cannot be decoded is dropped silently. */
    public synchronized void onDatagramReceived(byte[] data, int off, int len) {
        if (state == State.CLOSED) {
            return;
        }
        long now = clock.nanoTime();
        PacketCodec.Decoded decoded = tryDecode(data, off, len);
        if (decoded == null || decoded.connectionId() != connectionId) {
            return;
        }
        packetsReceived++;
        bytesReceived += len;

        if (decoded.flags() == PacketCodec.FLAG_REJECT) {
            byte[] body = decoded.plaintext();
            int reason = body.length > 0 ? body[0] & 0xFF : 0;
            // Forgeable only by someone holding the PSK, since the header mask is PSK-derived.
            fail(new ProtocolException("peer rejected the session, reason " + reason));
            return;
        }
        if (decoded.flags() == PacketCodec.FLAG_HANDSHAKE) {
            handleHandshake(decoded, now);
        } else if (state != State.HANDSHAKE) {
            handleTransportPacket(decoded, now);
        }
    }

    // --- application interface ---------------------------------------------------

    /**
     * Installs the driver's wakeup callback. Must be set before the driver thread starts, since a
     * write can arrive at any point afterwards.
     */
    public void setWakeupHook(Runnable hook) {
        this.wakeupHook = hook;
    }

    /**
     * Queues bytes for transmission. The caller should stop writing when {@link #isWritable()} turns
     * false and resume on {@link SessionListener#onWritable()}; writing past the limit is allowed but
     * buffers without bound.
     */
    public synchronized void write(byte[] data, int off, int len) {
        if (state == State.CLOSED) {
            throw new IllegalStateException("session is closed");
        }
        if (len <= 0) {
            return;
        }
        boolean wasIdle = !hasWorkToSend();
        sendQueue.addLast(Arrays.copyOfRange(data, off, off + len));
        queuedBytes += len;
        if (wasIdle) {
            wakeup();
        }
    }

    /** Copies in-order received bytes out of the reassembly buffer. */
    public synchronized int read(byte[] dst, int off, int maxLen) {
        int n = receiveBuffer.read(dst, off, maxLen);
        bytesDelivered += n;
        return n;
    }

    public synchronized int readableBytes() {
        return receiveBuffer.available();
    }

    /** Bytes queued by the application but not yet put on the wire. */
    public synchronized long queuedBytes() {
        return queuedBytes;
    }

    /** False once the send buffer is full; the application should stop writing until it clears. */
    public synchronized boolean isWritable() {
        return state != State.CLOSED && queuedBytes < config.sendBufferBytes();
    }

    public synchronized void close(int reasonCode, String message) {
        if (state == State.CLOSED || state == State.CLOSING) {
            return;
        }
        if (state == State.HANDSHAKE) {
            state = State.CLOSED;
            listener.onClosed(reasonCode, message);
            return;
        }
        state = State.CLOSING;
        pendingClose = new Frame.Close(reasonCode, message == null ? "" : message);
        // Give the CLOSE frame a few round trips to land before abandoning the session.
        closeDeadlineNanos = clock.nanoTime() + 3 * rttStats.ptoNanos(true);
        wakeup();
    }

    private void wakeup() {
        Runnable hook = wakeupHook;
        if (hook != null) {
            hook.run();
        }
    }

    public synchronized State state() {
        return state;
    }

    public int connectionId() {
        return connectionId;
    }

    public synchronized SessionStats stats() {
        return new SessionStats(state, rttStats.smoothedRttNanos(), rttStats.minRttNanos(),
                sentPackets.bytesInFlight(), sentPackets.congestionWindow(), packetsSent, packetsReceived,
                sentPackets.lostPackets(), packetsRetransmitted, bytesSent, bytesReceived, bytesDelivered,
                queuedBytes, sentPackets.ptoCount());
    }

    public synchronized RttStats rttStats() {
        return rttStats;
    }

    // --- handshake ---------------------------------------------------------------

    private void handleHandshake(PacketCodec.Decoded decoded, long now) {
        if (noise.isComplete()) {
            return; // a late retransmission of a message we already processed
        }
        byte[] message = decoded.plaintext();
        if (role == SessionConfig.Role.SERVER) {
            handleResponderHandshake(message, now);
        } else {
            handleInitiatorHandshake(message, now);
        }
    }

    private void handleResponderHandshake(byte[] message, long now) {
        if (noise.messageIndex() == 0) {
            if (!verifyInitiator(config.pskRef(), message)) {
                return; // no PSK: drop without doing any DH work
            }
            try {
                byte[] payload = noise.readMessage(message);
                answerInitiatorMessage1(payload, message);
            } catch (NoiseXx.HandshakeException | ProtocolException e) {
                // Drop silently. Failing here would let any stray datagram tear down a session.
                return;
            }
            return;
        }
        // We are waiting for message 3. Check for a retransmitted message 1 first, and do not feed
        // one to the handshake state machine: it would fail authentication and, more importantly,
        // waste crypto on a message we already know the answer to.
        //
        // A retransmitted message 1 means our message 2 was lost or is still in flight. Answer from
        // the cache rather than restarting the handshake -- restarting would generate a fresh
        // ephemeral key and invalidate the message 3 the initiator is already computing, which
        // deadlocks the session: it completes, we cannot decrypt anything it sends, and it never
        // learns why. Answering from cache is idempotent and keeps one handshake in flight.
        if (cachedMessage2 != null && verifyInitiator(config.pskRef(), message)) {
            handshakeOutbound.add(cachedMessage2);
            return;
        }
        try {
            noise.readMessage(message);
            establish();
        } catch (NoiseXx.HandshakeException e) {
            // Drop silently: a stray or corrupt datagram must not tear down a live session.
        }
    }

    private void handleInitiatorHandshake(byte[] message, long now) {
        byte[] payload;
        try {
            payload = noise.readMessage(message);
        } catch (NoiseXx.HandshakeException e) {
            // A failed parse has already advanced the handshake hash and chaining key past the
            // ephemeral and ee tokens, so retrying with this state cannot succeed no matter how many
            // times the peer repeats message 2. Fail loudly and let the application reconnect rather
            // than retransmitting message 1 into a handshake that can never complete.
            //
            // Reaching here requires a datagram that decoded under the PSK-derived mask key but is
            // not a valid message 2 -- i.e. corruption, since an off-path sender cannot produce one.
            // Recovering from it would mean restarting the handshake on both sides, which needs the
            // responder to tell "message 1 retransmitted" apart from "initiator started over".
            fail(new ProtocolException("handshake message 2 was corrupt or unexpected", e));
            return;
        }
        try {
            applyPeerNegotiation(HandshakePayload.decodeServerPayload(payload));
            byte[] message3 = noise.writeMessage(new byte[0]);
            establish();
            // Keep retransmitting message 3 until the peer proves it arrived, otherwise our first
            // data packets would be dropped by a server that never saw it. Due immediately: backing
            // off before the first attempt would add that whole delay to every connection setup.
            retryMessage = handshakeCodec.encode(PacketCodec.FLAG_HANDSHAKE, connectionId, 0,
                    message3, 0, message3.length);
            retryAtNanos = now;
        } catch (NoiseXx.HandshakeException | ProtocolException e) {
            fail(e);
        }
    }

    /**
     * Answers message 1 with message 2, caching it so a retransmitted message 1 can be answered
     * again without disturbing the handshake in progress.
     *
     * <p>Message 1 carries only the plaintext ephemeral key, so no DH has happened yet at this
     * point: verifying the PSK authenticator here is what keeps an unauthenticated peer from making
     * us perform the two X25519 operations message 2 requires.
     */
    private void answerInitiatorMessage1(byte[] payload, byte[] message)
            throws ProtocolException, NoiseXx.HandshakeException {
        byte[] ephemeral = HandshakePayload.initiatorEphemeralFromMessage1(message);
        byte[] authenticator = Arrays.copyOfRange(payload, 0,
                Math.min(HandshakePayload.AUTHENTICATOR_LEN, payload.length));
        if (!HandshakePayload.verify(config.pskRef(), ephemeral, authenticator)) {
            // Unreachable when the endpoint gates on verifyInitiator first, but the engine must not
            // depend on its caller having done so.
            reject(REJECT_BAD_AUTH, "PSK authenticator mismatch");
            return;
        }
        applyPeerNegotiation(HandshakePayload.decodeClientPayload(payload));
        byte[] message2 = noise.writeMessage(HandshakePayload.encodeServerPayload(negotiation()));
        cachedMessage2 = handshakeCodec.encode(PacketCodec.FLAG_HANDSHAKE, connectionId, 0,
                message2, 0, message2.length);
        handshakeOutbound.add(cachedMessage2);
    }

    private void establish() throws NoiseXx.HandshakeException {
        TransportKeys keys = noise.complete();
        sendCodec = PacketCodec.forTransport(keys.sendKey(), keys.hpKey());
        recvCodec = PacketCodec.forTransport(keys.recvKey(), keys.hpKey());
        state = State.ESTABLISHED;
        if (role == SessionConfig.Role.SERVER) {
            // Due immediately, then on a backoff until the initiator's ACK proves it arrived.
            confirmPingAtNanos = clock.nanoTime();
        }
        listener.onEstablished();
    }

    private void applyPeerNegotiation(HandshakePayload.Negotiation peer) {
        peerMaxOffset = peer.receiveWindowBytes();
        // Neither side may send a datagram larger than the other is prepared to receive.
        int effective = Math.min(config.maxDatagramSize(), peer.maxDatagramSize());
        payloadCapacity = effective - PacketCodec.HEADER_LEN - AeadCipher.TAG_LEN;
        sentPackets.setMaxDatagramSize(effective);
    }

    private HandshakePayload.Negotiation negotiation() {
        return new HandshakePayload.Negotiation(config.receiveWindowBytes(), config.maxDatagramSize());
    }

    private void reject(byte reason, String message) {
        handshakeOutbound.add(handshakeCodec.encode(PacketCodec.FLAG_REJECT, connectionId, 0,
                new byte[]{reason}, 0, 1));
        fail(new ProtocolException(message));
    }

    // --- transport ---------------------------------------------------------------

    private PacketCodec.Decoded tryDecode(byte[] data, int off, int len) {
        if (state != State.HANDSHAKE) {
            try {
                return recvCodec.decode(data, off, len);
            } catch (ProtocolException ignored) {
                // Fall through: a retransmitted handshake message uses the other mask key.
            }
        }
        try {
            return handshakeCodec.decode(data, off, len);
        } catch (ProtocolException ignored) {
            return null;
        }
    }

    private void handleTransportPacket(PacketCodec.Decoded decoded, long now) {
        List<Frame> frames;
        try {
            frames = FrameCodec.decode(decoded.plaintext(), 0, decoded.plaintext().length);
        } catch (ProtocolException e) {
            fail(e);
            return;
        }

        boolean ackEliciting = false;
        int readableBefore = receiveBuffer.available();
        for (Frame frame : frames) {
            switch (frame) {
                case Frame.StreamData data -> {
                    receiveBuffer.put(data.offset(), data.data());
                    ackEliciting = true;
                }
                case Frame.Ack ack -> {
                    try {
                        sentPackets.receivedAck(ack, now);
                    } catch (ProtocolException e) {
                        fail(e);
                        return;
                    }
                }
                case Frame.MaxData maxData -> peerMaxOffset = Math.max(peerMaxOffset, maxData.maxOffset());
                case Frame.Ping ignored -> ackEliciting = true;
                case Frame.Close close -> {
                    state = State.CLOSED;
                    listener.onClosed(close.reasonCode(), close.message());
                    return;
                }
            }
        }

        receivedPackets.receivedPacket(decoded.packetNumber(), ackEliciting, now);
        if (ackEliciting && !ackPending) {
            ackPending = true;
            ackDeadlineNanos = now + config.maxAckDelayNanos();
        }
        // The peer decrypted our data, so message 3 certainly landed. For the client, this is
        // also the first proof that the server completed message 3 before application data begins.
        boolean newlyConfirmed = !peerConfirmed;
        peerConfirmed = true;
        retryMessage = null;
        if (newlyConfirmed) {
            listener.onPeerConfirmed();
        }

        if (receiveBuffer.available() > readableBefore) {
            listener.onReadable();
        }
        if (isWritable()) {
            listener.onWritable();
        }
    }

    private byte[] nextTransportDatagram(long now) {
        if (state == State.CLOSED) {
            return null;
        }
        if (state == State.CLOSING && now >= closeDeadlineNanos) {
            state = State.CLOSED;
            listener.onClosed(pendingClose == null ? CLOSE_REASON_LOCAL : pendingClose.reasonCode(),
                    "close timed out");
            pendingClose = null;
            return null;
        }

        SendMode mode = sentPackets.sendMode(now);
        if (mode == SendMode.NONE) {
            return null;
        }
        if (role == SessionConfig.Role.SERVER && !peerConfirmed && now >= confirmPingAtNanos) {
            confirmPingPending = true;
            confirmPingAtNanos = now + confirmPingDelayNanos;
            confirmPingDelayNanos = Math.min(confirmPingDelayNanos * 2, HANDSHAKE_RETRY_MAX_NANOS);
        }
        boolean maySendData = mode == SendMode.ANY || mode == SendMode.PTO_PROBE;
        if (mode == SendMode.PACING_LIMITED) {
            // Deliberate deviation from quic-go, which paces ACK-only packets too. Holding back an
            // ACK behind the pacer can deadlock progress: the peer's window only reopens when our
            // ACK arrives, and our pacer only refills with time.
            if (!ackDueNow(now)) {
                return null;
            }
            maySendData = false;
        } else if (mode == SendMode.ACK_ONLY) {
            maySendData = false;
        }

        int capacity = payloadCapacity;
        OutBuffer payload = new OutBuffer(capacity);
        List<Frame> frames = new ArrayList<>();
        boolean ackEliciting = false;
        boolean ackIncluded = false;

        // Lost frames go first: the peer is already waiting on them and they are paid for.
        pendingRetransmit.addAll(sentPackets.takeRetransmitQueue());
        while (!pendingRetransmit.isEmpty()) {
            Frame frame = pendingRetransmit.peekFirst();
            if (payload.size() + frameSize(frame) > capacity) {
                break;
            }
            pendingRetransmit.pollFirst();
            FrameCodec.encode(List.of(frame), payload);
            frames.add(frame);
            ackEliciting = true;
            packetsRetransmitted++;
        }

        if (creditUpdateDue()) {
            Frame.MaxData frame = new Frame.MaxData(receiveBuffer.maxAcceptableOffset());
            if (payload.size() + frameSize(frame) <= capacity) {
                FrameCodec.encode(List.of(frame), payload);
                frames.add(frame);
                advertisedMaxOffset = receiveBuffer.maxAcceptableOffset();
                ackEliciting = true;
            }
        }

        // Coalesce the ACK into any outgoing packet, but only force one out when it is actually due.
        if (ackPending && (maySendData || ackDueNow(now))) {
            Frame.Ack ack = receivedPackets.buildAck(now);
            if (ack != null && payload.size() + frameSize(ack) <= capacity) {
                FrameCodec.encode(List.of(ack), payload);
                frames.add(ack);
                ackIncluded = true;
            }
        }

        if (maySendData) {
            while (true) {
                int chunk = computeChunkSize(capacity - payload.size(), nextSendOffset);
                if (chunk <= 0) {
                    break;
                }
                byte[] data = takeStreamChunk(chunk);
                if (data == null) {
                    break;
                }
                Frame.StreamData frame = new Frame.StreamData(nextSendOffset, data);
                FrameCodec.encode(List.of(frame), payload);
                frames.add(frame);
                nextSendOffset += data.length;
                ackEliciting = true;
            }
        }

        if (confirmPingPending && payload.size() + frameSize(PING_FRAME) <= capacity) {
            FrameCodec.encode(List.of(PING_FRAME), payload);
            frames.add(PING_FRAME);
            confirmPingPending = false;
            ackEliciting = true;
        }

        if (mode == SendMode.PTO_PROBE && !ackEliciting) {
            FrameCodec.encode(List.of(PING_FRAME), payload);
            frames.add(PING_FRAME);
            ackEliciting = true;
        }

        if (pendingClose != null) {
            if (!peerConfirmed) {
                // Our side is established but the peer may not have processed message 3 yet, and it
                // discards data packets that arrive before its handshake completes. A CLOSE sent now
                // would vanish and the peer would keep the session forever. The message-3 retransmit
                // timer guarantees confirmation arrives, so wait for it and push the deadline out.
                closeDeadlineNanos = now + 3 * rttStats.ptoNanos(true);
            } else {
                FrameCodec.encode(List.of(pendingClose), payload);
                frames.add(pendingClose);
                pendingClose = null;
                ackEliciting = true;
            }
        }

        if (frames.isEmpty()) {
            return null;
        }

        byte[] plaintext = payload.toByteArray();
        long packetNumber = nextPacketNumber++;
        byte[] datagram = sendCodec.encode(PacketCodec.FLAG_DATA, connectionId, packetNumber,
                plaintext, 0, plaintext.length);
        sentPackets.sentPacket(packetNumber, now, frames, datagram.length, ackEliciting);
        packetsSent++;
        bytesSent += datagram.length;
        if (ackIncluded) {
            ackPending = false;
        }
        return datagram;
    }

    /**
     * Runs the loss-detection and probe timers if they are due.
     *
     * <p>This is what makes {@link #nanosUntilNextEvent(long)} meaningful: the driver sleeps until
     * that deadline and then calls {@link #nextDatagram()}, so the alarm has to be serviced from the
     * send path. Without it, a packet whose ACK never arrives is neither declared lost nor probed,
     * and the session freezes with bytes permanently in flight.
     */
    private void runDueTimers(long now) {
        if (sendCodec == null || !sentPackets.hasAlarm()) {
            return;
        }
        if (now >= sentPackets.alarmNanos()) {
            sentPackets.onLossDetectionTimeout(now);
        }
    }

    private boolean ackDueNow(long now) {
        return ackPending && (receivedPackets.shouldAckImmediately() || now >= ackDeadlineNanos);
    }

    private boolean creditUpdateDue() {
        return receiveBuffer.maxAcceptableOffset() >= advertisedMaxOffset + config.receiveWindowBytes() / 2;
    }

    private boolean hasWorkToSend() {
        return !pendingRetransmit.isEmpty()
                || sentPackets.hasRetransmitPending()
                || (queuedBytes > 0 && peerMaxOffset > nextSendOffset)
                || creditUpdateDue()
                || ackPending
                || confirmPingPending
                || pendingClose != null;
    }

    private int computeChunkSize(int room, long offset) {
        if (queuedBytes <= 0 || room <= 0) {
            return 0;
        }
        long credit = peerMaxOffset - offset;
        if (credit <= 0) {
            return 0;
        }
        int overhead = 1 + VarInt.encodedSize(offset);
        int available = (int) Math.min(Math.min(credit, queuedBytes), (long) room - overhead);
        if (available <= 0) {
            return 0;
        }
        // The length field's own width depends on the value it encodes, so shrink until it fits.
        int chunk = available;
        for (int i = 0; i < 4 && chunk > 0; i++) {
            if (overhead + VarInt.encodedSize(chunk) + chunk <= room) {
                return chunk;
            }
            chunk = room - overhead - VarInt.encodedSize(chunk);
        }
        return 0;
    }

    private byte[] takeStreamChunk(int maxLen) {
        if (sendQueue.isEmpty()) {
            return null;
        }
        byte[] head = sendQueue.peekFirst();
        int available = head.length - sendHeadOffset;
        int n = Math.min(maxLen, available);
        if (n <= 0) {
            return null;
        }
        byte[] chunk = Arrays.copyOfRange(head, sendHeadOffset, sendHeadOffset + n);
        sendHeadOffset += n;
        queuedBytes -= n;
        if (sendHeadOffset >= head.length) {
            sendQueue.pollFirst();
            sendHeadOffset = 0;
        }
        return chunk;
    }

    private int frameSize(Frame frame) {
        sizeProbe.clear();
        FrameCodec.encode(List.of(frame), sizeProbe);
        return sizeProbe.size();
    }

    private void fail(Throwable cause) {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        retryMessage = null;
        listener.onError(cause);
        listener.onClosed(CLOSE_REASON_ERROR, String.valueOf(cause.getMessage()));
    }
}
