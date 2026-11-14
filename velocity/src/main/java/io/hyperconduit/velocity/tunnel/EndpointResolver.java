package io.hyperconduit.velocity.tunnel;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/**
 * Resolves the tunnel endpoints from Velocity's bound Minecraft listener.
 *
 * <p>The TCP target is Velocity's listener. A wildcard bind is converted to the matching loopback
 * address ({@code 0.0.0.0 -> 127.0.0.1}, {@code :: -> ::1}) so the sidecar never connects to the
 * wildcard address itself. The UDP listen address keeps the same address and port as Velocity's
 * TCP listener, because HyperConduit uses the game port over UDP.</p>
 */
public final class EndpointResolver {

    /** The sidecar's UDP listen address and the loopback TCP target it forwards to. */
    public record Endpoint(InetSocketAddress listen, InetSocketAddress target) {
        public Endpoint {
            if (listen == null || target == null) {
                throw new IllegalArgumentException("Endpoint addresses must not be null");
            }
            if (listen.getPort() < 1 || listen.getPort() > 65535) {
                throw new IllegalArgumentException("Listen port must be between 1 and 65535");
            }
            if (target.getPort() < 1 || target.getPort() > 65535) {
                throw new IllegalArgumentException("Target port must be between 1 and 65535");
            }
        }
    }

    private EndpointResolver() {
    }

    /**
     * Resolves the endpoints, or returns empty when Velocity has not exposed a usable bound
     * address. No DNS lookup is performed: Velocity's bound address is already resolved.
     */
    public static Optional<Endpoint> resolve(InetSocketAddress bound) {
        if (bound == null || bound.getPort() < 1 || bound.getPort() > 65535) {
            return Optional.empty();
        }
        InetAddress address = bound.getAddress();
        if (address == null) {
            return Optional.empty();
        }
        InetAddress target = address.isAnyLocalAddress() ? loopback(address) : address;
        return Optional.of(new Endpoint(bound, new InetSocketAddress(target, bound.getPort())));
    }

    private static InetAddress loopback(InetAddress wildcard) {
        try {
            if (wildcard instanceof Inet6Address) {
                return InetAddress.getByAddress(new byte[] {
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
                });
            }
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException impossible) {
            // A literal four/sixteen-byte address is always valid.
            throw new IllegalStateException("loopback_unavailable", impossible);
        }
    }

    /** Formats an address as a sidecar {@code host:port}, bracketing IPv6 literals. */
    public static String format(InetSocketAddress address) {
        InetAddress value = address.getAddress();
        String host = value == null ? address.getHostString() : value.getHostAddress();
        int zone = host.indexOf('%');
        if (zone >= 0) {
            host = host.substring(0, zone);
        }
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + address.getPort();
    }
}
