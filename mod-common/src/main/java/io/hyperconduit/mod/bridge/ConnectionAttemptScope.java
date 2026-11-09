package io.hyperconduit.mod.bridge;

import io.hyperconduit.mod.config.HyperConduitConfig;
import java.util.Objects;

/** A single connector owns its configuration snapshot; global settings are never changed. */
public final class ConnectionAttemptScope implements AutoCloseable {
    private static final ThreadLocal<HyperConduitConfig> CURRENT = new ThreadLocal<>();
    private final HyperConduitConfig previous;

    private ConnectionAttemptScope(HyperConduitConfig config) {
        previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(config, "config").copy());
    }

    public static ConnectionAttemptScope enter(HyperConduitConfig config) {
        return new ConnectionAttemptScope(config);
    }

    public static HyperConduitConfig snapshot() {
        HyperConduitConfig config = CURRENT.get();
        return config == null ? null : config.copy();
    }

    public static Thread startConnector(Thread original) {
        HyperConduitConfig config = snapshot();
        if (config == null) {
            original.start();
            return original;
        }
        Thread worker = new Thread(() -> {
            try (ConnectionAttemptScope ignored = enter(config)) {
                original.run();
            }
        }, original.getName());
        worker.setDaemon(original.isDaemon());
        worker.setUncaughtExceptionHandler(original.getUncaughtExceptionHandler());
        worker.start();
        return worker;
    }

    @Override public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
