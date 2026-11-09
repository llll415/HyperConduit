package io.hyperconduit.sidecar;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser and rate tests with no process, no Minecraft, and no sidecar binary. */
class TelemetryTrackerTest {

    @Test
    void parsesStrictStatsAndAllConnectionFields() throws Exception {
        TelemetryTracker.Frame frame = parse(stats(3, 2_000_000_000L, false,
                conn(1, "bbr", 0, 5_000_000, 1_000, 2_000),
                conn(2, "brutal", 1_250_000, 0, 0, 0)));
        assertNotNull(frame);
        assertEquals(3, frame.sequence());
        assertEquals(2_000_000_000L, frame.elapsedNanos());
        assertFalse(frame.truncated());
        assertEquals(2, frame.connections().size());
        TelemetryTracker.Sample first = frame.connections().get(0);
        assertEquals(1, first.id());
        assertEquals("bbr", first.congestion());
        assertEquals(0, first.txTargetBytesPerSecond());
        assertEquals(5_000_000, first.smoothedRttNanos());
        assertEquals(1_000, first.bytesSent());
        assertEquals(2_000, first.bytesReceived());
        assertEquals("brutal", frame.connections().get(1).congestion());
    }

    @Test
    void rejectsUnknownDuplicateAndOutOfRangeFields() {
        assertInvalid("{\"version\":1,\"event\":\"stats\",\"sequence\":1,\"elapsedNanos\":1,"
                + "\"connections\":[],\"truncated\":false,\"extra\":1}");
        assertInvalid("{\"version\":1,\"event\":\"stats\",\"sequence\":1,\"sequence\":2,"
                + "\"elapsedNanos\":1,\"connections\":[],\"truncated\":false}");
        assertInvalid(stats(1, 1, false, "{\"id\":1,\"congestion\":\"bbr\","
                + "\"txTargetBytesPerSecond\":0,\"smoothedRttNanos\":0,\"bytesSent\":0,"
                + "\"bytesReceived\":0,\"peer\":\"x\"}"));
        assertInvalid(stats(1, 1, false, "{\"id\":1,\"id\":2,\"congestion\":\"bbr\","
                + "\"txTargetBytesPerSecond\":0,\"smoothedRttNanos\":0,\"bytesSent\":0,"
                + "\"bytesReceived\":0}"));
        assertInvalid(stats(0, 1, false));
        assertInvalid(stats(1, 0, false));
        assertInvalid(stats(1, 1, false, conn(0, "bbr", 0, 0, 0, 0)));
        assertInvalid(stats(1, 1, false, conn(1, "cubic", 0, 0, 0, 0)));
        assertInvalid(stats(1, 1, false, conn(1, "bbr", 0, -1, 0, 0)));
        assertInvalid(stats(1, 1, false, conn(1, "bbr", -1, 0, 0, 0)));
        // Only Brutal has a fixed target; BBR/Reno must report zero.
        assertInvalid(stats(1, 1, false, conn(1, "bbr", 1, 0, 0, 0)));
        assertInvalid(stats(1, 1, false, conn(1, "reno", 1, 0, 0, 0)));
        assertInvalid(stats(1, 1, false, conn(1, "brutal", 0, 0, 0, 0)));
        assertInvalid("{\"version\":1,\"event\":\"stats\",\"sequence\":1e3,"
                + "\"elapsedNanos\":1,\"connections\":[],\"truncated\":false}");
        assertInvalid("{\"version\":1,\"event\":\"stats\",\"sequence\":01,"
                + "\"elapsedNanos\":1,\"connections\":[],\"truncated\":false}");
        assertInvalid("{\"version\":1,\"event\":\"stats\",\"sequence\":9223372036854775808,"
                + "\"elapsedNanos\":1,\"connections\":[],\"truncated\":false}");
        assertInvalid("{\"version\":1,\"event\":\"stats\",\"sequence\":1,"
                + "\"elapsedNanos\":1,\"connections\":[],\"truncated\":\"false\"}");
        assertInvalid("{\"version\":2,\"event\":\"stats\",\"sequence\":1,"
                + "\"elapsedNanos\":1,\"connections\":[],\"truncated\":false}");
        assertInvalid("{\"version\":1,\"event\":\"ready\",\"sequence\":1,"
                + "\"elapsedNanos\":1,\"connections\":[],\"truncated\":false}");
        assertInvalid(stats(1, 1, false) + "{}");
    }

    @Test
    void rejectsMoreThan128ConnectionsAndDuplicateIds() {
        List<String> many = new ArrayList<>();
        for (int i = 1; i <= 129; i++) many.add(conn(i, "bbr", 0, 0, 0, 0));
        assertInvalid(stats(1, 1, false, many.toArray(String[]::new)));
        assertInvalid(stats(1, 1, false,
                conn(7, "bbr", 0, 0, 0, 0), conn(7, "reno", 0, 0, 0, 0)));
    }

