package io.hyperconduit.conn;

import io.hyperconduit.cc.BrutalCc;
import io.hyperconduit.cc.CongestionController;
import io.hyperconduit.cc.Constants;
import io.hyperconduit.cc.RenoCc;
import io.hyperconduit.crypto.X25519;

import java.security.KeyPair;
import java.util.function.Supplier;

/** Immutable-after-construction session settings. */
public final class SessionConfig {

    public enum Role { CLIENT, SERVER }

    private final Role role;
    private KeyPair staticKey = X25519.generate();
    private PeerIdentityVerifier peerIdentityVerifier = PeerIdentityVerifier.ACCEPT_ANY;
    private int connectionId;
    private Supplier<CongestionController> congestionControllerFactory;
    private int maxDatagramSize = Constants.INITIAL_PACKET_SIZE;
    private int receiveWindowBytes = 256 * 1024;
    private int sendBufferBytes = 256 * 1024;
    private long maxAckDelayNanos = Constants.MAX_ACK_DELAY_NANOS;
    private long handshakeTimeoutNanos = 15_000_000_000L;

    private SessionConfig(Role role) {
        this.role = role;
        this.congestionControllerFactory = brutal(100_000_000L / 8);
    }

    public static SessionConfig client() { return new SessionConfig(Role.CLIENT); }
    public static SessionConfig server() { return new SessionConfig(Role.SERVER); }

    /** Temporary source compatibility for callers compiled against protocol v1; the value is ignored. */
    @Deprecated public static SessionConfig client(byte[] ignoredPsk) { return client(); }
    /** Temporary source compatibility for callers compiled against protocol v1; the value is ignored. */
    @Deprecated public static SessionConfig server(byte[] ignoredPsk) { return server(); }

    public static Supplier<CongestionController> brutal(long bps) { return brutal(bps, false); }
    public static Supplier<CongestionController> brutal(long bps, boolean disableLossCompensation) {
        return () -> new BrutalCc(bps, disableLossCompensation);
    }
    public static Supplier<CongestionController> reno() { return RenoCc::new; }

    public SessionConfig staticKey(KeyPair staticKey) { this.staticKey = staticKey; return this; }
    public SessionConfig peerIdentityVerifier(PeerIdentityVerifier verifier) {
        this.peerIdentityVerifier = verifier == null ? PeerIdentityVerifier.ACCEPT_ANY : verifier;
        return this;
    }
    public SessionConfig connectionId(int connectionId) { this.connectionId = connectionId; return this; }
    public SessionConfig congestionController(Supplier<CongestionController> factory) { this.congestionControllerFactory = factory; return this; }
    public SessionConfig maxDatagramSize(int size) { this.maxDatagramSize = size; return this; }
    public SessionConfig receiveWindowBytes(int bytes) { this.receiveWindowBytes = bytes; return this; }
    public SessionConfig sendBufferBytes(int bytes) { this.sendBufferBytes = bytes; return this; }
    public SessionConfig maxAckDelayNanos(long nanos) { this.maxAckDelayNanos = nanos; return this; }
    public SessionConfig handshakeTimeoutNanos(long nanos) { this.handshakeTimeoutNanos = nanos; return this; }

    public Role role() { return role; }
    public KeyPair staticKey() { return staticKey; }
    public PeerIdentityVerifier peerIdentityVerifier() { return peerIdentityVerifier; }
    public int connectionId() { return connectionId; }
    public CongestionController newCongestionController() { return congestionControllerFactory.get(); }
    public int maxDatagramSize() { return maxDatagramSize; }
    public int payloadCapacity() { return maxDatagramSize - io.hyperconduit.frame.PacketCodec.HEADER_LEN - io.hyperconduit.crypto.AeadCipher.TAG_LEN; }
    public int receiveWindowBytes() { return receiveWindowBytes; }
    public int sendBufferBytes() { return sendBufferBytes; }
    public long maxAckDelayNanos() { return maxAckDelayNanos; }
    public long handshakeTimeoutNanos() { return handshakeTimeoutNanos; }
}
