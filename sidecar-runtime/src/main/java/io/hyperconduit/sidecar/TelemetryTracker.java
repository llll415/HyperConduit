package io.hyperconduit.sidecar;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict parser and rate tracker for the opt-in {@code stats} event.
 *
 * <p>The parser never sees a game connection and never fails the sidecar
 * process: malformed, duplicate, out-of-order, or out-of-range stats clear the
 * telemetry state only. The enclosing process keeps the game tunnel alive.
 */
final class TelemetryTracker {

    static final int MAX_CONNECTIONS = 128;
    private static final int MAX_DEPTH = 8;
    private static final Set<String> ROOT_FIELDS = Set.of(
            "version", "event", "sequence", "elapsedNanos", "connections", "truncated");
    private static final Set<String> CONNECTION_FIELDS = Set.of(
            "id", "congestion", "txTargetBytesPerSecond", "smoothedRttNanos",
            "bytesSent", "bytesReceived");
    private static final Set<String> CONGESTIONS = Set.of("bbr", "reno", "brutal");
    private static final Pattern SEQUENCE = Pattern.compile("\"sequence\"\\s*:\\s*([1-9][0-9]*)");
    private static final Pattern ELAPSED = Pattern.compile("\"elapsedNanos\"\\s*:\\s*([1-9][0-9]*)");
    private static final Object NULL = new Object();

    private volatile TelemetrySnapshot current;
    private Map<Long, Counters> previous = Map.of();
    private long lastSequence;
    private long lastElapsed;
    private long lastReceiptNanos;

    /** Number token text is retained so exponent/leading-zero forms stay invalid. */
    record JsonNumber(String text) {}

    record Sample(long id, String congestion, long txTargetBytesPerSecond,
                  long smoothedRttNanos, long bytesSent, long bytesReceived) {}

    record Frame(long sequence, long elapsedNanos, boolean truncated, List<Sample> connections) {}

    private record Counters(long bytesSent, long bytesReceived) {}

    /** A duplicate field still carries a root object so stats can keep high-water. */
    static final class DuplicateFieldException extends IOException {
        private final transient Map<String, Object> root;

        DuplicateFieldException(Map<String, Object> root) {
            super("Duplicate protocol field");
            this.root = root;
        }

        Map<String, Object> root() {
            return root;
        }
    }

