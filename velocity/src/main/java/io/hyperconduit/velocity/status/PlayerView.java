package io.hyperconduit.velocity.status;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable player data used for status rendering and tunnel association.
 *
 * <p>{@code primaryEndpoint} is the real TCP peer taken from PacketEvents'
 * low-level Netty channel. {@code fallbackEndpoint} is Velocity's logical
 * remote address; it is used only for an exact endpoint match, never as an IP
 * guess.</p>
 */
public record PlayerView(
        UUID uuid,
        String name,
        InetSocketAddress primaryEndpoint,
        InetSocketAddress fallbackEndpoint) {

    public PlayerView {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
    }
}