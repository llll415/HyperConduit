package io.hyperconduit.conn;

import io.hyperconduit.ProtocolException;
import io.hyperconduit.crypto.Hkdf;
import io.hyperconduit.util.InBuffer;
import io.hyperconduit.util.OutBuffer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The payload carried by the Noise handshake messages, plus the PSK authenticator that guards them.
 *
 * <p><b>Why an authenticator at all.</b> The Noise prologue already binds the PSK, but the first
 * handshake message contains no ciphertext — a responder can parse it without knowing the PSK. A
 * peer with a wrong PSK could therefore make the server perform an X25519 DH per packet. The
 * authenticator is {@code HMAC(psk, e_i)}: the server recomputes it from the 32 plaintext ephemeral
 * bytes at the front of message 1 and drops the packet before touching the DH.
 *
 * <p>It also carries each side's receive window and datagram size, so flow control has correct
 * initial credit from the first data packet instead of waiting for a {@code MAX_DATA} round trip.
 */
public final class HandshakePayload {

    public static final int AUTHENTICATOR_LEN = 32;
    private static final byte[] AUTH_LABEL = "HyperConduit/auth/v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MASK_LABEL = "HyperConduit/hp/v1".getBytes(StandardCharsets.UTF_8);
    private static final int VERSION = 1;

    private HandshakePayload() {
    }

    /**
     * Key used to mask packet headers before the handshake derives its own. Both sides can compute
     * it from the PSK alone, which is what lets the very first datagram already look random.
     */
    public static byte[] maskKey(byte[] psk) {
        return Hkdf.derive(MASK_LABEL, psk == null || psk.length == 0 ? new byte[32] : psk, 1)[0];
    }

    /** {@code HMAC(psk, label || initiatorEphemeralPublicKey)}, truncated to 32 bytes. */
    public static byte[] authenticator(byte[] psk, byte[] initiatorEphemeralPublicKey) {
        OutBuffer input = new OutBuffer(AUTH_LABEL.length + initiatorEphemeralPublicKey.length);
        input.writeBytes(AUTH_LABEL);
        input.writeBytes(initiatorEphemeralPublicKey);
        byte[] mac = Hkdf.hmac(psk == null || psk.length == 0 ? new byte[32] : psk, input.toByteArray());
        return mac; // HmacSHA256 already yields exactly 32 bytes
    }

    /** Constant-time comparison, so a wrong PSK does not leak how many leading bytes matched. */
    public static boolean verify(byte[] psk, byte[] initiatorEphemeralPublicKey, byte[] candidate) {
        if (candidate == null || candidate.length != AUTHENTICATOR_LEN) {
            return false;
        }
        return MessageDigest.isEqual(authenticator(psk, initiatorEphemeralPublicKey), candidate);
    }

    /** The first 32 bytes of a Noise XX message 1 are the initiator's plaintext ephemeral key. */
    public static byte[] initiatorEphemeralFromMessage1(byte[] message) {
        if (message.length < AUTHENTICATOR_LEN) {
            throw new IllegalArgumentException("message 1 too short: " + message.length);
        }
        byte[] ephemeral = new byte[AUTHENTICATOR_LEN];
        System.arraycopy(message, 0, ephemeral, 0, AUTHENTICATOR_LEN);
        return ephemeral;
    }

    public record Negotiation(int receiveWindowBytes, int maxDatagramSize) {

        void validate() throws ProtocolException {
            if (receiveWindowBytes <= 0 || receiveWindowBytes > (1 << 28)) {
                throw new ProtocolException("absurd receive window: " + receiveWindowBytes);
            }
            if (maxDatagramSize < 64 || maxDatagramSize > 65535) {
                throw new ProtocolException("absurd datagram size: " + maxDatagramSize);
            }
        }
    }

    public static byte[] encodeClientPayload(byte[] authenticator, Negotiation negotiation) {
        OutBuffer out = new OutBuffer(AUTHENTICATOR_LEN + 16);
        out.writeBytes(authenticator);
        writeNegotiation(out, negotiation);
        return out.toByteArray();
    }

    /** @return the negotiation, after the 32-byte authenticator prefix has been stripped. */
    public static Negotiation decodeClientPayload(byte[] payload) throws ProtocolException {
        InBuffer in = new InBuffer(payload);
        try {
            in.readBytes(AUTHENTICATOR_LEN);
            return readNegotiation(in);
        } catch (InBuffer.BufferUnderflow e) {
            throw new ProtocolException("truncated client handshake payload", e);
        }
    }

    public static byte[] encodeServerPayload(Negotiation negotiation) {
        OutBuffer out = new OutBuffer(16);
        writeNegotiation(out, negotiation);
        return out.toByteArray();
    }

    public static Negotiation decodeServerPayload(byte[] payload) throws ProtocolException {
        try {
            return readNegotiation(new InBuffer(payload));
        } catch (InBuffer.BufferUnderflow e) {
            throw new ProtocolException("truncated server handshake payload", e);
        }
    }

    private static void writeNegotiation(OutBuffer out, Negotiation negotiation) {
        out.writeVarInt(VERSION);
        out.writeVarInt(negotiation.receiveWindowBytes());
        out.writeVarInt(negotiation.maxDatagramSize());
    }

    private static Negotiation readNegotiation(InBuffer in) throws ProtocolException {
        long version = in.readVarInt();
        if (version != VERSION) {
            throw new ProtocolException("unsupported tunnel version " + version);
        }
        Negotiation negotiation = new Negotiation((int) in.readVarInt(), (int) in.readVarInt());
        negotiation.validate();
        return negotiation;
    }
}
