package io.hyperconduit.demo;

import java.nio.charset.StandardCharsets;

/**
 * The tiny line protocol the benchmark server speaks over a tunnel session.
 *
 * <p>The first line the client sends selects the mode; after that the stream is raw bytes. This
 * exists so a real link can be measured per direction instead of only as a round trip: a Minecraft
 * client's pain is mostly downlink plus interactive latency, and an echo test blends the two
 * together and hides which one is broken.
 */
public final class BenchProtocol {

    /** Echo every byte back. Used for round-trip latency probes. */
    public static final String ECHO = "ECHO";
    /** Read {@code n} bytes, then reply {@code OK}. Measures the uplink alone. */
    public static final String SINK = "SINK";
    /** Send {@code n} bytes. Measures the downlink alone. */
    public static final String SEND = "SEND";
    /** Reply to {@code SINK} once everything has been received. */
    public static final String OK = "OK";

    public static final byte[] ECHO_REQUEST = (ECHO + "\n").getBytes(StandardCharsets.UTF_8);

    private BenchProtocol() {
    }

    public static byte[] sinkRequest(long bytes) {
        return (SINK + " " + bytes + "\n").getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] sendRequest(long bytes) {
        return (SEND + " " + bytes + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Deterministic pseudo-payload, so neither side has to hold the whole transfer in memory to
     * verify it. {@code index} is the offset within the generated stream.
     */
    public static byte payloadByteAt(long index) {
        return (byte) (index * 31 + index / 251 + 7);
    }

    public static byte[] payloadChunk(long startIndex, int length) {
        byte[] chunk = new byte[length];
        for (int i = 0; i < length; i++) {
            chunk[i] = payloadByteAt(startIndex + i);
        }
        return chunk;
    }

    public static boolean matchesExpected(byte[] data, int off, int len, long startIndex) {
        for (int i = 0; i < len; i++) {
            if (data[off + i] != payloadByteAt(startIndex + i)) {
                return false;
            }
        }
        return true;
    }

    /** A parsed first line. {@code command} is empty until the terminating newline has arrived. */
    public record Request(String command, long argument, int headerLength) {

        static final Request INCOMPLETE = new Request("", 0, 0);
    }

    /**
     * Scans a received chunk for the end of the request line.
     *
     * @param buffer bytes received so far, starting at the beginning of the session's stream
     * @return {@link Request#INCOMPLETE} if the newline has not arrived yet
     */
    public static Request parseRequest(byte[] buffer, int length) {
        for (int i = 0; i < length; i++) {
            if (buffer[i] != '\n') {
                continue;
            }
            String line = new String(buffer, 0, i, StandardCharsets.UTF_8).trim();
            String[] parts = line.split("\\s+");
            long argument = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
            return new Request(parts.length > 0 ? parts[0] : "", argument, i + 1);
        }
        return Request.INCOMPLETE;
    }
}
