package io.hyperconduit.demo;

import io.hyperconduit.conn.SessionEngine;
import io.hyperconduit.conn.SessionListener;
import io.hyperconduit.conn.SessionStats;
import io.hyperconduit.net.UdpClientSession;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Benchmark client. Four modes, because a single round-trip echo measurement hides the thing that
 * actually matters here:
 *
 * <ul>
 *   <li>{@code down} — downlink throughput only ({@code SEND})</li>
 *   <li>{@code up} — uplink throughput only ({@code SINK})</li>
 *   <li>{@code rtt} — small-probe round-trip latency on an idle tunnel</li>
 *   <li>{@code rtt-load} — the same probes on a <em>second, concurrent</em> session while the first
 *       saturates the downlink. This is the measurement that decides the design: a Minecraft client
 *       feels interactive latency while chunks and entities stream in, and a transport that lets bulk
 *       traffic block it will feel terrible regardless of its peak throughput.</li>
 * </ul>
 *
 * <p>{@code rtt-load} needs two sessions precisely because one ordered stream cannot separate them:
 * within a single session a probe queues behind the bulk bytes, which is head-of-line blocking and is
 * unavoidable for any reliable ordered transport. Separate sessions get separate pacers and separate
 * retransmission state, so a hole in the bulk transfer does not stall the probe.
 */
public final class BenchClient {

    private static final int CHUNK = 64 * 1024;
    private static final int PROBE_BYTES = 8;
    private static final long PARK_NANOS = 50_000L;
    private static final long PROBE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    /** How long a probe waits by spinning before falling back to parking. */
    private static final long SPIN_WINDOW_NANOS = 5_000_000L;

    private BenchClient() {
    }

    /** Collectors read from the engine inside a driver-thread callback, so they need a reference. */
    private interface EngineAware {
        void setEngine(SessionEngine engine);
    }

    public static void run(BenchOptions options) throws Exception {
        switch (options.mode()) {
            case "down" -> downlink(options);
            case "up" -> uplink(options);
            case "rtt" -> latency(options, false);
            case "rtt-load" -> latency(options, true);
            default -> throw new IllegalArgumentException(
                    "unknown --mode: " + options.mode() + " (expected down|up|rtt|rtt-load)");
        }
    }

    // --- downlink -----------------------------------------------------------------

    private static void downlink(BenchOptions options) throws Exception {
        DownlinkCollector collector = new DownlinkCollector(options.verify());
        try (UdpClientSession session = connect(options, collector)) {
            SessionEngine engine = session.engine();
            byte[] request = BenchProtocol.sendRequest(options.sizeBytes());
            engine.write(request, 0, request.length);

            System.out.printf("[down] receiving %,d bytes ...%n", options.sizeBytes());
            long startNanos = System.nanoTime();
            boolean completed = awaitBytes(collector.received, options.sizeBytes(),
                    options.timeoutSeconds(), startNanos);
            long elapsedNanos = System.nanoTime() - startNanos;

            reportTransfer("down", options, completed, collector.received.get(), elapsedNanos,
                    collector.mismatchedChunks.get(), engine.stats());
        }
    }

    /** Counts and verifies stream bytes as the driver thread delivers them. */
    private static class DownlinkCollector implements SessionListener, EngineAware {

        final AtomicLong received = new AtomicLong();
        final AtomicLong mismatchedChunks = new AtomicLong();
        private final boolean verify;
        /** Optional external counter to mirror progress into, for a concurrent load generator. */
        private final AtomicLong progressSink;
        private final byte[] buffer = new byte[CHUNK];
        private long expectedIndex;
        private SessionEngine engine;

        DownlinkCollector(boolean verify) {
            this(verify, null);
        }

        DownlinkCollector(boolean verify, AtomicLong progressSink) {
            this.verify = verify;
            this.progressSink = progressSink;
        }

        @Override
        public void setEngine(SessionEngine engine) {
            this.engine = engine;
        }

        @Override
        public void onReadable() {
            while (true) {
                int n = engine.read(buffer, 0, buffer.length);
                if (n <= 0) {
                    return;
                }
                if (verify && !BenchProtocol.matchesExpected(buffer, 0, n, expectedIndex)) {
                    mismatchedChunks.incrementAndGet();
                }
                expectedIndex += n;
                long total = received.addAndGet(n);
                if (progressSink != null) {
                    progressSink.set(total);
                }
            }
        }
    }

    // --- uplink -------------------------------------------------------------------

    private static void uplink(BenchOptions options) throws Exception {
        OkWatcher watcher = new OkWatcher();
        try (UdpClientSession session = connect(options, watcher)) {
            SessionEngine engine = session.engine();
            byte[] request = BenchProtocol.sinkRequest(options.sizeBytes());
            engine.write(request, 0, request.length);

            System.out.printf("[up] sending %,d bytes ...%n", options.sizeBytes());
            long startNanos = System.nanoTime();
            long sent = 0;
            while (sent < options.sizeBytes()) {
                if (!engine.isWritable()) {
                    LockSupport.parkNanos(PARK_NANOS);
                    continue;
                }
                int n = (int) Math.min(CHUNK, options.sizeBytes() - sent);
                engine.write(BenchProtocol.payloadChunk(sent, n), 0, n);
                sent += n;
            }
            boolean acknowledged = watcher.ok.await(options.timeoutSeconds(), TimeUnit.SECONDS);
            long elapsedNanos = System.nanoTime() - startNanos;

            reportTransfer("up", options, acknowledged, sent, elapsedNanos, 0, engine.stats());
        }
    }

