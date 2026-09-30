package io.hyperconduit.frame;

import io.hyperconduit.ProtocolException;
import io.hyperconduit.crypto.AeadCipher;

import javax.crypto.Mac;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Datagram-level encode/decode: a 9-byte header (flags, connection id, packet number) plus a body.
 *
 * <p><b>Header masking.</b> The flags byte and packet number are XORed with
 * {@code HMAC-SHA256(maskKey, body)[0..5]}, so the two fields that actually form a fingerprint — a
 * fixed flags value and a monotonically increasing counter — look uniformly random to an observer.
 * This matters more than usual here: the traffic is destined for a link where packets are dropped
 * based on how well they can be classified.
 *
 * <p><b>The connection id is deliberately left in the clear</b> (as in QUIC). The server demultiplexes
 * datagrams by id before it can know which session's keys to use, and the mask key differs between
 * the handshake and transport phases, so a masked id could not be read at all. A per-session random
 * 32-bit id is not a useful fingerprint: it is constant for the life of the session, and the UDP
 * 4-tuple already correlates the flow.
 *
 * <p><b>Why the AAD is the unprotected header.</b> QUIC protects the header with a mask derived
 * from the ciphertext and feeds the protected header to the AEAD, which forces a specific ordering.
 * Nothing here needs RFC 9000 compatibility, so the AAD is the plaintext header and the mask is
 * derived from the body. That breaks the circular dependency while still binding flags, connection
 * id and packet number into the authentication tag: flipping any of them fails the AEAD check.
 *
 * <p>Instances are not thread-safe (they reuse a {@link Mac} and scratch buffers). A session owns
 * one codec per direction and serialises access.
 */
public final class PacketCodec {

    /** connection id (4, clear) + flags (1) + packet number (4), the latter two masked. */
    public static final int HEADER_LEN = 9;
    public static final int CONNECTION_ID_LEN = 4;
    /** Offset of the masked portion of the header. */
    public static final int MASKED_OFFSET = CONNECTION_ID_LEN;
    private static final int MASKED_LEN = HEADER_LEN - MASKED_OFFSET;

    /** Carries a Noise handshake message; body is plaintext, masked with the PSK-derived key. */
    public static final int FLAG_HANDSHAKE = 0x01;
    /** Carries transport frames; body is AEAD ciphertext. */
    public static final int FLAG_DATA = 0x10;
    /** Server refuses the session. Body is a single plaintext reason byte. */
    public static final int FLAG_REJECT = 0x20;

    private static final int NONCE_PREFIX_ZEROES = AeadCipher.NONCE_LEN - Integer.BYTES;

    /** Result of a successful decode. {@code plaintext} is exactly the frame bytes, no padding. */
    public record Decoded(int flags, int connectionId, long packetNumber, byte[] plaintext) {
    }

    private final AeadCipher aead;
    private final Mac hpMac;
    private final byte[] maskBuf = new byte[32];
    private final byte[] nonceBuf = new byte[AeadCipher.NONCE_LEN];
    private final byte[] headerBuf = new byte[HEADER_LEN];

