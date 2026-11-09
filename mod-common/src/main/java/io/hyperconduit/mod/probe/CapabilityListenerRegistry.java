package io.hyperconduit.mod.probe;

import io.netty.channel.ChannelFuture;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Per-listener facts for the server capability declaration. Each bind (IPv4, IPv6, disabled or
 * tunnel-only) owns one entry; closing that public channel removes only its own entry. The
 * aggregate never reads config at status time and never lets a Unix-domain bind overwrite an
 * existing TCP listener.
 *
 * <p>Multiple active listeners with the same policy aggregate as enabled-any / tcpAllowed-any.
 * If active sidecars disagree on {@code disableBrutal} or {@code tokenRequired}, the declaration
 * is conservatively {@link Capability#UNKNOWN} instead of reporting one listener's policy.
 */
public final class CapabilityListenerRegistry {

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Registers one listener. {@code publicTcp} may be null for a tunnel-only listener. A bind
     * future that fails or closes removes this entry; it never contributes tcpAllowed.
     */
    public Handle add(ChannelFuture publicTcp, boolean disableBrutal, boolean tokenRequired) {
        Listener listener = new Listener(disableBrutal, tokenRequired);
        listeners.add(listener);
        if (publicTcp != null) listener.watch(publicTcp);
        return listener;
    }

    public boolean anySidecarAlive() {
        for (Listener listener : listeners) {
            if (listener.active && listener.isSidecarAlive()) return true;
        }
        return false;
    }

    public CapabilityDetails declaration() {
        boolean enabled = false;
        boolean tcpAllowed = false;
        Boolean disableBrutal = null;
        Boolean tokenRequired = null;
        boolean mixedPolicy = false;
        for (Listener listener : listeners) {
            if (!listener.active) continue;
            if (listener.isPublicTcpAlive()) tcpAllowed = true;
            if (!listener.isSidecarAlive()) continue;
            enabled = true;
            if (disableBrutal == null) disableBrutal = listener.disableBrutal;
            else if (disableBrutal != listener.disableBrutal) mixedPolicy = true;
            if (tokenRequired == null) tokenRequired = listener.tokenRequired;
            else if (tokenRequired != listener.tokenRequired) mixedPolicy = true;
        }
        if (mixedPolicy) return CapabilityDetails.UNKNOWN;
        if (!enabled) return CapabilityDetails.declaration(false, tcpAllowed, false, false);
        return CapabilityDetails.declaration(true, tcpAllowed, disableBrutal, tokenRequired);
    }

    /** Removes every entry; used by the listener stop hook. */
    public void clear() {
        for (Listener listener : listeners) listener.active = false;
        listeners.clear();
    }

    public interface Handle {
        void sidecarAlive(boolean alive);

        void observeSidecar(BooleanSupplier alive);

        void remove();
    }

    private final class Listener implements Handle {
        private final boolean disableBrutal;
        private final boolean tokenRequired;
        private volatile boolean sidecarAlive;
        private volatile BooleanSupplier sidecarProbe;
        private volatile boolean publicTcpAlive;
        private volatile ChannelFuture publicBind;
        private volatile boolean active = true;

        private Listener(boolean disableBrutal, boolean tokenRequired) {
            this.disableBrutal = disableBrutal;
            this.tokenRequired = tokenRequired;
        }

        private void watch(ChannelFuture bound) {
            publicBind = bound;
            bound.addListener(future -> {
                if (!active) return;
                if (!future.isSuccess()) {
                    remove();
                    return;
                }
                publicTcpAlive = true;
                bound.channel().closeFuture().addListener(closed -> {
                    publicTcpAlive = false;
                    remove();
                });
            });
        }

        private boolean isPublicTcpAlive() {
            ChannelFuture bound = publicBind;
            return publicTcpAlive && bound != null && bound.isSuccess() && bound.channel().isActive();
        }

        @Override public void sidecarAlive(boolean alive) {
            sidecarAlive = alive;
            // A tunnel-only listener has no public channel to close; once its sidecar
            // is gone it contributes nothing and can be forgotten.
            if (!alive && !publicTcpAlive) remove();
        }

        @Override public void observeSidecar(BooleanSupplier alive) {
            sidecarProbe = java.util.Objects.requireNonNull(alive);
            sidecarAlive(alive.getAsBoolean());
        }

        private boolean isSidecarAlive() {
            BooleanSupplier probe = sidecarProbe;
            return sidecarAlive && (probe == null || probe.getAsBoolean());
        }

        @Override public void remove() {
            active = false;
            publicTcpAlive = false;
            listeners.remove(this);
        }
    }
}
