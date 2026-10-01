package io.hyperconduit.mod.identity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.hyperconduit.crypto.X25519;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.util.Base64;

/** Loads or creates the server's stable Noise static key. */
public final class ServerIdentityStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE = "hyperconduit-server-identity.json";

    private ServerIdentityStore() {
    }

    public static KeyPair loadOrCreate(Path configDir) {
        Path file = configDir.resolve(FILE);
        if (Files.exists(file)) {
            try {
                StoredIdentity stored = GSON.fromJson(Files.readString(file), StoredIdentity.class);
                if (stored == null || stored.privateKey == null || stored.publicKey == null) {
                    throw new IllegalStateException("invalid HyperConduit server identity");
                }
                byte[] privateKey = Base64.getDecoder().decode(stored.privateKey);
                byte[] publicKey = Base64.getDecoder().decode(stored.publicKey);
                return new KeyPair(X25519.decodePublic(publicKey), X25519.decodePrivate(privateKey));
            } catch (IOException | IllegalArgumentException e) {
                throw new IllegalStateException("cannot read " + file, e);
            }
        }
        KeyPair identity = X25519.generate();
        StoredIdentity stored = new StoredIdentity(
                Base64.getEncoder().encodeToString(X25519.encodePrivate(identity.getPrivate())),
                Base64.getEncoder().encodeToString(X25519.encodePublic(identity.getPublic())));
        writeAtomically(file, GSON.toJson(stored));
        return identity;
    }

    private static void writeAtomically(Path destination, String contents) {
        try {
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temporary, contents, StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + destination, e);
        }
    }

    private record StoredIdentity(String privateKey, String publicKey) {
    }
}
