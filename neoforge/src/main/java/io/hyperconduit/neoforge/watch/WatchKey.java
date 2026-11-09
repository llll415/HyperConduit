package io.hyperconduit.neoforge.watch;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity for a {@code /hyperconduit watch} subscription.
 *
 * <p>Minecraft creates a fresh {@link net.minecraft.commands.CommandSourceStack} for every
 * command invocation, so the stack itself must never be used as a set key. Players are keyed by
 * UUID (which survives a reconnect); every non-player source is keyed by the identity of the
 * underlying {@code CommandSource} (console server, RCON source, command-block source, ...).</p>
 */
public final class WatchKey {

    private enum Kind {
        PLAYER,
        SOURCE
    }

    private final Kind kind;
    private final UUID playerId;
    private final Object source;

    private WatchKey(Kind kind, UUID playerId, Object source) {
        this.kind = kind;
        this.playerId = playerId;
        this.source = source;
    }

    public static WatchKey player(UUID playerId) {
        return new WatchKey(Kind.PLAYER, Objects.requireNonNull(playerId, "playerId"), null);
    }

    public static WatchKey source(Object source) {
        return new WatchKey(Kind.SOURCE, null, Objects.requireNonNull(source, "source"));
    }

    public boolean isPlayer() {
        return this.kind == Kind.PLAYER;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public Object source() {
        return this.source;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof WatchKey key)) return false;
        if (this.kind != key.kind) return false;
        return this.kind == Kind.PLAYER
                ? this.playerId.equals(key.playerId)
                : this.source == key.source;
    }

    @Override
    public int hashCode() {
        int result = this.kind.hashCode();
        return 31 * result + (this.kind == Kind.PLAYER
                ? this.playerId.hashCode()
                : System.identityHashCode(this.source));
    }

    @Override
    public String toString() {
        if (this.kind == Kind.PLAYER) {
            return "WatchKey[player=" + this.playerId + "]";
        }
        return "WatchKey[source=" + this.source.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(this.source)) + "]";
    }
}
