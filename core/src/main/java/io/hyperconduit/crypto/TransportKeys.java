package io.hyperconduit.crypto;

import java.util.Arrays;

/**
 * Keys produced by {@link NoiseXx#complete()}. {@code sendKey}/{@code recvKey} are the two
 * transport directions; {@code hpKey} is a third output used only to mask packet headers.
 *
 * <p>Deriving a third output from the Noise {@code Split} HKDF is an extension to the spec
 * (which defines exactly two) and is safe here because both endpoints are ours.
 */
public record TransportKeys(byte[] sendKey, byte[] recvKey, byte[] hpKey, byte[] remoteStaticPublicKey) {

    public void destroy() {
        Arrays.fill(sendKey, (byte) 0);
        Arrays.fill(recvKey, (byte) 0);
        Arrays.fill(hpKey, (byte) 0);
    }
}
