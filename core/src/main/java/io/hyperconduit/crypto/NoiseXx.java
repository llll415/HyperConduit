package io.hyperconduit.crypto;

import io.hyperconduit.util.InBuffer;
import io.hyperconduit.util.OutBuffer;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Arrays;

/**
 * The Noise XX handshake over {@code Noise_XX_25519_ChaChaPoly_SHA256}, built only from JDK
 * primitives (no Bouncy Castle, no full TLS 1.3).
 *
 * <pre>
 *   -> e
 *   &lt;- e, ee, s, es
 *   -> s, se
 * </pre>
 *
 * <p>The pre-shared key is bound in as the <b>prologue</b>, not as a {@code psk} token. That makes
 * it an access-control gate rather than key material: a peer using a different prologue diverges
 * on the handshake hash and fails the first AEAD check, so it can never complete a handshake or
 * learn anything from one. Both static keys still contribute real DH secrecy.
 *
 * <p>Message payloads are the natural place to carry the tunnel's own negotiation (target
 * address, protocol version), since they are authenticated but not part of the DH transcript.
 */
public final class NoiseXx {

    public static final String PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256";
    public static final int MESSAGE_COUNT = 3;
    public static final int EPHEMERAL_LEN = X25519.KEY_LEN;
    /** A transmitted static key is 32 bytes of key plus the 16-byte AEAD tag. */
    public static final int ENCRYPTED_STATIC_LEN = X25519.KEY_LEN + AeadCipher.TAG_LEN;

    private static final byte[] EMPTY = new byte[0];

    public enum Role {
        INITIATOR,
        RESPONDER
    }

    public static final class HandshakeException extends Exception {
        HandshakeException(String message) {
            super(message);
        }

        HandshakeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final SymmetricState sym;
    private final Role role;
    private final KeyPair ephemeral;
    private final PrivateKey staticPrivate;
    private final byte[] staticPublic;

    private byte[] remoteEphemeral;
    private byte[] remoteStatic;
    private int messageIndex;
    private TransportKeys keys;

    private NoiseXx(Role role, KeyPair staticKey) {
        this.role = role;
        this.staticPrivate = staticKey.getPrivate();
        this.staticPublic = X25519.encodePublic(staticKey.getPublic());
        this.sym = new SymmetricState(PROTOCOL_NAME);
        this.ephemeral = X25519.generate();
    }

    public static NoiseXx initiator(KeyPair staticKey) {
        return new NoiseXx(Role.INITIATOR, staticKey);
    }

    public static NoiseXx responder(KeyPair staticKey) {
        return new NoiseXx(Role.RESPONDER, staticKey);
    }

    /** Temporary source compatibility for v1 callers; the PSK is intentionally ignored in v2. */
    @Deprecated public static NoiseXx initiator(KeyPair staticKey, byte[] ignoredPsk) {
        return initiator(staticKey);
    }

    /** Temporary source compatibility for v1 callers; the PSK is intentionally ignored in v2. */
    @Deprecated public static NoiseXx responder(KeyPair staticKey, byte[] ignoredPsk) {
        return responder(staticKey);
    }

    public Role role() {
        return role;
    }

    public byte[] staticPublicKey() {
        return staticPublic.clone();
    }

    public byte[] remoteStaticPublicKey() {
        return remoteStatic == null ? null : remoteStatic.clone();
    }

    /** This side's ephemeral public key. */
    public byte[] ephemeralPublicKey() {
        return X25519.encodePublic(ephemeral.getPublic());
    }

    public boolean isComplete() {
        return keys != null;
    }

    /** True when the next handshake message is ours to write rather than read. */
    public boolean shouldWriteNext() {
        return (role == Role.INITIATOR) == (messageIndex % 2 == 0);
    }

    public int messageIndex() {
        return messageIndex;
    }

    public byte[] writeMessage(byte[] payload) throws HandshakeException {
        if (isComplete()) {
            throw new HandshakeException("handshake already complete");
        }
        if (!shouldWriteNext()) {
            throw new HandshakeException("not our turn to write (message index " + messageIndex + ")");
        }
        OutBuffer out = new OutBuffer(128);
        switch (messageIndex) {
            case 0 -> {
                writeE(out);
                writePayload(out, payload);
            }
            case 1 -> {
                writeE(out);
                doEe();
                writeS(out);
                doEs();
                writePayload(out, payload);
            }
            case 2 -> {
                writeS(out);
                doSe();
                writePayload(out, payload);
                finish();
            }
            default -> throw new HandshakeException("no handshake message to write at index " + messageIndex);
        }
        messageIndex++;
        return out.toByteArray();
    }

