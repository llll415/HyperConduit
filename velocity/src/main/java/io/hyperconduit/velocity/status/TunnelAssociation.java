package io.hyperconduit.velocity.status;

import io.hyperconduit.sidecar.TelemetrySnapshot;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Exact player-to-tunnel association.
 *
 * <p>The sidecar reports the local TCP endpoint it used to reach Velocity. The
 * proxy sees that same endpoint as the accepted socket peer. Matching the full
 * address and port is unique per live TCP connection; matching only an IP would
 * be a guess and is deliberately not implemented.</p>
 */
public final class TunnelAssociation {

    private TunnelAssociation() {
    }

    /** A matched bridge and whether the primary real-TCP peer was used. */
    public record Match(TelemetrySnapshot.Bridge bridge, boolean viaFallback) {
    }

    /**
     * Returns only players that map to one unique live bridge. Unmatched players
     * are absent from the result so callers render them as unknown instead of
     * inventing a transport type.
     */
    public static Map<UUID, Match> associate(List<PlayerView> players,
                                             TelemetrySnapshot snapshot) {
        return associate(players, snapshot, true);
    }

    /**
     * @param allowLogicalFallback when false, only the PacketEvents channel
     *        peer is considered. Callers disable it when Proxy Protocol v2
     *        output may have replaced Velocity's logical address with the
     *        original client address.
     */
    public static Map<UUID, Match> associate(List<PlayerView> players,
                                             TelemetrySnapshot snapshot,
                                             boolean allowLogicalFallback) {
        Map<UUID, Match> matches = new LinkedHashMap<>();
        if (players == null || snapshot == null) {
            return matches;
        }
        Set<TelemetrySnapshot.Bridge> used = new HashSet<>();
        for (PlayerView player : players) {
            if (player == null) {
                continue;
            }
            TelemetrySnapshot.Bridge bridge = snapshot.bridgeFor(player.primaryEndpoint());
            boolean fallback = false;
            if (bridge == null && allowLogicalFallback) {
                bridge = snapshot.bridgeFor(player.fallbackEndpoint());
                fallback = true;
            }
            if (bridge == null || !used.add(bridge)) {
                continue;
            }
            matches.put(player.uuid(), new Match(bridge, fallback));
        }
        return matches;
    }
}