    /**
     * Parses a complete protocol line into a strict raw object tree. Duplicate
     * object field names, trailing data, and excessive nesting are rejected.
     */
    static Map<String, Object> parseRoot(String text) throws IOException {
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setLenient(false);
        boolean[] duplicate = {false};
        Object value = readValue(reader, 0, duplicate);
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw new IOException("Trailing protocol data");
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IOException("Protocol event must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) value;
        if (duplicate[0]) throw new DuplicateFieldException(root);
        return root;
    }

    private static Object readValue(JsonReader reader, int depth, boolean[] duplicate)
            throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Protocol nesting too deep");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                Map<String, Object> object = new LinkedHashMap<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    Object field = readValue(reader, depth + 1, duplicate);
                    if (object.containsKey(name)) {
                        // Keep the first value so a duplicate event/sequence
                        // cannot hide that this was a stats frame.
                        duplicate[0] = true;
                        continue;
                    }
                    object.put(name, field);
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                List<Object> array = new ArrayList<>();
                reader.beginArray();
                while (reader.hasNext()) array.add(readValue(reader, depth + 1, duplicate));
                reader.endArray();
                yield array;
            }
            case STRING -> reader.nextString();
            case NUMBER -> new JsonNumber(reader.nextString());
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> {
                reader.nextNull();
                yield NULL;
            }
            default -> throw new IOException("Invalid protocol value");
        };
    }

    /** Returns a validated frame, or {@code null} if any stats field is invalid. */
    static Frame parseStats(Map<String, Object> root) {
        try {
            if (!root.keySet().equals(ROOT_FIELDS)) return null;
            if (!isVersionOne(root.get("version"))) return null;
            if (!"stats".equals(root.get("event"))) return null;
            long sequence = positive(root.get("sequence"));
            long elapsed = positive(root.get("elapsedNanos"));
            if (!(root.get("truncated") instanceof Boolean truncated)) return null;
            if (!(root.get("connections") instanceof List<?> list)) return null;
            if (list.size() > MAX_CONNECTIONS) return null;

            List<Sample> samples = new ArrayList<>(list.size());
            Set<Long> ids = new HashSet<>();
            for (Object value : list) {
                if (!(value instanceof Map<?, ?>)) return null;
                @SuppressWarnings("unchecked")
                Map<String, Object> connection = (Map<String, Object>) value;
                if (!connection.keySet().equals(CONNECTION_FIELDS)) return null;
                long id = positive(connection.get("id"));
                if (!ids.add(id)) return null;
                Object congestionValue = connection.get("congestion");
                if (!(congestionValue instanceof String congestion)
                        || !CONGESTIONS.contains(congestion)) {
                    return null;
                }
                long txTarget = nonNegative(connection.get("txTargetBytesPerSecond"));
                // Only the local Brutal sender has a fixed target. BBR/Reno
                // must never be presented as a configured rate.
                if ("brutal".equals(congestion) ? txTarget <= 0 : txTarget != 0) return null;
                samples.add(new Sample(
                        id,
                        congestion,
                        txTarget,
                        nonNegative(connection.get("smoothedRttNanos")),
                        nonNegative(connection.get("bytesSent")),
                        nonNegative(connection.get("bytesReceived"))));
            }
            return new Frame(sequence, elapsed, truncated, List.copyOf(samples));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /**
     * Applies a validated frame. Out-of-order frames drop the current sample and
     * rate baseline but keep the sequence/elapsed high-water marks, so an older
     * frame can never be accepted after a newer one was rejected.
     */
    synchronized boolean accept(Frame frame) {
        return accept(frame, System.nanoTime());
    }

    /** Testable clock variant; nowNanos is also the snapshot capture time. */
    synchronized boolean accept(Frame frame, long nowNanos) {
        if (frame == null) {
            dropBaseline();
            return false;
        }
        long age = lastReceiptNanos == 0 ? 0 : nowNanos - lastReceiptNanos;
        if (age > TelemetrySnapshot.STALE_NANOS) {
            // A gap longer than the freshness window invalidates the previous
            // byte counters. The first frame after the gap reports pending
            // rates instead of averaging across a stale interval.
            previous = Map.of();
        }
        lastReceiptNanos = nowNanos;
        if (lastSequence != 0 && frame.sequence() <= lastSequence) {
            dropBaseline();
            return false;
        }
        if (lastElapsed != 0 && frame.elapsedNanos() <= lastElapsed) {
            lastSequence = frame.sequence();
            dropBaseline();
            return false;
        }
        long elapsedDelta = lastElapsed == 0 ? 0 : frame.elapsedNanos() - lastElapsed;
        Map<Long, TelemetrySnapshot.Connection> connections = new LinkedHashMap<>();
        Map<Long, Counters> next = new HashMap<>();
        for (Sample sample : frame.connections()) {
            Counters prior = previous.get(sample.id());
            boolean rates = prior != null
                    && elapsedDelta > 0
                    && sample.bytesSent() >= prior.bytesSent()
                    && sample.bytesReceived() >= prior.bytesReceived();
            double sent = 0.0;
            double received = 0.0;
            if (rates) {
                sent = (sample.bytesSent() - prior.bytesSent()) * 1_000_000_000.0 / elapsedDelta;
                received = (sample.bytesReceived() - prior.bytesReceived())
                        * 1_000_000_000.0 / elapsedDelta;
            }
            connections.put(sample.id(), new TelemetrySnapshot.Connection(
                    sample.id(), sample.congestion(), sample.txTargetBytesPerSecond(),
                    sample.smoothedRttNanos(), sample.bytesSent(), sample.bytesReceived(),
                    rates, sent, received));
            // A decrease is not a rate; the current values become the new baseline.
            next.put(sample.id(), new Counters(sample.bytesSent(), sample.bytesReceived()));
        }
        previous = Map.copyOf(next);
        lastSequence = frame.sequence();
        lastElapsed = frame.elapsedNanos();
        current = new TelemetrySnapshot(frame.sequence(), frame.elapsedNanos(), frame.truncated(),
                nowNanos, connections);
        return true;
    }

    /**
     * Rejects an invalid stats frame. If its sequence/elapsed are still
     * parseable they advance the high-water marks; current values and the rate
     * baseline are dropped.
     */
    synchronized void reject(Map<String, Object> root) {
        rejectValues(parseablePositive(root.get("sequence")),
                parseablePositive(root.get("elapsedNanos")));
    }

    /**
     * Rejects a syntactically malformed stats line. Sequence/elapsed literals
     * are still recovered when present so a broken seq5 cannot be followed by
     * an older seq4; otherwise only the current sample and baseline are lost.
     */
    synchronized void reject(String text) {
        rejectValues(extractPositive(SEQUENCE, text), extractPositive(ELAPSED, text));
    }

    private void rejectValues(long sequence, long elapsed) {
        if (sequence > lastSequence) lastSequence = sequence;
        if (elapsed > lastElapsed) lastElapsed = elapsed;
        lastReceiptNanos = System.nanoTime();
        dropBaseline();
    }

    private static long extractPositive(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return 0;
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException overflow) {
            return 0;
        }
    }

    /** Returns only a live sample; stale or absent telemetry is unavailable. */
    TelemetrySnapshot get() {
        TelemetrySnapshot snapshot = current;
        return snapshot != null && snapshot.available() ? snapshot : null;
    }

    /** Full lifecycle reset, used when the process stops, fails, or closes. */
    synchronized void clear() {
        current = null;
        previous = Map.of();
        lastSequence = 0;
        lastElapsed = 0;
        lastReceiptNanos = 0;
    }

    /** Drops current/baseline while preserving sequence/elapsed high-water. */
    private void dropBaseline() {
        current = null;
        previous = Map.of();
    }

    private static boolean isVersionOne(Object value) {
        return value instanceof JsonNumber number && number.text().equals("1");
    }

    private static long positive(Object value) {
        return number(value, true);
    }

    private static long parseablePositive(Object value) {
        try {
            return positive(value);
        } catch (RuntimeException invalid) {
            return 0;
        }
    }

    private static long nonNegative(Object value) {
        return number(value, false);
    }

    private static long number(Object value, boolean positive) {
        if (!(value instanceof JsonNumber number)) throw new IllegalArgumentException("number");
        String text = number.text();
        if (positive ? !text.matches("[1-9][0-9]*") : !text.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("number");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("number", overflow);
        }
    }
}
