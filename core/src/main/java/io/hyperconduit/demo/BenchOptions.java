package io.hyperconduit.demo;

import io.hyperconduit.cc.CongestionController;
import io.hyperconduit.conn.SessionConfig;

import java.net.InetSocketAddress;
import java.util.function.Supplier;

/** Parsed command-line options, shared by the server and client entry points. */
public record BenchOptions(
        InetSocketAddress address,
        String mode,
        String controller,
        int mbps,
        int windowBytes,
        long sizeBytes,
        int probeCount,
        long probeIntervalMillis,
        int timeoutSeconds,
        boolean verify) {

    public static final int DEFAULT_MBPS = 100;
    public static final int DEFAULT_WINDOW = 256 * 1024;
    public static final long DEFAULT_SIZE = 32L * 1024 * 1024;

    /** This side's Brutal send-rate target in bytes/second. */
    public long bps() {
        return mbps * 1_000_000L / 8;
    }

    public Supplier<CongestionController> congestionController() {
        return "reno".equals(controller) ? SessionConfig.reno() : SessionConfig.brutal(bps());
    }

    public SessionConfig clientConfig(int connectionId) {
        return SessionConfig.client()
                .connectionId(connectionId)
                .congestionController(congestionController())
                .receiveWindowBytes(windowBytes)
                .sendBufferBytes(sendBufferBytes());
    }

    public SessionConfig serverConfig() {
        return SessionConfig.server()
                .congestionController(congestionController())
                .receiveWindowBytes(windowBytes);
    }

    /**
     * Application-side send buffer high-water mark. Deliberately modest: a huge buffer lets the
     * caller spin in {@code write()} and starve the driver thread that actually puts packets on the
     * wire, which looks like a stall but is really lock contention. Backpressure should come from
     * here and from the tunnel's own flow control, not from an effectively unbounded queue.
     */
    public int sendBufferBytes() {
        return Math.max(1024 * 1024, 4 * windowBytes);
    }

}
