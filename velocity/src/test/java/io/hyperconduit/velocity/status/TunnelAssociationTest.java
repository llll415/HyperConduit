package io.hyperconduit.velocity.status;

import io.hyperconduit.sidecar.TelemetrySnapshot;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunnelAssociationTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void matchesTheRealTcpPeerBeforeTheFallback() throws Exception {
        TelemetrySnapshot.Bridge bridge = bridge(7, 0, "127.0.0.1", 40001,
                "203.0.113.5", 5555);
        TelemetrySnapshot snapshot = snapshot(bridge);
        PlayerView alice = new PlayerView(ALICE, "Alice",
                address("127.0.0.1", 40001), address("198.51.100.7", 60000));

        Map<UUID, TunnelAssociation.Match> matches = TunnelAssociation.associate(
                List.of(alice), snapshot);

        assertNotNull(matches.get(ALICE));
        assertFalse(matches.get(ALICE).viaFallback());
        assertEquals("203.0.113.5:5555", matches.get(ALICE).bridge().peerAddress());
    }

    @Test
    void fallsBackOnlyForAnExactEndpointMatch() throws Exception {
        TelemetrySnapshot.Bridge bridge = bridge(7, 0, "127.0.0.1", 40001,
                "203.0.113.5", 5555);
        TelemetrySnapshot snapshot = snapshot(bridge);
        PlayerView alice = new PlayerView(ALICE, "Alice", null, address("127.0.0.1", 40001));

        Map<UUID, TunnelAssociation.Match> matches = TunnelAssociation.associate(
                List.of(alice), snapshot);

        assertNotNull(matches.get(ALICE));
        assertTrue(matches.get(ALICE).viaFallback());
    }

    @Test
    void neverMatchesOnlyByIpAddress() throws Exception {
        TelemetrySnapshot.Bridge bridge = bridge(7, 0, "127.0.0.1", 40001,
                "203.0.113.5", 5555);
        TelemetrySnapshot snapshot = snapshot(bridge);
        // Same IP, different port: not the same TCP connection.
        PlayerView alice = new PlayerView(ALICE, "Alice",
                address("127.0.0.1", 40002), address("127.0.0.1", 40002));
        // A public client IP is not the sidecar's local TCP endpoint.
        PlayerView bob = new PlayerView(BOB, "Bob",
                address("203.0.113.5", 5555), null);

        Map<UUID, TunnelAssociation.Match> matches = TunnelAssociation.associate(
                List.of(alice, bob), snapshot);

        assertTrue(matches.isEmpty());
    }

    @Test
    void doesNotReuseOneBridgeForTwoPlayers() throws Exception {
        TelemetrySnapshot.Bridge bridge = bridge(7, 0, "127.0.0.1", 40001,
                "203.0.113.5", 5555);
        TelemetrySnapshot snapshot = snapshot(bridge);
        PlayerView alice = new PlayerView(ALICE, "Alice",
                address("127.0.0.1", 40001), null);
        PlayerView bob = new PlayerView(BOB, "Bob",
                address("127.0.0.1", 40001), null);

        Map<UUID, TunnelAssociation.Match> matches = TunnelAssociation.associate(
                List.of(alice, bob), snapshot);

        assertEquals(1, matches.size());
        assertNotNull(matches.get(ALICE));
        assertNull(matches.get(BOB));
    }

    @Test
    void treatsIpv4MappedIpv6AsTheSameEndpoint() throws Exception {
        TelemetrySnapshot.Bridge bridge = bridge(7, 0, "::ffff:127.0.0.1", 40001,
                "203.0.113.5", 5555);
        TelemetrySnapshot snapshot = snapshot(bridge);
        PlayerView alice = new PlayerView(ALICE, "Alice",
                address("127.0.0.1", 40001), null);

        assertNotNull(TunnelAssociation.associate(List.of(alice), snapshot).get(ALICE));
    }

    private static TelemetrySnapshot snapshot(TelemetrySnapshot.Bridge... bridges) {
        return new TelemetrySnapshot(1, 1, false, System.nanoTime(), Map.of(), List.of(bridges));
    }

    private static TelemetrySnapshot.Bridge bridge(long connectionId, long streamId,
                                                   String localHost, int localPort,
                                                   String peerHost, int peerPort)
            throws Exception {
        return new TelemetrySnapshot.Bridge(connectionId, streamId,
                address(localHost, localPort), address(peerHost, peerPort));
    }

    private static InetSocketAddress address(String host, int port) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(host), port);
    }
}