package io.hyperconduit.conn;

import io.hyperconduit.crypto.Hkdf;
import io.hyperconduit.util.OutBuffer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Stateless address-validation cookie used before a server allocates a tunnel session. */
public final class RetryCookie {

    public static final int EXPIRY_LEN = Long.BYTES;
    public static final int MAC_LEN = 16;
    public static final int LEN = EXPIRY_LEN + MAC_LEN;
    private static final byte[] LABEL = "HyperConduit/retry/v2".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private byte[] currentSecret = randomSecret();
    private byte[] previousSecret = randomSecret();
    private long nextRotationMillis = System.currentTimeMillis() + 60_000L;

    public synchronized byte[] mint(SocketAddress peer, int connectionId, long expiryMillis) {
        rotateIfDue(System.currentTimeMillis());
        byte[] input = input(peer, connectionId, expiryMillis);
        byte[] mac = Hkdf.hmac(currentSecret, input);
        OutBuffer out = new OutBuffer(LEN);
        out.writeLong(expiryMillis);
        out.writeBytes(mac, 0, MAC_LEN);
        return out.toByteArray();
    }

    public synchronized boolean verify(SocketAddress peer, int connectionId, byte[] cookie, long nowMillis) {
        if (cookie == null || cookie.length != LEN) {
            return false;
        }
        long expiry = readLong(cookie, 0);
        if (expiry < nowMillis) {
            return false;
        }
        rotateIfDue(nowMillis);
        byte[] input = input(peer, connectionId, expiry);
        return matches(currentSecret, input, cookie) || matches(previousSecret, input, cookie);
    }

    private void rotateIfDue(long nowMillis) {
        if (nowMillis < nextRotationMillis) {
            return;
        }
        previousSecret = currentSecret;
        currentSecret = randomSecret();
        nextRotationMillis = nowMillis + 60_000L;
    }

    private static boolean matches(byte[] secret, byte[] input, byte[] cookie) {
        byte[] actual = Hkdf.hmac(secret, input);
        byte[] expected = new byte[MAC_LEN];
        System.arraycopy(cookie, EXPIRY_LEN, expected, 0, MAC_LEN);
        byte[] truncated = new byte[MAC_LEN];
        System.arraycopy(actual, 0, truncated, 0, MAC_LEN);
        return MessageDigest.isEqual(truncated, expected);
    }

    private static byte[] input(SocketAddress peer, int connectionId, long expiryMillis) {
        if (!(peer instanceof InetSocketAddress inet) || inet.getAddress() == null) {
            throw new IllegalArgumentException("retry peer must be a resolved InetSocketAddress");
        }
        InetAddress address = inet.getAddress();
        byte[] rawAddress = address.getAddress();
        OutBuffer out = new OutBuffer(LABEL.length + rawAddress.length + 20);
        out.writeBytes(LABEL);
        out.writeByte(rawAddress.length);
        out.writeBytes(rawAddress);
        out.writeInt(inet.getPort());
        out.writeInt(connectionId);
        out.writeLong(expiryMillis);
        return out.toByteArray();
    }

    private static long readLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFFL);
        }
        return value;
    }

    private static byte[] randomSecret() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return secret;
    }
}
