package io.hyperconduit.neoforge.watch;

/**
 * Pure snapshot of the player state that determines whether a watch subscription is still valid.
 */
public record WatchTargetState(boolean acceptingMessages, boolean permitted, boolean currentConnection) {

    public static final WatchTargetState HEALTHY = new WatchTargetState(true, true, true);

    public boolean isHealthy() {
        return this.acceptingMessages && this.permitted && this.currentConnection;
    }
}