    @Test
    void ratesNeedTwoSamplesAndCounterDecreaseRebaselines() throws Exception {
        TelemetryTracker tracker = new TelemetryTracker();
        assertTrue(tracker.accept(parse(stats(1, 1_000_000_000L, false,
                conn(1, "bbr", 0, 5_000_000, 1_000, 2_000)))));
        TelemetrySnapshot first = tracker.get();
        assertNotNull(first);
        assertFalse(first.connections().get(1L).ratesAvailable());
        assertEquals(0.0, first.connections().get(1L).sentBytesPerSecond());

        assertTrue(tracker.accept(parse(stats(2, 2_000_000_000L, false,
                conn(1, "bbr", 0, 5_000_000, 3_000, 6_000)))));
        TelemetrySnapshot second = tracker.get();
        assertNotNull(second);
        TelemetrySnapshot.Connection rate = second.connections().get(1L);
        assertTrue(rate.ratesAvailable());
        assertEquals(2_000.0, rate.sentBytesPerSecond(), 0.001);
        assertEquals(4_000.0, rate.receivedBytesPerSecond(), 0.001);

        // A counter decrease is not negative throughput: it re-baselines.
        assertTrue(tracker.accept(parse(stats(3, 3_000_000_000L, false,
                conn(1, "bbr", 0, 5_000_000, 1_000, 1_000)))));
        TelemetrySnapshot decreased = tracker.get();
        assertNotNull(decreased);
        assertFalse(decreased.connections().get(1L).ratesAvailable());

        assertTrue(tracker.accept(parse(stats(4, 4_000_000_000L, false,
                conn(1, "bbr", 0, 5_000_000, 2_000, 3_000)))));
        TelemetrySnapshot rebased = tracker.get();
        assertNotNull(rebased);
        assertTrue(rebased.connections().get(1L).ratesAvailable());
        assertEquals(1_000.0, rebased.connections().get(1L).sentBytesPerSecond(), 0.001);
        assertEquals(2_000.0, rebased.connections().get(1L).receivedBytesPerSecond(), 0.001);
    }

    @Test
    void removedConnectionsDisappearOnTheNextFrame() throws Exception {
        TelemetryTracker tracker = new TelemetryTracker();
        tracker.accept(parse(stats(1, 1_000_000_000L, false,
                conn(1, "bbr", 0, 0, 0, 0), conn(2, "reno", 0, 0, 0, 0))));
        assertEquals(2, tracker.get().connections().size());
        tracker.accept(parse(stats(2, 2_000_000_000L, false,
                conn(1, "bbr", 0, 0, 0, 0))));
        assertEquals(1, tracker.get().connections().size());
        assertNull(tracker.get().connections().get(2L));
    }

    @Test
    void sequenceGapsAreAcceptedButOutOfOrderFramesClearTelemetry() throws Exception {
        TelemetryTracker tracker = new TelemetryTracker();
        assertTrue(tracker.accept(parse(stats(1, 1_000_000_000L, false))));
        assertTrue(tracker.accept(parse(stats(5, 2_000_000_000L, false))));

        assertFalse(tracker.accept(parse(stats(5, 3_000_000_000L, false))));
        assertNull(tracker.get());
        assertTrue(tracker.accept(parse(stats(6, 4_000_000_000L, false))));
        assertFalse(tracker.accept(parse(stats(7, 4_000_000_000L, false))));
        assertNull(tracker.get());
    }

    @Test
    void rejectedSequenceKeepsHighWaterSoOlderFramesStayRejected() throws Exception {
        TelemetryTracker tracker = new TelemetryTracker();
        assertTrue(tracker.accept(parse(stats(5, 5_000_000_000L, false))));
        // seq4 is older than the accepted high-water seq5.
        assertFalse(tracker.accept(parse(stats(4, 6_000_000_000L, false))));
        assertNull(tracker.get());
        // Only a genuinely newer sequence is accepted.
        assertTrue(tracker.accept(parse(stats(6, 7_000_000_000L, false))));
    }

    @Test
    void invalidFrameAdvancesHighWaterAndDropsBaseline() throws Exception {
        TelemetryTracker tracker = new TelemetryTracker();
        assertTrue(tracker.accept(parse(stats(4, 4_000_000_000L, false))));
        assertNotNull(tracker.get());

        // Invalid for an unknown connection field, but sequence 5 is parseable.
        tracker.reject(TelemetryTracker.parseRoot(stats(5, 5_000_000_000L, false,
                "{\"id\":1,\"congestion\":\"bbr\",\"txTargetBytesPerSecond\":0,"
                        + "\"smoothedRttNanos\":0,\"bytesSent\":0,\"bytesReceived\":0,"
                        + "\"peer\":\"x\"}")));
        assertNull(tracker.get());
        assertFalse(tracker.accept(parse(stats(4, 6_000_000_000L, false))));
        assertNull(tracker.get());
        assertTrue(tracker.accept(parse(stats(6, 7_000_000_000L, false))));
    }