    /** Watches for the server's {@code OK} reply that closes a SINK transfer. */
    private static final class OkWatcher implements SessionListener, EngineAware {

        final CountDownLatch ok = new CountDownLatch(1);
        private final byte[] buffer = new byte[64];
        private SessionEngine engine;
        private int filled;

        @Override
        public void setEngine(SessionEngine engine) {
            this.engine = engine;
        }

        @Override
        public void onReadable() {
            while (filled < buffer.length) {
                int n = engine.read(buffer, filled, buffer.length - filled);
                if (n <= 0) {
                    return;
                }
                filled += n;
                if (new String(buffer, 0, filled, StandardCharsets.UTF_8).startsWith(BenchProtocol.OK)) {
                    ok.countDown();
                    return;
                }
            }
        }
    }

    // --- latency ------------------------------------------------------------------

    private static void latency(BenchOptions options, boolean underLoad) throws Exception {
        AtomicLong loadReceived = null;
        Thread loadThread = null;
        if (underLoad) {
            loadReceived = new AtomicLong();
            loadThread = startLoadSession(options, loadReceived);
            waitForLoad(loadReceived);
        }

        ProbeCollector collector = new ProbeCollector();
        try (UdpClientSession session = connect(options, collector)) {
            SessionEngine engine = session.engine();
            byte[] request = BenchProtocol.ECHO_REQUEST;
            engine.write(request, 0, request.length);

            System.out.printf("[rtt] %s, %,d probes of %d bytes every %d ms%n",
                    underLoad ? "probing under downlink load" : "probing an idle tunnel",
                    options.probeCount(), PROBE_BYTES, options.probeIntervalMillis());

            long[] samples = new long[options.probeCount()];
            int completed = 0;
            for (int i = 0; i < samples.length; i++) {
                long expected = (long) (i + 1) * PROBE_BYTES;
                byte[] probe = probePayload(i);
                long startNanos = System.nanoTime();
                engine.write(probe, 0, probe.length);

                boolean echoed = false;
                long spinDeadline = startNanos + SPIN_WINDOW_NANOS;
                while (System.nanoTime() - startNanos < PROBE_TIMEOUT_NANOS) {
                    if (collector.received.get() >= expected) {
                        echoed = true;
                        break;
                    }
                    // Spin first: parking on Windows is quantised to ~15.6ms, which would dominate a
                    // sub-millisecond measurement and make the whole latency comparison meaningless.
                    if (System.nanoTime() < spinDeadline) {
                        Thread.onSpinWait();
                    } else {
                        LockSupport.parkNanos(PARK_NANOS);
                    }
                }
                if (!echoed) {
                    System.err.println("[rtt] probe " + i + " timed out");
                    continue;
                }
                samples[completed++] = System.nanoTime() - startNanos;
                if (options.probeIntervalMillis() > 0) {
                    Thread.sleep(options.probeIntervalMillis());
                }
            }
            reportLatency(underLoad, samples, completed, loadReceived, engine.stats());
        } finally {
            if (loadThread != null) {
                loadThread.interrupt();
            }
        }
    }

    private static byte[] probePayload(int index) {
        byte[] probe = new byte[PROBE_BYTES];
        for (int i = 0; i < probe.length; i++) {
            probe[i] = (byte) (index >>> (8 * (i % 4)));
        }
        return probe;
    }

