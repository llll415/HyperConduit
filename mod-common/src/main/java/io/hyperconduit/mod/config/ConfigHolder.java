package io.hyperconduit.mod.config;

import io.hyperconduit.conn.IdentityChangedException;
import io.hyperconduit.conn.PeerIdentityVerifier;
import io.hyperconduit.mod.identity.KnownServerStore;
import io.hyperconduit.mod.identity.ServerIdentityStore;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.security.KeyPair;

/** Shared runtime configuration and private identity/trust state for loader-neutral mixins. */
public final class ConfigHolder {

    private static volatile HyperConduitConfig config;
    private static volatile Path configDir;
    private static volatile KeyPair serverIdentity;
    private static volatile KnownServerStore knownServers;

    private ConfigHolder() {
    }

    public static void install(HyperConduitConfig config) {
        ConfigHolder.config = config;
    }

    public static void install(HyperConduitConfig config, Path configDir) {
        ConfigHolder.config = config;
        ConfigHolder.configDir = configDir;
        serverIdentity = null;
        knownServers = KnownServerStore.load(configDir);
    }

    public static void save(HyperConduitConfig config) {
        Path directory = configDir;
        if (directory == null) {
            throw new IllegalStateException("HyperConduit config directory is not installed");
        }
        config.save(directory);
        install(config);
    }

    public static synchronized KeyPair serverIdentity() {
        KeyPair identity = serverIdentity;
        if (identity == null) {
            Path directory = configDir;
            if (directory == null) {
                throw new IllegalStateException("HyperConduit config directory is not installed");
            }
            identity = ServerIdentityStore.loadOrCreate(directory);
            serverIdentity = identity;
        }
        return identity;
    }

    public static PeerIdentityVerifier verifierFor(String host, int port) {
        KnownServerStore store = knownServers;
        if (store == null) {
            throw new IllegalStateException("HyperConduit known-server store is not installed");
        }
        return publicKey -> {
            var status = store.check(host, port, publicKey);
            if (status == KnownServerStore.IdentityStatus.UNKNOWN) {
                store.trustFirstUse(host, port, publicKey);
                return true;
            }
            if (status == KnownServerStore.IdentityStatus.CHANGED) {
                throw new IdentityChangedException(KnownServerStore.endpoint(host, port),
                        store.knownFingerprint(host, port), KnownServerStore.fingerprint(publicKey), publicKey);
            }
            return true;
        };
    }

    public static KnownServerStore knownServers() {
        KnownServerStore store = knownServers;
        if (store == null) {
            throw new IllegalStateException("HyperConduit known-server store is not installed");
        }
        return store;
    }

    @Nullable
    public static HyperConduitConfig get() {
        return config;
    }
}
