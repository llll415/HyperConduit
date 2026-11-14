package io.hyperconduit.mod.probe;

import io.hyperconduit.mod.bridge.ServerTunnelController;

import java.util.List;

/** Discovery reflects the running listener, not settings awaiting the next restart. */
public interface TunnelListenerState {
    boolean hyperconduit$acceptsTunnels();

    /** Listener policy snapshot; refreshed only after a replacement sidecar reaches READY. */
    CapabilityDetails hyperconduit$declaration();

    /**
     * Snapshot of the controllers owned by the live listeners. Each bind (IPv4, IPv6, disabled or
     * tunnel-only) has its own controller, so a command can restart or inspect them individually
     * without assuming a single global listener.
     */
    default List<ServerTunnelController> hyperconduit$controllers() {
        return List.of();
    }
}
