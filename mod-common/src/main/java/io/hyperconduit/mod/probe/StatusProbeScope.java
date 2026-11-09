package io.hyperconduit.mod.probe;

/** Scoped on the vanilla ping worker; never carried over to a login connection. */
public final class StatusProbeScope implements AutoCloseable {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();
    private final Boolean previous;
    private StatusProbeScope() { previous = ACTIVE.get(); ACTIVE.set(true); }
    public static StatusProbeScope enter() { return new StatusProbeScope(); }
    public static boolean active() { return Boolean.TRUE.equals(ACTIVE.get()); }
    @Override public void close() { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
}
