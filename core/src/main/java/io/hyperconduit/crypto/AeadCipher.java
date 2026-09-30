package io.hyperconduit.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * ChaCha20-Poly1305 over the JDK provider.
 *
 * <p>Two JDK quirks matter here: the AEAD variant only accepts {@link IvParameterSpec} (passing
 * {@code ChaCha20ParameterSpec} throws {@code InvalidAlgorithmParameterException}), and the
 * instance must be re-initialised for every nonce. Instances are not thread-safe; the transport
 * keeps one per direction and the session serialises access.
 */
public final class AeadCipher {

    public static final int KEY_LEN = 32;
    public static final int NONCE_LEN = 12;
    public static final int TAG_LEN = 16;

    private static final String TRANSFORMATION = "ChaCha20-Poly1305";

    private final Cipher cipher;
    private final SecretKeySpec keySpec;

    public AeadCipher(byte[] key) {
        if (key.length != KEY_LEN) {
            throw new IllegalArgumentException("AEAD key must be " + KEY_LEN + " bytes");
        }
        this.keySpec = new SecretKeySpec(key, "ChaCha20");
        try {
            this.cipher = Cipher.getInstance(TRANSFORMATION);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ChaCha20-Poly1305 unavailable", e);
        }
    }

    /** Encrypts into {@code dst} at {@code dstOff}; returns bytes written ({@code srcLen + TAG_LEN}). */
    public int encrypt(byte[] nonce, byte[] aad, byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff) {
        try {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(nonce));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(src, srcOff, srcLen, dst, dstOff);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AEAD encrypt failed", e);
        }
    }

    /** Decrypts into {@code dst} at {@code dstOff}; returns bytes written ({@code srcLen - TAG_LEN}). */
    public int decrypt(byte[] nonce, byte[] aad, byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff)
            throws AuthenticationException {
        if (srcLen < TAG_LEN) {
            throw new AuthenticationException("ciphertext shorter than the AEAD tag");
        }
        try {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(nonce));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(src, srcOff, srcLen, dst, dstOff);
        } catch (AEADBadTagException e) {
            throw new AuthenticationException("AEAD tag mismatch", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AEAD decrypt failed", e);
        }
    }

    public static final class AuthenticationException extends Exception {
        AuthenticationException(String message) {
            super(message);
        }

        AuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
