package io.hyperconduit.velocity.watch;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.hyperconduit.velocity.command.HyperConduitCommand;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * One-second status subscriptions.
 *
 * <p>Subscriptions are de-duplicated by player UUID or the console key. Every
 * tick removes players who disconnected, lost {@code hyperconduit.admin}, or
 * exist while the proxy is shutting down. A failed send removes only that
 * subscription. Proxy shutdown cancels the task and clears all targets.</p>
 */
public final class WatchService implements AutoCloseable {

    /** Narrow seam so the cleanup and de-duplication logic is testable. */
    interface Target {
        boolean valid();

        void send(String line);
    }

    private final ProxyServer server;
    private final Object plugin;
    private final Supplier<List<String>> lines;
    private final WatchRegistry<Target> registry = new WatchRegistry<>();
    private final AtomicBoolean ticking = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile ScheduledTask task;

    public WatchService(ProxyServer server, Object plugin, Supplier<List<String>> lines) {
        this.server = server;
        this.plugin = plugin;
        this.lines = Objects.requireNonNull(lines, "lines");
    }

    /** Starts the one-second task; repeated calls are ignored. */
    public void start() {
        if (server == null || task != null || closed.get()) {
            return;
        }
        task = server.getScheduler()
                .buildTask(plugin, this::tick)
                .repeat(Duration.ofSeconds(1))
                .schedule();
    }

    public boolean add(CommandSource source) {
        Objects.requireNonNull(source, "source");
        if (closed.get()) {
            return false;
        }
        return add(keyOf(source), target(source));
    }

    public boolean remove(CommandSource source) {
        Objects.requireNonNull(source, "source");
        return registry.remove(keyOf(source));
    }

    boolean add(WatchRegistry.Key key, Target target) {
        return registry.put(key, target);
    }

    boolean remove(WatchRegistry.Key key) {
        return registry.remove(key);
    }

    int size() {
        return registry.size();
    }

    void tick() {
        if (closed.get() || !ticking.compareAndSet(false, true)) {
            return;
        }
        try {
            if (closed.get()) {
                return;
            }
            if (server != null && server.isShuttingDown()) {
                registry.clear();
                return;
            }
            registry.removeIf(target -> {
                try {
                    return !target.valid();
                } catch (RuntimeException unavailable) {
                    // A broken permission/player lookup must not leave a
                    // subscription that can never be validated again.
                    return true;
                }
            });
            if (registry.isEmpty()) {
                return;
            }
            List<String> snapshot;
            try {
                snapshot = lines.get();
            } catch (RuntimeException unavailable) {
                // Status collection failure must not cancel subscriptions.
                return;
            }
            if (snapshot == null || snapshot.isEmpty()) {
                return;
            }
            for (Target target : registry.snapshot()) {
                try {
                    for (String line : snapshot) {
                        target.send(line);
                    }
                } catch (RuntimeException failedSend) {
                    registry.removeValue(target);
                }
            }
        } finally {
            ticking.set(false);
        }
    }

    @Override
    public void close() {
        closed.set(true);
        ScheduledTask current = task;
        task = null;
        if (current != null) {
            current.cancel();
        }
        registry.clear();
    }

    private Target target(CommandSource source) {
        return new Target() {
            @Override
            public boolean valid() {
                return isValid(source);
            }

            @Override
            public void send(String line) {
                source.sendPlainMessage(line);
            }
        };
    }

    private boolean isValid(CommandSource source) {
        if (server == null) {
            return true;
        }
        if (server.isShuttingDown()) {
            return false;
        }
        if (source instanceof ConsoleCommandSource) {
            return true;
        }
        if (!source.hasPermission(HyperConduitCommand.PERMISSION)) {
            return false;
        }
        if (source instanceof Player player) {
            return player.isActive()
                    && server.getPlayer(player.getUniqueId()).isPresent();
        }
        return true;
    }

    static WatchRegistry.Key keyOf(CommandSource source) {
        if (source instanceof Player player) {
            return WatchRegistry.Key.player(player.getUniqueId());
        }
        if (source instanceof ConsoleCommandSource) {
            return WatchRegistry.Key.consoleKey();
        }
        return WatchRegistry.Key.other(System.identityHashCode(source));
    }
}