    public byte[] readMessage(byte[] message) throws HandshakeException {
        if (isComplete()) {
            throw new HandshakeException("handshake already complete");
        }
        if (shouldWriteNext()) {
            throw new HandshakeException("not our turn to read (message index " + messageIndex + ")");
        }
        byte[] payload;
        try {
            InBuffer in = new InBuffer(message);
            switch (messageIndex) {
                case 0 -> {
                    readE(in);
                    payload = readPayload(in);
                }
                case 1 -> {
                    readE(in);
                    doEe();
                    readS(in);
                    doEs();
                    payload = readPayload(in);
                }
                case 2 -> {
                    readS(in);
                    doSe();
                    payload = readPayload(in);
                    finish();
                }
                default -> throw new HandshakeException("no handshake message to read at index " + messageIndex);
            }
            if (in.hasRemaining()) {
                throw new HandshakeException("handshake message has " + in.remaining() + " trailing bytes");
            }
        } catch (AeadCipher.AuthenticationException e) {
            throw new HandshakeException("handshake authentication failed (keys or tampering)", e);
        } catch (InBuffer.BufferUnderflow e) {
            throw new HandshakeException("truncated handshake message", e);
        } catch (SecurityException e) {
            throw new HandshakeException("X25519 rejected the peer's key material", e);
        }
        messageIndex++;
        return payload;
    }

    public TransportKeys complete() throws HandshakeException {
        if (!isComplete()) {
            throw new HandshakeException("handshake not complete (message index " + messageIndex + ")");
        }
        return keys;
    }

    // --- Noise tokens ---------------------------------------------------------------

    /**
     * The {@code e} token is always plaintext in XX: both parties send their ephemeral before any
     * DH has established a key, so {@code CipherState.hasKey()} is false at that point.
     */
    private void writeE(OutBuffer out) {
        byte[] ep = X25519.encodePublic(ephemeral.getPublic());
        sym.mixHash(ep);
        out.writeBytes(ep);
    }

    private void readE(InBuffer in) {
        byte[] ep = in.readBytes(EPHEMERAL_LEN);
        sym.mixHash(ep);
        remoteEphemeral = ep;
    }

    private void writeS(OutBuffer out) {
        sym.encryptAndHash(out, staticPublic);
    }

    private void readS(InBuffer in) throws AeadCipher.AuthenticationException {
        byte[] ct = in.readBytes(ENCRYPTED_STATIC_LEN);
        remoteStatic = sym.decryptAndHash(ct, 0, ct.length);
    }

    /** {@code ee} = DH(initiator ephemeral, responder ephemeral). */
    private void doEe() {
        sym.mixKey(X25519.dh(ephemeral.getPrivate(), remoteEphemeral));
    }

    /** {@code es} = DH(initiator ephemeral, responder static). */
    private void doEs() {
        if (role == Role.INITIATOR) {
            sym.mixKey(X25519.dh(ephemeral.getPrivate(), remoteStatic));
        } else {
            sym.mixKey(X25519.dh(staticPrivate, remoteEphemeral));
        }
    }

    /** {@code se} = DH(initiator static, responder ephemeral). */
    private void doSe() {
        if (role == Role.INITIATOR) {
            sym.mixKey(X25519.dh(staticPrivate, remoteEphemeral));
        } else {
            sym.mixKey(X25519.dh(ephemeral.getPrivate(), remoteStatic));
        }
    }

    private void writePayload(OutBuffer out, byte[] payload) {
        sym.encryptAndHash(out, payload == null ? EMPTY : payload);
    }

    private byte[] readPayload(InBuffer in) throws AeadCipher.AuthenticationException {
        byte[] ct = in.readBytes(in.remaining());
        return sym.decryptAndHash(ct, 0, ct.length);
    }

    private void finish() {
        byte[][] split = sym.split(3);
        byte[] initiatorToResponder = split[0];
        byte[] responderToInitiator = split[1];
        byte[] sendKey = role == Role.INITIATOR ? initiatorToResponder : responderToInitiator;
        byte[] recvKey = role == Role.INITIATOR ? responderToInitiator : initiatorToResponder;
        keys = new TransportKeys(sendKey, recvKey, split[2], remoteStatic);
        sym.destroy();
        Arrays.fill(remoteEphemeral, (byte) 0);
    }

}
