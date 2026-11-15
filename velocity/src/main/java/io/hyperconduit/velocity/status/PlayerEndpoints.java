package io.hyperconduit.velocity.status;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.player.User;
import com.velocitypowered.api.proxy.Player;
import io.netty.channel.Channel;

import java.net.InetSocketAddress;

/**
 * Reads the real TCP peer from PacketEvents' low-level channel.
 *
 * <p>Velocity keeps the PROXY-protocol source address in its own
 * {@code MinecraftConnection} field, while the Netty channel still exposes the
 * actual accepted socket. Using the channel is what makes an exact match
 * against the sidecar's reported local endpoint possible even when
 * {@code haproxy-protocol} is enabled.</p>
 *
 * <p>Any PacketEvents absence, version mismatch or unexpected channel type
 * degrades to {@code null}; callers then show an unknown association instead of
 * guessing from a public IP.</p>
 */
public final class PlayerEndpoints {

    private PlayerEndpoints() {
    }

    /** Returns the real accepted TCP peer, or {@code null} when unavailable. */
    public static InetSocketAddress realTcpPeer(Player player) {
        if (player == null) {
            return null;
        }
        try {
            PacketEventsAPI<?> api = PacketEvents.getAPI();
            if (api == null || !api.isInitialized()) {
                return null;
            }
            User user = api.getPlayerManager().getUser(player);
            if (user == null) {
                return null;
            }
            Object channel = user.getChannel();
            if (channel instanceof Channel netty
                    && netty.remoteAddress() instanceof InetSocketAddress address) {
                return address;
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // PacketEvents is optional at runtime for status rendering. The
            // command must keep working and report an unknown association.
        }
        return null;
    }
}