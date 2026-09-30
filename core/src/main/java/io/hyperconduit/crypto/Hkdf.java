package io.hyperconduit.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Noise's HKDF (Noise spec §4.3), which differs from RFC 5869's Expand step: outputs are
 * chained through the extracted temp key rather than through an accumulating T(i).
 */
public final class Hkdf {

    public static final int HASH_LEN = 32;

    private Hkdf() {
    }

    public static byte[][] derive(byte[] chainingKey, byte[] inputKeyMaterial, int numOutputs) {
        if (numOutputs < 1 || numOutputs > 3) {
            throw new IllegalArgumentException("Noise HKDF supports 1..3 outputs, got " + numOutputs);
        }
        byte[] tempKey = hmac(chainingKey, inputKeyMaterial);
        byte[][] out = new byte[numOutputs][];
        byte[] prev = hmac(tempKey, new byte[]{0x01});
        out[0] = prev;
        if (numOutputs >= 2) {
            byte[] input = concat(prev, (byte) 0x02);
            prev = hmac(tempKey, input);
            out[1] = prev;
        }
        if (numOutputs == 3) {
            out[2] = hmac(tempKey, concat(prev, (byte) 0x03));
        }
        Arrays.fill(tempKey, (byte) 0);
        return out;
    }

    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[HASH_LEN] : key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static byte[] concat(byte[] a, byte b) {
        byte[] out = Arrays.copyOf(a, a.length + 1);
        out[a.length] = b;
        return out;
    }
}
