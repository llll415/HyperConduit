package io.hyperconduit.crypto;

import javax.crypto.KeyAgreement;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * X25519 over the JDK's built-in provider. The JDK only exposes keys in encoded form
 * (44-byte X.509 DER for public, 48-byte PKCS#8 for private), while the Noise wire format
 * carries bare 32-byte keys, so the DER envelopes are added/stripped here.
 */
public final class X25519 {

    public static final int KEY_LEN = 32;
    private static final String ALGORITHM = "X25519";

    private static final byte[] PUB_PREFIX = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00
    };
    private static final byte[] PRIV_PREFIX = {
            0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x04, 0x22, 0x04, 0x20
    };

    private X25519() {
    }

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 unavailable", e);
        }
    }

    public static byte[] encodePublic(PublicKey key) {
        byte[] der = key.getEncoded();
        return Arrays.copyOfRange(der, der.length - KEY_LEN, der.length);
    }

    public static byte[] encodePrivate(PrivateKey key) {
        byte[] pkcs8 = key.getEncoded();
        return Arrays.copyOfRange(pkcs8, pkcs8.length - KEY_LEN, pkcs8.length);
    }

    public static PublicKey decodePublic(byte[] raw) {
        requireRaw(raw);
        byte[] der = new byte[PUB_PREFIX.length + KEY_LEN];
        System.arraycopy(PUB_PREFIX, 0, der, 0, PUB_PREFIX.length);
        System.arraycopy(raw, 0, der, PUB_PREFIX.length, KEY_LEN);
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not a valid X25519 public key", e);
        }
    }

    public static PrivateKey decodePrivate(byte[] raw) {
        requireRaw(raw);
        byte[] pkcs8 = new byte[PRIV_PREFIX.length + KEY_LEN];
        System.arraycopy(PRIV_PREFIX, 0, pkcs8, 0, PRIV_PREFIX.length);
        System.arraycopy(raw, 0, pkcs8, PRIV_PREFIX.length, KEY_LEN);
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not a valid X25519 private key", e);
        }
    }

    public static byte[] dh(PrivateKey privateKey, byte[] peerPublicKeyRaw) {
        byte[] shared = dh(privateKey, decodePublic(peerPublicKeyRaw));
        // Noise requires rejecting the all-zero output produced by low-order points.
        boolean allZero = true;
        for (byte b : shared) {
            if (b != 0) {
                allZero = false;
                break;
            }
        }
        if (allZero) {
            throw new SecurityException("X25519 produced the all-zero shared secret");
        }
        return shared;
    }

    public static byte[] dh(PrivateKey privateKey, PublicKey peerPublicKey) {
        try {
            KeyAgreement agreement = KeyAgreement.getInstance(ALGORITHM);
            agreement.init(privateKey);
            agreement.doPhase(peerPublicKey, true);
            return agreement.generateSecret();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("X25519 DH failed", e);
        }
    }

    private static void requireRaw(byte[] raw) {
        if (raw == null || raw.length != KEY_LEN) {
            throw new IllegalArgumentException("expected " + KEY_LEN + " raw key bytes");
        }
    }
}
