package io.hyperconduit.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import io.hyperconduit.conn.SessionConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Runtime configuration for the HyperConduit mod, loaded from
 * {@code config/hyperconduit.json} (or the server directory equivalent).
 *
 * <p>Three fields carry the user's three product decisions:
 *
 * <ul>
 *   <li>{@code enabled} — when false, the mod is a no-op: Minecraft connects to vanilla servers
 *       over ordinary TCP exactly as if the mod were not installed. The mixins check this first
 *       and return without touching anything.</li>
 *   <li>{@code mbps} — Brutal's target send rate per direction, in megabits per second. The
 *       default is 10, per the user's decision; users set it to their own subscribed bandwidth,
 *       not to a measured path speed.</li>
 *   <li>{@code brutal} — when false the transport still uses its paced reliable-UDP stack, but
 *       with loss compensation disabled (plain fixed-rate pacing, no window held against loss).
 *       This is the user's escape hatch if Brutal ever behaves badly on a given link.</li>
 * </ul>
 *
 * <p>The file is written back with defaults on first load, so users get a commented example to edit.
 */
public final class HyperConduitConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "hyperconduit.json";

    /**
     * Whether the tunnel is active at all.
     *
     * <p>Defaults to <b>false</b> on purpose. An unconfigured install must not break ordinary
     * multiplayer: with the tunnel enabled but the PSK still at its placeholder value, every
     * connection would attempt a handshake that cannot succeed. Off by default means installing the
     * mod changes nothing until the user deliberately turns it on, which is also what makes
     * "disabled = vanilla" true rather than aspirational.
     */
    public boolean enabled = false;

    /** Brutal target rate per direction, Mbps. Default 10 per the user's decision. */
    public int mbps = 10;

    /** Whether to use Brutal's loss-compensating congestion control. */
    public boolean brutal = true;

    /**
     * When true, the server accepts only HyperConduit UDP sessions and does not open vanilla TCP.
     * Defaults to false so installing the mod does not exclude unmodded players.
     */
    public boolean disableVanillaTcp = false;

    /**
     * Client side: the tunnel endpoint to connect to, in {@code host:port} form.
     *
     * <p>Leave blank to tunnel to whatever address the player picked in the multiplayer list, so
     * the tunnel address is configured only once (in the server list) rather than twice. Set it to
     * force every connection through one particular tunnel regardless of the server list entry.
     */
    public String tunnelServer = "";

    /**
     * Server side: the address the tunnel listener binds to, in {@code host:port} form.
     *
     * <p>The port must be UDP and reachable from clients. Use a non-obvious port: plain UDP on
     * well-known ports is commonly filtered on international links, and a high port is more likely
     * to pass.
     */
    public String listenAddress = "0.0.0.0:38912";

    /**
     * Shared secret for the tunnel handshake. Both ends must match. Generate one with
     * {@link #generatePsk()} — it is an access-control gate rather than an encryption key, so it
     * does not need to be high-entropy, but it must be kept private.
     */
    public String psk = "change-me";

    /** Receive window in bytes; raise this on high-BDP links. */
    public int receiveWindowBytes = 4 * 1024 * 1024;

    /** Send buffer high-water mark in bytes; MC's channel backpressure honours this. */
    public int sendBufferBytes = 4 * 1024 * 1024;

    /** A parsed {@code host:port} pair. */
    public record TunnelAddress(String host, int port) {
    }

    /** Parses {@link #tunnelServer}; used by the client mixin when {@link #hasExplicitTunnelServer()} is true. */
    public TunnelAddress parseTunnelServerAddress() {
        return parseHostPort(tunnelServer, "tunnelServer");
    }

    /** True when the user configured an explicit tunnel endpoint rather than using the server list address. */
    public boolean hasExplicitTunnelServer() {
        return tunnelServer != null && !tunnelServer.isBlank();
    }

    /** Parses {@link #listenAddress}; used by the server mixin. */
    public TunnelAddress parseListenAddress() {
        return parseHostPort(listenAddress, "listenAddress");
    }

    private static TunnelAddress parseHostPort(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be host:port, but was blank");
        }
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            throw new IllegalArgumentException(fieldName + " must be host:port, got: " + value);
        }
        return new TunnelAddress(value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
    }

    /** Builds the client-side SessionConfig from this file. */
    public SessionConfig clientSessionConfig() {
        return SessionConfig.client(psk.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .congestionController(brutal
                        ? SessionConfig.brutal((long) mbps * 1_000_000 / 8)
                        : SessionConfig.brutal((long) mbps * 1_000_000 / 8, true))
                .receiveWindowBytes(receiveWindowBytes)
                .sendBufferBytes(sendBufferBytes);
    }

    /** Builds the server-side SessionConfig from this file. */
    public SessionConfig serverSessionConfig() {
        return SessionConfig.server(psk.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .congestionController(brutal
                        ? SessionConfig.brutal((long) mbps * 1_000_000 / 8)
                        : SessionConfig.brutal((long) mbps * 1_000_000 / 8, true))
                .receiveWindowBytes(receiveWindowBytes)
                .sendBufferBytes(sendBufferBytes);
    }

    /** A random 16-byte hex PSK, for users who want to generate one. */
    public static String generatePsk() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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
        // First run: write defaults so the user has a commented example to edit.
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
        return "HyperConduitConfig{enabled=" + enabled
                + ", mbps=" + mbps
                + ", brutal=" + brutal
                + ", disableVanillaTcp=" + disableVanillaTcp
                + ", tunnelServer=" + tunnelServer
                + ", listenAddress=" + listenAddress
                + ", receiveWindowBytes=" + receiveWindowBytes
                + ", sendBufferBytes=" + sendBufferBytes + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HyperConduitConfig that)) {
            return false;
        }
        return enabled == that.enabled && mbps == that.mbps && brutal == that.brutal
                && disableVanillaTcp == that.disableVanillaTcp
                && Objects.equals(tunnelServer, that.tunnelServer)
                && Objects.equals(listenAddress, that.listenAddress)
                && Objects.equals(psk, that.psk)
                && receiveWindowBytes == that.receiveWindowBytes
                && sendBufferBytes == that.sendBufferBytes;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, mbps, brutal, disableVanillaTcp, tunnelServer, listenAddress,
                psk, receiveWindowBytes, sendBufferBytes);
    }
}
