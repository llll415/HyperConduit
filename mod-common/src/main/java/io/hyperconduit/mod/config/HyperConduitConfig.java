package io.hyperconduit.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import io.hyperconduit.conn.PeerIdentityVerifier;
import io.hyperconduit.conn.SessionConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** User preferences only. Server identity and known-server trust live in separate private stores. */
public final class HyperConduitConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "hyperconduit.json";

    /** Disabled by default so an unconfigured installation remains vanilla-compatible. */
    public boolean enabled = false;
    /** Target rate per direction in Mbps. */
    public int mbps = 10;
    /** Enables Brutal's loss compensation. */
    public boolean brutal = true;
    /** When true, the server accepts only HyperConduit UDP sessions. */
    public boolean disableVanillaTcp = false;
    public int receiveWindowBytes = 4 * 1024 * 1024;
    public int sendBufferBytes = 4 * 1024 * 1024;

    public SessionConfig clientSessionConfig(PeerIdentityVerifier identityVerifier) {
        return SessionConfig.client()
                .peerIdentityVerifier(identityVerifier)
                .congestionController(controller())
                .receiveWindowBytes(receiveWindowBytes)
                .sendBufferBytes(sendBufferBytes);
    }

    public SessionConfig serverSessionConfig(java.security.KeyPair identity) {
        return SessionConfig.server()
                .staticKey(identity)
                .congestionController(controller())
                .receiveWindowBytes(receiveWindowBytes)
                .sendBufferBytes(sendBufferBytes);
    }

    private java.util.function.Supplier<io.hyperconduit.cc.CongestionController> controller() {
        long bytesPerSecond = (long) mbps * 1_000_000 / 8;
        return brutal ? SessionConfig.brutal(bytesPerSecond) : SessionConfig.brutal(bytesPerSecond, true);
    }

    public static HyperConduitConfig load(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                HyperConduitConfig config = GSON.fromJson(reader, HyperConduitConfig.class);
                return config == null ? new HyperConduitConfig() : config;
            } catch (IOException | JsonSyntaxException e) {
                throw new IllegalStateException("cannot read " + file, e);
            }
        }
        HyperConduitConfig defaults = new HyperConduitConfig();
        defaults.save(configDir);
        return defaults;
    }

    public void save(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        try {
            Files.createDirectories(configDir);
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + file, e);
        }
    }

    @Override
    public String toString() {
        return "HyperConduitConfig{enabled=" + enabled + ", mbps=" + mbps + ", brutal=" + brutal
                + ", disableVanillaTcp=" + disableVanillaTcp + ", receiveWindowBytes=" + receiveWindowBytes
                + ", sendBufferBytes=" + sendBufferBytes + '}';
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HyperConduitConfig that)) return false;
        return enabled == that.enabled && mbps == that.mbps && brutal == that.brutal
                && disableVanillaTcp == that.disableVanillaTcp
                && receiveWindowBytes == that.receiveWindowBytes && sendBufferBytes == that.sendBufferBytes;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, mbps, brutal, disableVanillaTcp, receiveWindowBytes, sendBufferBytes);
    }
}
