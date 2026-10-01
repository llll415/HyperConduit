package io.hyperconduit.mod.identity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.hyperconduit.crypto.X25519;

import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Client TOFU database keyed by normalized Minecraft server address. */
public final class KnownServerStore {

    public enum IdentityStatus { UNKNOWN, MATCH, CHANGED }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE = "hyperconduit-known-servers.json";
    private final Path file;
    private final Map<String, String> fingerprints;

    private KnownServerStore(Path file, Map<String, String> fingerprints) {
        this.file = file;
        this.fingerprints = fingerprints;
    }

    public static KnownServerStore load(Path configDir) {
        Path file = configDir.resolve(FILE);
        if (!Files.exists(file)) {
            return new KnownServerStore(file, new LinkedHashMap<>());
        }
        try {
            StoredKnownServers stored = GSON.fromJson(Files.readString(file), StoredKnownServers.class);
            return new KnownServerStore(file, stored == null || stored.fingerprints == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(stored.fingerprints));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    /** Checks a server identity without changing persistent trust. */
    public synchronized IdentityStatus check(String host, int port, byte[] publicKey) {
        String known = fingerprints.get(endpoint(host, port));
        if (known == null) return IdentityStatus.UNKNOWN;
        String candidate = fingerprint(publicKey);
        return MessageDigest.isEqual(known.getBytes(StandardCharsets.US_ASCII),
                candidate.getBytes(StandardCharsets.US_ASCII)) ? IdentityStatus.MATCH : IdentityStatus.CHANGED;
    }

    /** Stores a first-use identity. Existing trust is never silently overwritten. */
    public synchronized void trustFirstUse(String host, int port, byte[] publicKey) {
        fingerprints.putIfAbsent(endpoint(host, port), fingerprint(publicKey));
        save();
    }

    /** First use is trusted and stored; a changed identity is rejected. */
    public synchronized boolean trustOrVerify(String host, int port, byte[] publicKey) {
        IdentityStatus status = check(host, port, publicKey);
        if (status == IdentityStatus.UNKNOWN) {
            trustFirstUse(host, port, publicKey);
            return true;
        }
        return status == IdentityStatus.MATCH;
    }

    public synchronized String knownFingerprint(String host, int port) {
        return fingerprints.get(endpoint(host, port));
    }

    public synchronized void replaceTrust(String host, int port, byte[] publicKey) {
        fingerprints.put(endpoint(host, port), fingerprint(publicKey));
        save();
    }

    public synchronized void remove(String host, int port) {
        if (fingerprints.remove(endpoint(host, port)) != null) save();
    }

    public synchronized Map<String, String> entries() {
        return Map.copyOf(fingerprints);
    }

    public static String endpoint(String host, int port) {
        String normalized = host == null ? "" : host.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        normalized = normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
        if (normalized.contains(":") || normalized.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
            try {
                normalized = InetAddress.getByName(normalized).getHostAddress();
            } catch (Exception e) {
                throw new IllegalArgumentException("invalid literal server address: " + host, e);
            }
        } else {
            normalized = IDN.toASCII(normalized, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        }
        return normalized.contains(":") ? "[" + normalized + "]:" + port : normalized + ":" + port;
    }

    public static String fingerprint(byte[] publicKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temporary, GSON.toJson(new StoredKnownServers(fingerprints)), StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + file, e);
        }
    }

    private record StoredKnownServers(Map<String, String> fingerprints) {
    }
}