    @Test
    void duplicateStatsFieldsStillAdvanceHighWater() throws Exception {
        TelemetryTracker tracker = new TelemetryTracker();
        assertTrue(tracker.accept(parse(stats(4, 4_000_000_000L, false))));

        TelemetryTracker.DuplicateFieldException duplicate = org.junit.jupiter.api.Assertions.assertThrows(
                TelemetryTracker.DuplicateFieldException.class,
                () -> TelemetryTracker.parseRoot(stats(5, 5_000_000_000L, false,
                        "{\"id\":1,\"id\":2,\"congestion\":\"bbr\","
                                + "\"txTargetBytesPerSecond\":0,\"smoothedRttNanos\":0,"
                                + "\"bytesSent\":0,\"bytesReceived\":0}")));
        assertEquals("stats", duplicate.root().get("event"));
        tracker.reject(duplicate.root());
        assertNull(tracker.get());
        assertFalse(tracker.accept(parse(stats(4, 6_000_000_000L, false))));
        assertTrue(tracker.accept(parse(stats(6, 7_000_000_000L, false))));
    }

    @Test
    void syntacticallyMalformedStatsStillKeepsSequenceHighWater() throws Exception {
        TelemetryTracker tracker = new TelemetryTracker();
        assertTrue(tracker.accept(parse(stats(4, 4_000_000_000L, false))));
        tracker.reject("{\"version\":1,\"event\":\"stats\",\"sequence\":5,\"elapsedNanos\":5");
        assertNull(tracker.get());
        assertFalse(tracker.accept(parse(stats(4, 6_000_000_000L, false))));
        assertTrue(tracker.accept(parse(stats(6, 7_000_000_000L, false))));
    }

    @Test
    void staleGapResetsRateBaseline() throws Exception {
        TelemetryTracker tracker = new TelemetryTracker();
        long now = System.nanoTime();
        tracker.accept(parse(stats(1, 1_000_000_000L, false,
                conn(1, "bbr", 0, 0, 1_000, 2_000))), now - 4_000_000_000L);
        // The first frame is already stale, but the second frame is current.
        // A gap longer than 3.5s must not compute a rate across the gap.
        tracker.accept(parse(stats(2, 2_000_000_000L, false,
                conn(1, "bbr", 0, 0, 5_000, 9_000))), now);
        TelemetrySnapshot snapshot = tracker.get();
        assertNotNull(snapshot);
        assertFalse(snapshot.connections().get(1L).ratesAvailable());
        assertEquals(0.0, snapshot.connections().get(1L).sentBytesPerSecond());
    }

    @Test
    void staleSnapshotsReportUnavailable() {
        long now = System.nanoTime();
        Map<Long, TelemetrySnapshot.Connection> connections = Map.of();
        TelemetrySnapshot live = new TelemetrySnapshot(1, 1, false, now, connections);
        TelemetrySnapshot stale = new TelemetrySnapshot(1, 1, false,
                now - TelemetrySnapshot.STALE_NANOS - 1, connections);
        assertTrue(live.available());
        assertFalse(stale.available());
    }

    private static TelemetryTracker.Frame parse(String line) throws IOException {
        return TelemetryTracker.parseStats(TelemetryTracker.parseRoot(line));
    }

    private static void assertInvalid(String line) {
        try {
            assertNull(parse(line), () -> "accepted invalid stats: " + line);
        } catch (IOException expected) {
            // A syntactically invalid line is also telemetry-only invalid.
        }
    }

    private static String stats(long sequence, long elapsed, boolean truncated,
                                String... connections) {
        return "{\"version\":1,\"event\":\"stats\",\"sequence\":" + sequence
                + ",\"elapsedNanos\":" + elapsed + ",\"connections\":["
                + String.join(",", connections) + "],\"truncated\":" + truncated + "}";
    }

    private static String conn(long id, String congestion, long tx, long rtt,
                               long sent, long received) {
        return "{\"id\":" + id + ",\"congestion\":\"" + congestion
                + "\",\"txTargetBytesPerSecond\":" + tx
                + ",\"smoothedRttNanos\":" + rtt
                + ",\"bytesSent\":" + sent
                + ",\"bytesReceived\":" + received + "}";
    }
}
