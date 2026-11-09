package io.hyperconduit.mod.bridge;

import io.hyperconduit.sidecar.TelemetrySnapshot;
import io.hyperconduit.sidecar.TunnelAttributes;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a Minecraft channel only ever sees telemetry for its own authenticated QUIC
 * connection. Each listener owns a supplier, a server never guesses an id from a single peer,
 * and an unavailable refresh must not keep serving the previous snapshot.
 */
class TunnelMetricsTest {

    @Test
    void clientWithOneActualConnectionIsSelected() {
        EmbeddedChannel channel = channel(true, null, () -> snapshot(connection(7L, "bbr")));
        try {
            TelemetrySnapshot.Connection selected = TunnelMetrics.connection(channel);
            assertEquals(7L, selected.id());
            assertEquals("bbr", selected.congestion());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void serverWithoutIdNeverGuessesASinglePeer() {
        EmbeddedChannel channel = channel(false, null, () -> snapshot(connection(7L, "bbr")));
        try {
            assertNull(TunnelMetrics.connection(channel),
                    "a server channel without an authenticated id must not select its only peer");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void serverUsesTheExactAuthenticatedIdFromItsOwnSupplier() {
        Supplier<TelemetrySnapshot> source =
                () -> snapshot(connection(11L, "bbr"), connection(22L, "reno"));
        EmbeddedChannel channel = channel(false, 22L, source);
        try {
            TelemetrySnapshot.Connection selected = TunnelMetrics.connection(channel);
            assertEquals(22L, selected.id());
            assertEquals("reno", selected.congestion());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void twoListenersWithTheSameConnectionIdStayIsolated() {
        EmbeddedChannel first = channel(false, 5L, () -> snapshot(connection(5L, "bbr")));
        EmbeddedChannel second = channel(false, 5L, () -> snapshot(connection(5L, "brutal")));
        try {
            TelemetrySnapshot.Connection firstSelected = TunnelMetrics.connection(first);
            TelemetrySnapshot.Connection secondSelected = TunnelMetrics.connection(second);
            assertEquals("bbr", firstSelected.congestion());
            assertEquals("brutal", secondSelected.congestion());
            assertNotSame(firstSelected, secondSelected,
                    "each listener must read its own supplier, not a shared snapshot");
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void closedChannelHasNoMetrics() {
        EmbeddedChannel channel = channel(true, 9L, () -> snapshot(connection(9L, "bbr")));
        channel.close();
        try {
            assertNull(TunnelMetrics.connection(channel));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void tcpChannelWithoutTunnelMarkerHasNoMetrics() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(TunnelMetrics.CLIENT).set(Boolean.TRUE);
        channel.attr(TunnelMetrics.SOURCE).set((Supplier<TelemetrySnapshot>) () -> snapshot(connection(9L, "bbr")));
        try {
            assertNull(TunnelMetrics.connection(channel),
                    "a plain TCP channel must not inherit tunnel telemetry");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void channelWithoutSourceHasNoMetrics() {
        EmbeddedChannel channel = channel(true, 9L, null);
        try {
            assertNull(TunnelMetrics.connection(channel));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void nullSupplierResultHasNoMetrics() {
        EmbeddedChannel channel = channel(true, 9L, () -> null);
        try {
            assertNull(TunnelMetrics.connection(channel));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void staleSnapshotHasNoMetrics() {
        long staleCapturedAt = System.nanoTime() - TelemetrySnapshot.STALE_NANOS - 1;
        EmbeddedChannel channel = channel(true, null,
                () -> new TelemetrySnapshot(1L, 1L, false, staleCapturedAt,
                        Map.of(9L, connection(9L, "bbr"))));
        try {
            assertNull(TunnelMetrics.connection(channel),
                    "connections from a stale snapshot must not be exposed");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void truncatedSnapshotMissingTheConnectionHasNoMetrics() {
        EmbeddedChannel channel = channel(false, 5L,
                () -> new TelemetrySnapshot(1L, 1L, true, System.nanoTime(),
                        Map.of(9L, connection(9L, "bbr"))));
        try {
            assertNull(TunnelMetrics.connection(channel),
                    "a truncated snapshot must not substitute another connection");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void refreshedUnavailableSupplierDoesNotReuseStaleValues() {
        AtomicReference<TelemetrySnapshot> current =
                new AtomicReference<>(snapshot(connection(9L, "bbr")));
        EmbeddedChannel channel = channel(true, null, current::get);
        try {
            assertEquals(9L, TunnelMetrics.connection(channel).id());

            current.set(new TelemetrySnapshot(2L, 2L, false,
                    System.nanoTime() - TelemetrySnapshot.STALE_NANOS - 1,
                    Map.of(9L, connection(9L, "bbr"))));
            assertNull(TunnelMetrics.connection(channel),
                    "an unavailable refresh must not reuse the previous snapshot");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel(boolean client, Long connectionId,
                                           Supplier<TelemetrySnapshot> source) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(TunnelAttributes.TUNNEL).set(Boolean.TRUE);
        if (client) {
            channel.attr(TunnelMetrics.CLIENT).set(Boolean.TRUE);
        }
        if (connectionId != null) {
            channel.attr(TunnelAttributes.CONNECTION_ID).set(connectionId);
        }
        if (source != null) {
            channel.attr(TunnelMetrics.SOURCE).set(source);
        }
        return channel;
    }

    private static TelemetrySnapshot snapshot(TelemetrySnapshot.Connection... connections) {
        Map<Long, TelemetrySnapshot.Connection> byId = new LinkedHashMap<>();
        for (TelemetrySnapshot.Connection connection : connections) {
            byId.put(connection.id(), connection);
        }
        return new TelemetrySnapshot(1L, 1L, false, System.nanoTime(), byId);
    }

    private static TelemetrySnapshot.Connection connection(long id, String congestion) {
        return new TelemetrySnapshot.Connection(id, congestion, 0L, 0L, 0L, 0L, false, 0d, 0d);
    }
}