    private PacketCodec(AeadCipher aead, byte[] maskKey) {
        this.aead = aead;
        try {
            this.hpMac = Mac.getInstance("HmacSHA256");
            this.hpMac.init(new SecretKeySpec(maskKey, "HmacSHA256"));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * Codec for the handshake phase: no AEAD (the Noise messages carry their own), header masking
     * keyed from the PSK so that even the first datagrams are unrecognisable without it.
     */
    public static PacketCodec forHandshake(byte[] pskMaskKey) {
        return new PacketCodec(null, pskMaskKey);
    }

    /** Codec for established sessions. {@code transportKey} is this direction's send or recv key. */
    public static PacketCodec forTransport(byte[] transportKey, byte[] maskKey) {
        return new PacketCodec(new AeadCipher(transportKey), maskKey);
    }

    public boolean encryptsBody() {
        return aead != null;
    }

    /** Reads the connection id without any key, for server-side demultiplexing. */
    public static int peekConnectionId(byte[] datagram, int off, int len) {
        if (len < HEADER_LEN) {
            throw new IllegalArgumentException("datagram shorter than the header: " + len);
        }
        return ((datagram[off] & 0xFF) << 24)
                | ((datagram[off + 1] & 0xFF) << 16)
                | ((datagram[off + 2] & 0xFF) << 8)
                | (datagram[off + 3] & 0xFF);
    }

    public byte[] encode(int flags, int connectionId, long packetNumber, byte[] plaintext, int off, int len) {
        writeHeader(flags, connectionId, packetNumber);

        byte[] out;
        int bodyLen;
        if (aead == null) {
            out = new byte[HEADER_LEN + len];
            System.arraycopy(plaintext, off, out, HEADER_LEN, len);
            bodyLen = len;
        } else {
            out = new byte[HEADER_LEN + len + AeadCipher.TAG_LEN];
            bodyLen = aead.encrypt(nonce(packetNumber), headerBuf, plaintext, off, len, out, HEADER_LEN);
        }
        System.arraycopy(headerBuf, 0, out, 0, HEADER_LEN);

        // The mask covers the body, so it can only be computed after the body is final.
        applyMask(out, HEADER_LEN, bodyLen);
        return out;
    }

    public Decoded decode(byte[] datagram, int off, int len) throws ProtocolException {
        int minLen = HEADER_LEN + (aead == null ? 0 : AeadCipher.TAG_LEN);
        if (len < minLen) {
            throw new ProtocolException("datagram too short: " + len + " < " + minLen);
        }
        int bodyLen = len - HEADER_LEN;
        readHeader(datagram, off, bodyLen);

        int flags = headerBuf[MASKED_OFFSET] & 0xFF;
        int connectionId = ((headerBuf[0] & 0xFF) << 24)
                | ((headerBuf[1] & 0xFF) << 16)
                | ((headerBuf[2] & 0xFF) << 8)
                | (headerBuf[3] & 0xFF);
        long packetNumber = ((headerBuf[5] & 0xFFL) << 24)
                | ((headerBuf[6] & 0xFFL) << 16)
                | ((headerBuf[7] & 0xFFL) << 8)
                | (headerBuf[8] & 0xFFL);

        if (aead == null) {
            byte[] plaintext = new byte[bodyLen];
            System.arraycopy(datagram, off + HEADER_LEN, plaintext, 0, bodyLen);
            return new Decoded(flags, connectionId, packetNumber, plaintext);
        }
        try {
            byte[] out = new byte[bodyLen];
            int written = aead.decrypt(nonce(packetNumber), headerBuf, datagram, off + HEADER_LEN, bodyLen, out, 0);
            return new Decoded(flags, connectionId, packetNumber, Arrays.copyOf(out, written));
        } catch (AeadCipher.AuthenticationException e) {
            throw new ProtocolException("packet authentication failed", e);
        }
    }

    /** XORs the masked header fields in place with {@code HMAC(hpKey, body)[0..5]}. */
    private void applyMask(byte[] packet, int bodyOff, int bodyLen) {
        computeMask(packet, bodyOff, bodyLen);
        for (int i = 0; i < MASKED_LEN; i++) {
            packet[MASKED_OFFSET + i] ^= maskBuf[i];
        }
    }

    /** Unmasks the received header fields into {@link #headerBuf}, keeping the clear id as-is. */
    private void readHeader(byte[] datagram, int off, int bodyLen) {
        computeMask(datagram, off + HEADER_LEN, bodyLen);
        System.arraycopy(datagram, off, headerBuf, 0, MASKED_OFFSET);
        for (int i = 0; i < MASKED_LEN; i++) {
            headerBuf[MASKED_OFFSET + i] = (byte) (datagram[off + MASKED_OFFSET + i] ^ maskBuf[i]);
        }
    }

    private void computeMask(byte[] data, int off, int len) {
        hpMac.update(data, off, len);
        try {
            hpMac.doFinal(maskBuf, 0);
        } catch (ShortBufferException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 12-byte AEAD nonce: eight zero bytes then the packet number, big-endian. */
    private byte[] nonce(long packetNumber) {
        Arrays.fill(nonceBuf, (byte) 0);
        nonceBuf[NONCE_PREFIX_ZEROES] = (byte) (packetNumber >>> 24);
        nonceBuf[NONCE_PREFIX_ZEROES + 1] = (byte) (packetNumber >>> 16);
        nonceBuf[NONCE_PREFIX_ZEROES + 2] = (byte) (packetNumber >>> 8);
        nonceBuf[NONCE_PREFIX_ZEROES + 3] = (byte) packetNumber;
        return nonceBuf;
    }

    private void writeHeader(int flags, int connectionId, long packetNumber) {
        headerBuf[0] = (byte) (connectionId >>> 24);
        headerBuf[1] = (byte) (connectionId >>> 16);
        headerBuf[2] = (byte) (connectionId >>> 8);
        headerBuf[3] = (byte) connectionId;
        headerBuf[4] = (byte) flags;
        headerBuf[5] = (byte) (packetNumber >>> 24);
        headerBuf[6] = (byte) (packetNumber >>> 16);
        headerBuf[7] = (byte) (packetNumber >>> 8);
        headerBuf[8] = (byte) packetNumber;
    }
}
