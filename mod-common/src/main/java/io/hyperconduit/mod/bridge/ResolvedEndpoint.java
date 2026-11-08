package io.hyperconduit.mod.bridge;

import java.net.InetSocketAddress;

/** Carries the user-selected endpoint across resolution on the same connector thread. */
public final class ResolvedEndpoint {
    private ResolvedEndpoint() {}
    private record Value(String endpoint, InetSocketAddress target) {}
    private static final ThreadLocal<Value> CURRENT = new ThreadLocal<>();

    public static void record(String endpoint, InetSocketAddress target) {
        if (target == null) CURRENT.remove();
        else CURRENT.set(new Value(endpoint, target));
    }

    public static String consume(InetSocketAddress target) {
        Value value = CURRENT.get();
        CURRENT.remove();
        return value != null && value.target().equals(target) ? value.endpoint() : null;
    }
}
