package io.hyperconduit.mod.probe;

/** Discovery reflects the running listener, not settings awaiting the next restart. */
public interface TunnelListenerState {
    boolean hyperconduit$acceptsTunnels();
}
