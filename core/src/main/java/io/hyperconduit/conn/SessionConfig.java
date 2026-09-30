package io.hyperconduit.conn;

import io.hyperconduit.cc.BrutalCc;
import io.hyperconduit.cc.CongestionController;
import io.hyperconduit.cc.Constants;
import io.hyperconduit.cc.RenoCc;
import io.hyperconduit.crypto.X25519;

import java.security.KeyPair;
import java.util.function.Supplier;

/**
 * Session configuration. Constructed once at startup; not thread-safe, and not meant to be changed
 * after the engine is built.
 */
public final class SessionConfig {

    public enum Role {
        CLIENT,
        SERVER
    }

    private final Role role;
    private final byte[] psk;

    private KeyPair staticKey = X25519.generate();
    private int connectionId;
    private Supplier<CongestionController> congestionControllerFactory;
    private int maxDatagramSize = Constants.INITIAL_PACKET_SIZE;
    private int receiveWindowBytes = 256 * 1024;
    private int sendBufferBytes = 256 * 1024;
    private long maxAckDelayNanos = Constants.MAX_ACK_DELAY_NANOS;
    private long handshakeTimeoutNanos = 15_000_000_000L;

    private SessionConfig(Role role, byte[] psk) {
        this.role = role;
        this.psk = psk == null ? new byte[0] : psk.clone();
        // Brutal by default: it is the reason this transport exists. A 100 Mbit/s target is a
        // deliberately conservative starting point; real deployments set it from the subscribed
        // bandwidth of the receiving side.
        this.congestionControllerFactory = brutal(100_000_000L / 8);
    }

    public static SessionConfig client(byte[] psk) {
        return new SessionConfig(Role.CLIENT, psk);
    }

    public static SessionConfig server(byte[] psk) {
        return new SessionConfig(Role.SERVER, psk);
    }

    /** Brutal targeting {@code bps} bytes/second with loss compensation enabled. */
    public static Supplier<CongestionController> brutal(long bps) {
        return brutal(bps, false);
    }

    public static Supplier<CongestionController> brutal(long bps, boolean disableLossCompensation) {
        return () -> new BrutalCc(bps, disableLossCompensation);
    }

    /** Unpaced AIMD, for A/B measurement against Brutal on the same link. */
    public static Supplier<CongestionController> reno() {
        return RenoCc::new;
    }

    public SessionConfig staticKey(KeyPair staticKey) {
        this.staticKey = staticKey;
        return this;
    }

    /** Client-side only; the server learns the id from the first datagram. */
    public SessionConfig connectionId(int connectionId) {
        this.connectionId = connectionId;
        return this;
    }

    public SessionConfig congestionController(Supplier<CongestionController> factory) {
        this.congestionControllerFactory = factory;
        return this;
    }

    public SessionConfig maxDatagramSize(int maxDatagramSize) {
        this.maxDatagramSize = maxDatagramSize;
        return this;
    }

    public SessionConfig receiveWindowBytes(int receiveWindowBytes) {
        this.receiveWindowBytes = receiveWindowBytes;
        return this;
    }

    /**
     * High-water mark for application data queued but not yet sent. {@link SessionEngine#isWritable()}
     * turns false above it, which is how the tunnel applies backpressure to Minecraft's channel
     * instead of buffering without bound.
     */
    public SessionConfig sendBufferBytes(int sendBufferBytes) {
        this.sendBufferBytes = sendBufferBytes;
        return this;
    }

    public SessionConfig maxAckDelayNanos(long maxAckDelayNanos) {
        this.maxAckDelayNanos = maxAckDelayNanos;
        return this;
    }

    public SessionConfig handshakeTimeoutNanos(long handshakeTimeoutNanos) {
        this.handshakeTimeoutNanos = handshakeTimeoutNanos;
        return this;
    }

    public Role role() {
        return role;
    }

    public byte[] psk() {
        return psk.clone();
    }

    byte[] pskRef() {
        return psk;
    }

    public KeyPair staticKey() {
        return staticKey;
    }

    public int connectionId() {
        return connectionId;
    }

    public CongestionController newCongestionController() {
        return congestionControllerFactory.get();
    }

    public int maxDatagramSize() {
        return maxDatagramSize;
    }

    /** Bytes usable for frames after the header and AEAD tag. */
    public int payloadCapacity() {
        return maxDatagramSize
                - io.hyperconduit.frame.PacketCodec.HEADER_LEN
                - io.hyperconduit.crypto.AeadCipher.TAG_LEN;
    }

    public int receiveWindowBytes() {
        return receiveWindowBytes;
    }

    public int sendBufferBytes() {
        return sendBufferBytes;
    }

    public long maxAckDelayNanos() {
        return maxAckDelayNanos;
    }

    public long handshakeTimeoutNanos() {
        return handshakeTimeoutNanos;
    }
}
