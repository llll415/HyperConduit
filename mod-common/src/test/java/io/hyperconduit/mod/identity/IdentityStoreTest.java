package io.hyperconduit.mod.identity;

import io.hyperconduit.crypto.X25519;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityStoreTest {

    @Test
    void serverIdentitySurvivesRestart(@TempDir Path directory) {
        KeyPair first = ServerIdentityStore.loadOrCreate(directory);
        KeyPair second = ServerIdentityStore.loadOrCreate(directory);
        assertArrayEquals(X25519.encodePublic(first.getPublic()), X25519.encodePublic(second.getPublic()));
    }

    @Test
    void trustOnFirstUseRejectsIdentityChange(@TempDir Path directory) {
        KnownServerStore store = KnownServerStore.load(directory);
        byte[] first = X25519.encodePublic(X25519.generate().getPublic());
        byte[] changed = X25519.encodePublic(X25519.generate().getPublic());

        assertTrue(store.trustOrVerify("Example.COM.", 25565, first));
        assertTrue(store.trustOrVerify("example.com", 25565, first));
        assertFalse(store.trustOrVerify("example.com", 25565, changed));

        store.replaceTrust("example.com", 25565, changed);
        assertTrue(store.trustOrVerify("example.com", 25565, changed));
        assertTrue(store.trustOrVerify("example.com", 25566, changed));
    }
}
