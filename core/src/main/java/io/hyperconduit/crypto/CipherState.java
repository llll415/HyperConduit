package io.hyperconduit.crypto;

/** Noise CipherState (spec §4.2): a key plus a 64-bit nonce counter. */
final class CipherState {

    private static final long MAX_NONCE = -1L; // 2^64 - 1

    private AeadCipher aead;
    private long nonce;

    boolean hasKey() {
        return aead != null;
    }

    void initializeKey(byte[] key) {
        this.aead = new AeadCipher(key);
        this.nonce = 0;
    }

    /** Noise nonce encoding: 32 zero bits, then the counter as 64-bit little-endian. */
    static void writeNonce(long n, byte[] out) {
        out[0] = 0;
        out[1] = 0;
        out[2] = 0;
        out[3] = 0;
        for (int i = 0; i < 8; i++) {
            out[4 + i] = (byte) (n >>> (8 * i));
        }
    }

    /** Returns the plaintext verbatim when no key has been set yet, per the Noise "DoNothing" rule. */
    byte[] encryptWithAd(byte[] ad, byte[] plaintext) {
        if (aead == null) {
            return plaintext.clone();
        }
        advanceNonce();
        byte[] nonceBytes = new byte[AeadCipher.NONCE_LEN];
        writeNonce(nonce - 1, nonceBytes);
        byte[] dst = new byte[plaintext.length + AeadCipher.TAG_LEN];
        int written = aead.encrypt(nonceBytes, ad, plaintext, 0, plaintext.length, dst, 0);
        if (written != dst.length) {
            throw new IllegalStateException("AEAD wrote " + written + " bytes, expected " + dst.length);
        }
        return dst;
    }

    byte[] decryptWithAd(byte[] ad, byte[] ciphertext, int off, int len)
            throws AeadCipher.AuthenticationException {
        if (aead == null) {
            byte[] plain = new byte[len];
            System.arraycopy(ciphertext, off, plain, 0, len);
            return plain;
        }
        // The nonce advances only on success. The Noise spec treats a failed DecryptAndHash as fatal
        // for the handshake, so implementations normally never decrypt again and this does not
        // matter. This transport runs over UDP and deliberately retries handshake messages, so a
        // corrupt or misidentified datagram must not consume a nonce: doing so silently
        // desynchronises the cipher state and makes the genuine message undecryptable, which wedges
        // the handshake permanently. Encryption still advances unconditionally, since it cannot fail
        // partway and reusing a nonce to encrypt would be catastrophic.
        if (nonce == MAX_NONCE) {
            throw new IllegalStateException("nonce exhausted");
        }
        byte[] nonceBytes = new byte[AeadCipher.NONCE_LEN];
        writeNonce(nonce, nonceBytes);
        byte[] dst = new byte[len];
        int written = aead.decrypt(nonceBytes, ad, ciphertext, off, len, dst, 0);
        nonce++;
        byte[] out = new byte[written];
        System.arraycopy(dst, 0, out, 0, written);
        return out;
    }

    private void advanceNonce() {
        if (nonce == MAX_NONCE) {
            throw new IllegalStateException("nonce exhausted");
        }
        nonce++;
    }
}
