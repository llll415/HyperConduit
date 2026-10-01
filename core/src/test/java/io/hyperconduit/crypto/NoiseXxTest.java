package io.hyperconduit.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoiseXxTest {

    private static final byte[] PSK = "hyperconduit-test-psk".getBytes(StandardCharsets.UTF_8);

    /** Runs the full three-message XX exchange and returns both sides' transport keys. */
    private static TransportKeys[] handshake(byte[] clientPsk, byte[] serverPsk,
                                             byte[] msg1Payload, byte[] msg2Payload) throws Exception {
        KeyPair clientStatic = X25519.generate();
        KeyPair serverStatic = X25519.generate();
        NoiseXx client = NoiseXx.initiator(clientStatic, clientPsk);
        NoiseXx server = NoiseXx.responder(serverStatic, serverPsk);

        assertTrue(client.shouldWriteNext());
        assertFalse(server.shouldWriteNext());

        byte[] msg1 = client.writeMessage(msg1Payload);
        assertArrayEquals(msg1Payload, server.readMessage(msg1));

        assertFalse(client.shouldWriteNext());
        assertTrue(server.shouldWriteNext());

        byte[] msg2 = server.writeMessage(msg2Payload);
        assertArrayEquals(msg2Payload, client.readMessage(msg2));

        byte[] msg3 = client.writeMessage(new byte[0]);
        assertEquals(0, server.readMessage(msg3).length);

        assertTrue(client.isComplete());
        assertTrue(server.isComplete());
        return new TransportKeys[]{client.complete(), server.complete()};
    }

    @Test
    void fullHandshakeProducesMatchingTransportKeys() throws Exception {
        byte[] payload1 = "connect:play.example.com:25565".getBytes(StandardCharsets.UTF_8);
        byte[] payload2 = "ok".getBytes(StandardCharsets.UTF_8);

        TransportKeys[] keys = handshake(PSK, PSK, payload1, payload2);
        TransportKeys client = keys[0];
        TransportKeys server = keys[1];

        assertArrayEquals(client.sendKey(), server.recvKey(), "client send key must be server recv key");
        assertArrayEquals(client.recvKey(), server.sendKey(), "client recv key must be server send key");
        assertArrayEquals(client.hpKey(), server.hpKey(), "header-protection key must match");

        assertEquals(32, client.sendKey().length);
        // Three distinct HKDF outputs: reusing one across roles would break key separation.
        assertFalse(Arrays.equals(client.sendKey(), client.recvKey()));
        assertFalse(Arrays.equals(client.sendKey(), client.hpKey()));
        assertFalse(Arrays.equals(client.recvKey(), client.hpKey()));
    }

    @Test
    void bothPartiesLearnEachOthersStaticKey() throws Exception {
        KeyPair clientStatic = X25519.generate();
        KeyPair serverStatic = X25519.generate();
        NoiseXx client = NoiseXx.initiator(clientStatic, PSK);
        NoiseXx server = NoiseXx.responder(serverStatic, PSK);

        server.readMessage(client.writeMessage(new byte[0]));
        client.readMessage(server.writeMessage(new byte[0]));
        server.readMessage(client.writeMessage(new byte[0]));

        assertArrayEquals(X25519.encodePublic(serverStatic.getPublic()), client.complete().remoteStaticPublicKey());
        assertArrayEquals(X25519.encodePublic(clientStatic.getPublic()), server.complete().remoteStaticPublicKey());
    }

    @Test
    void distinctServerIdentitiesProduceDistinctAuthenticatedStaticKeys() throws Exception {
        KeyPair clientStatic = X25519.generate();
        KeyPair firstServer = X25519.generate();
        KeyPair secondServer = X25519.generate();
        NoiseXx client = NoiseXx.initiator(clientStatic);
        NoiseXx responder = NoiseXx.responder(firstServer);
        responder.readMessage(client.writeMessage(new byte[0]));
        client.readMessage(responder.writeMessage(new byte[0]));
        assertFalse(Arrays.equals(X25519.encodePublic(firstServer.getPublic()),
                X25519.encodePublic(secondServer.getPublic())));
        assertArrayEquals(X25519.encodePublic(firstServer.getPublic()), client.remoteStaticPublicKey());
    }

    @Test
    void truncatedMessageIsRejected() throws Exception {
        NoiseXx client = NoiseXx.initiator(X25519.generate(), PSK);
        NoiseXx server = NoiseXx.responder(X25519.generate(), PSK);

        server.readMessage(client.writeMessage(new byte[0]));
        byte[] msg2 = server.writeMessage(new byte[0]);

        byte[] truncated = Arrays.copyOf(msg2, msg2.length - 1);
        assertThrows(NoiseXx.HandshakeException.class, () -> client.readMessage(truncated));
    }

    @Test
    void roleGuardsPreventOutOfOrderUse() {
        NoiseXx client = NoiseXx.initiator(X25519.generate(), PSK);
        assertThrows(NoiseXx.HandshakeException.class, () -> client.readMessage(new byte[64]));

        NoiseXx server = NoiseXx.responder(X25519.generate(), PSK);
        assertThrows(NoiseXx.HandshakeException.class, () -> server.writeMessage(new byte[0]));
    }

    @Test
    void transportKeysEncryptAndDecryptAcrossDirections() throws Exception {
        TransportKeys[] keys = handshake(PSK, PSK, new byte[0], new byte[0]);
        AeadCipher sender = new AeadCipher(keys[0].sendKey());
        AeadCipher receiver = new AeadCipher(keys[1].recvKey());

        byte[] nonce = new byte[AeadCipher.NONCE_LEN];
        byte[] aad = new byte[]{1, 2, 3};
        byte[] plaintext = "minecraft-packet-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = new byte[plaintext.length + AeadCipher.TAG_LEN];
        int written = sender.encrypt(nonce, aad, plaintext, 0, plaintext.length, ciphertext, 0);
        assertEquals(ciphertext.length, written);

        byte[] out = new byte[plaintext.length];
        int decrypted = receiver.decrypt(nonce, aad, ciphertext, 0, ciphertext.length, out, 0);
        assertEquals(plaintext.length, decrypted);
        assertArrayEquals(plaintext, out);

        byte[] tampered = ciphertext.clone();
        tampered[0] ^= 0x01;
        assertThrows(AeadCipher.AuthenticationException.class,
                () -> receiver.decrypt(nonce, aad, tampered, 0, tampered.length, out, 0));
    }

    @Test
    void corruptMessage2LeavesTheHandshakeUnrecoverable() throws Exception {
        NoiseXx client = NoiseXx.initiator(X25519.generate(), PSK);
        NoiseXx server = NoiseXx.responder(X25519.generate(), PSK);

        server.readMessage(client.writeMessage(new byte[0]));
        byte[] msg2 = server.writeMessage(new byte[0]);

        byte[] corrupted = msg2.clone();
        corrupted[corrupted.length - 1] ^= 0x01;
        assertThrows(NoiseXx.HandshakeException.class, () -> client.readMessage(corrupted));

        // Documents a real limitation rather than desired behaviour: parsing the corrupt message ran
        // the `e` and `ee` tokens before the AEAD check failed, so the handshake hash and chaining
        // key are already past the point where the intact message 2 could be accepted. The only
        // recovery is a whole new handshake, which is why SessionEngine fails the session here
        // instead of retrying. Do not "fix" this by re-reading without resetting the state.
        assertThrows(NoiseXx.HandshakeException.class, () -> client.readMessage(msg2));
    }

    @Test
    void failedDecryptDoesNotAdvanceTheCipherNonce() throws Exception {
        // The property SessionEngine's responder relies on: rejecting a datagram that is not the
        // message we expected must leave the cipher state untouched, so the genuine message still
        // decrypts. Advancing on failure silently wedges the handshake.
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        CipherState sender = new CipherState();
        CipherState receiver = new CipherState();
        sender.initializeKey(key);
        receiver.initializeKey(key);

        byte[] ad = "aad".getBytes(StandardCharsets.UTF_8);
        byte[] plaintext = "message".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = sender.encryptWithAd(ad, plaintext);

        byte[] corrupt = ciphertext.clone();
        corrupt[corrupt.length - 1] ^= 0x01;
        assertThrows(AeadCipher.AuthenticationException.class,
                () -> receiver.decryptWithAd(ad, corrupt, 0, corrupt.length));
        assertArrayEquals(plaintext, receiver.decryptWithAd(ad, ciphertext, 0, ciphertext.length));
    }

    @Test
    void x25519RawEncodingRoundTrips() {
        KeyPair pair = X25519.generate();
        byte[] pub = X25519.encodePublic(pair.getPublic());
        byte[] priv = X25519.encodePrivate(pair.getPrivate());
        assertEquals(32, pub.length);
        assertEquals(32, priv.length);

        byte[] sharedFromRaw = X25519.dh(X25519.decodePrivate(priv), pub);
        byte[] sharedFromObjects = X25519.dh(pair.getPrivate(), X25519.decodePublic(pub));
        assertArrayEquals(sharedFromRaw, sharedFromObjects);
    }

    @Test
    void hkdfDerivesChainedOutputs() {
        byte[][] two = Hkdf.derive(new byte[32], new byte[]{1, 2, 3}, 2);
        byte[][] three = Hkdf.derive(new byte[32], new byte[]{1, 2, 3}, 3);

        assertEquals(32, two[0].length);
        assertArrayEquals(two[0], three[0], "first output must not depend on the requested count");
        assertArrayEquals(two[1], three[1]);
        assertFalse(Arrays.equals(three[0], three[2]));
        assertThrows(IllegalArgumentException.class, () -> Hkdf.derive(new byte[32], new byte[0], 4));
    }

}
