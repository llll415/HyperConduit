package io.hyperconduit.neoforge.watch;

/**
 * Pure cleanup policy for watch subscriptions.
 *
 * <p>Player subscriptions are dropped when the connection stops accepting messages, permission
 * level 2 is lost, or the UUID now resolves to a different {@code ServerPlayer} (a reconnect left
 * the old stack behind). Non-player subscriptions only drop when their command source is known to
 * be invalid.</p>
 */
public final class WatchCleanup {

    private WatchCleanup() {
    }

    public static boolean shouldDrop(WatchKey key, WatchTargetState playerState, boolean sourceInvalid) {
        if (!key.isPlayer()) return sourceInvalid;
        return playerState == null || !playerState.isHealthy();
    }
}