    /** Starts a second session that pulls bytes continuously, to load the downlink. */
    private static Thread startLoadSession(BenchOptions options, AtomicLong progress) {
        Thread thread = new Thread(() -> {
            DownlinkCollector collector = new DownlinkCollector(false, progress);
            try (UdpClientSession session = connect(options, collector)) {
                byte[] request = BenchProtocol.sendRequest(options.sizeBytes());
                session.engine().write(request, 0, request.length);
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(200);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[rtt-load] background load session failed: " + e);
            }
        }, "bench-load");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void waitForLoad(AtomicLong loadReceived) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        // Wait until the bulk stream is actually moving, otherwise the probes measure an idle link.
        while (loadReceived.get() < 256 * 1024 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        if (loadReceived.get() < 256 * 1024) {
            System.err.printf("[rtt-load] warning: background downlink only reached %,d bytes;"
                    + " probes may be measuring a lightly loaded link%n", loadReceived.get());
        } else {
            System.out.printf("[rtt-load] background downlink has moved %,d bytes, starting probes%n",
                    loadReceived.get());
        }
    }

    /** Counts echoed probe bytes so the caller can wait for a specific probe to come back. */
    private static final class ProbeCollector implements SessionListener, EngineAware {

        final AtomicLong received = new AtomicLong();
        private final byte[] buffer = new byte[4096];
        private SessionEngine engine;

        @Override
        public void setEngine(SessionEngine engine) {
            this.engine = engine;
        }

        @Override
        public void onReadable() {
            while (true) {
                int n = engine.read(buffer, 0, buffer.length);
                if (n <= 0) {
                    return;
                }
                received.addAndGet(n);
            }
        }
    }

    // --- shared helpers -----------------------------------------------------------

    private static UdpClientSession connect(BenchOptions options, SessionListener listener)
            throws Exception {
        UdpClientSession session = new UdpClientSession(options.address(),
                options.clientConfig(new SecureRandom().nextInt()), listener);
        // Wire the engine before starting the driver thread: listeners fire from that thread and
        // read from the engine, so assigning afterwards would be a race.
        if (listener instanceof EngineAware aware) {
            aware.setEngine(session.engine());
        }
        session.start();
        if (!session.awaitEstablished(TimeUnit.SECONDS.toNanos(options.timeoutSeconds()))) {
            session.close();
            throw new IllegalStateException("handshake did not complete within "
                    + options.timeoutSeconds() + "s (state=" + session.engine().state() + ")");
        }
        return session;
    }

    private static boolean awaitBytes(AtomicLong counter, long target, int timeoutSeconds,
                                      long startNanos) {
        long deadline = startNanos + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        long lastReportNanos = startNanos;
        long lastBytes = 0;
        while (counter.get() < target && System.nanoTime() < deadline) {
            LockSupport.parkNanos(PARK_NANOS);
            long now = System.nanoTime();
            if (now - lastReportNanos >= TimeUnit.SECONDS.toNanos(1)) {
                long current = counter.get();
                double mbps = (current - lastBytes) * 8.0 / ((now - lastReportNanos) / 1e9) / 1e6;
                System.out.printf("  %6.2f Mbit/s  %,d / %,d bytes (%.0f%%)%n", mbps, current,
                        target, 100.0 * current / Math.max(1, target));
                lastReportNanos = now;
                lastBytes = current;
            }
        }
        return counter.get() >= target;
    }

    private static void reportTransfer(String label, BenchOptions options, boolean completed,
                                       long transferred, long elapsedNanos, long mismatches,
                                       SessionStats stats) {
        System.out.println();
        if (!completed) {
            System.out.printf("[%s] INCOMPLETE after %.1fs: %,d of %,d bytes%n", label,
                    elapsedNanos / 1e9, transferred, options.sizeBytes());
        } else {
            System.out.printf("[%s] %,d bytes in %.3f s = %.2f Mbit/s%n", label, transferred,
                    elapsedNanos / 1e9, transferred * 8.0 / (elapsedNanos / 1e9) / 1e6);
        }
        if (options.verify()) {
            if (mismatches > 0) {
                System.out.printf("[%s] !! %,d chunks did not match the expected payload%n", label,
                        mismatches);
            } else {
                System.out.printf("[%s] payload verified byte-for-byte%n", label);
            }
        }
        reportStats(label, stats);
    }

    private static void reportLatency(boolean underLoad, long[] samples, int count,
                                      AtomicLong loadReceived, SessionStats stats) {
        System.out.println();
        if (count == 0) {
            System.out.println("[rtt] no probes completed");
            return;
        }
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        System.out.printf("[rtt] %d probes over an %s tunnel%s%n", count,
                underLoad ? "actively loaded" : "idle",
                underLoad && loadReceived != null
                        ? String.format(" (background downlink moved %,d bytes)", loadReceived.get())
                        : "");
        System.out.printf("[rtt]   min    %7.2f ms%n", sorted[0] / 1e6);
        System.out.printf("[rtt]   median %7.2f ms%n", percentile(sorted, 0.50) / 1e6);
        System.out.printf("[rtt]   p95    %7.2f ms%n", percentile(sorted, 0.95) / 1e6);
        System.out.printf("[rtt]   p99    %7.2f ms%n", percentile(sorted, 0.99) / 1e6);
        System.out.printf("[rtt]   max    %7.2f ms   <- the spike a player feels%n",
                sorted[count - 1] / 1e6);
        System.out.printf("[rtt]   mean   %7.2f ms%n", mean(sorted) / 1e6);
        reportStats("rtt", stats);
    }

    private static void reportStats(String label, SessionStats stats) {
        System.out.printf("[%s] tunnel: rtt=%.1f ms (min %.1f) sent=%,d pkt received=%,d pkt"
                        + " lost=%,d rtx=%,d pto=%d cwnd=%,d inFlight=%,d%n",
                label, stats.smoothedRttNanos() / 1e6, stats.minRttNanos() / 1e6,
                stats.packetsSent(), stats.packetsReceived(), stats.packetsLost(),
                stats.packetsRetransmitted(), stats.ptoCount(), stats.congestionWindow(),
                stats.bytesInFlight());
    }

    private static long percentile(long[] sorted, double p) {
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static double mean(long[] values) {
        long sum = 0;
        for (long value : values) {
            sum += value;
        }
        return values.length == 0 ? 0 : (double) sum / values.length;
    }
}
