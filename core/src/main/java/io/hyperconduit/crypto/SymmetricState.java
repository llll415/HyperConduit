package io.hyperconduit.crypto;

import io.hyperconduit.util.OutBuffer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Noise SymmetricState (spec §4.2): the chaining key, handshake hash, and current CipherState. */
final class SymmetricState {

    private static final int HASH_LEN = Hkdf.HASH_LEN;
    private static final byte[] EMPTY = new byte[0];

    private final MessageDigest digest;
    private final CipherState cipherState = new CipherState();
    private byte[] chainingKey;
    private byte[] handshakeHash;

    SymmetricState(String protocolName) {
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] name = protocolName.getBytes(StandardCharsets.UTF_8);
        if (name.length <= HASH_LEN) {
            this.handshakeHash = new byte[HASH_LEN];
            System.arraycopy(name, 0, handshakeHash, 0, name.length);
        } else {
            this.handshakeHash = sha256(name, 0, name.length);
        }
        this.chainingKey = handshakeHash.clone();
    }

    void mixHash(byte[] data) {
        mixHash(data, 0, data.length);
    }

    void mixHash(byte[] data, int off, int len) {
        digest.reset();
        digest.update(handshakeHash);
        digest.update(data, off, len);
        handshakeHash = digest.digest();
    }

    void mixKey(byte[] inputKeyMaterial) {
        byte[][] out = Hkdf.derive(chainingKey, inputKeyMaterial, 2);
        chainingKey = out[0];
        cipherState.initializeKey(out[1]);
    }

    /** Noise EncryptAndHash: append the (possibly plaintext) output, then hash what was appended. */
    void encryptAndHash(OutBuffer out, byte[] plaintext) {
        byte[] produced = cipherState.encryptWithAd(handshakeHash, plaintext);
        out.writeBytes(produced);
        mixHash(produced);
    }

    /** Noise DecryptAndHash: hash the ciphertext slice, then return the plaintext. */
    byte[] decryptAndHash(byte[] data, int off, int len) throws AeadCipher.AuthenticationException {
        byte[] plaintext = cipherState.decryptWithAd(handshakeHash, data, off, len);
        mixHash(data, off, len);
        return plaintext;
    }

    /**
     * Noise Split, extended to {@code numOutputs} so the packet-header mask key can be derived
     * from the same chaining key without reusing transport key material.
     */
    byte[][] split(int numOutputs) {
        return Hkdf.derive(chainingKey, EMPTY, numOutputs);
    }

    void destroy() {
        Arrays.fill(chainingKey, (byte) 0);
        Arrays.fill(handshakeHash, (byte) 0);
    }

    private byte[] sha256(byte[] data, int off, int len) {
        digest.reset();
        digest.update(data, off, len);
        return digest.digest();
    }
}
