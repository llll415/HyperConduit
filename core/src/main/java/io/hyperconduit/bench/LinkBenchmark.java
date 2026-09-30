package io.hyperconduit.bench;

import io.hyperconduit.cc.CongestionController;
import io.hyperconduit.conn.SessionConfig;
import io.hyperconduit.conn.SessionStats;
import io.hyperconduit.sim.SimulatedLink;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Measures the transport over simulated links and prints a comparison table.
 *
 * <p><b>What this can and cannot tell you.</b> Both controllers here run inside the same userspace
 * reliability layer, so they share one loss detector, one retransmission path and one probe timer.
 * The comparison therefore isolates the contribution of the congestion algorithm and pacing — it
 * does <em>not</em> reproduce the kernel-TCP RTO cliff, which is a large part of why a userspace
 * transport was chosen in the first place. Measuring that requires a real socket against
 * {@code netem} or {@code clumsy}, or the production link itself.
 *
 * <p><b>The metric that matters is stall, not throughput.</b> On a deliberately lossy path the
 * question is not how fast a bulk transfer finishes but how long the application sees nothing while
 * a hole is being repaired. {@code maxStall} is the longest such hitch — what a player actually
 * feels — and {@code stallTotal} is how much of the transfer was spent frozen.
 *
 * <p>Run with {@code gradle :core:run -PmainClass=io.hyperconduit.bench.LinkBenchmark}, optionally
 * with {@code --size= --rtt= --loss= --mbps= --window= --cc= --runs= --timeout=} arguments.
 * Each cell is averaged over {@code --runs} independent loss-pattern seeds (default 5); a single
 * run is far too noisy to read anything from.
 */
public final class LinkBenchmark {

    private static final long MS = SimulatedLink.MS;
    private static final long SECOND = SimulatedLink.SECOND;
    private static final byte[] PSK = "benchmark-psk".getBytes(StandardCharsets.UTF_8);

    private LinkBenchmark() {
    }

    private record SingleRun(boolean completed, long completionMs, double goodputMbps,
                             long stallTotalMs, long maxStallMs, long packetsLost,
                             long packetsRetransmitted) {
    }

    /**
     * Aggregated over {@code runs} different loss-pattern seeds. A single run is not trustworthy:
     * where in the transfer the losses happen to land moves both goodput and the worst stall by a
     * wide margin, which is why the table reports means alongside the worst observed stall.
     */
    private record Result(String controller, double lossRate, int runs, int completedRuns,
                          double meanGoodputMbps, double meanCompletionMs, double meanStallTotalMs,
                          double meanMaxStallMs, long worstMaxStallMs, long totalLost, long totalRtx) {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        System.out.println("HyperConduit link benchmark");
        System.out.printf("  transfer size  : %,d bytes%n", options.sizeBytes);
        System.out.printf("  one-way latency: %d ms (RTT %d ms)%n", options.latencyMs,
                2 * options.latencyMs);
        System.out.printf("  target rate    : %d Mbit/s per direction%n", options.mbps);
        System.out.printf("  receive window : %,d bytes%n", options.windowBytes);
        System.out.printf("  loss rates     : %s%n", options.lossRates);
        System.out.printf("  controllers    : %s%n", options.controllers);
        System.out.printf("  runs per cell  : %d (independent loss seeds)%n%n", options.runs);

        List<Result> results = new ArrayList<>();
        for (double loss : options.lossRates) {
            for (String controller : options.controllers) {
                System.out.printf("  running cc=%-7s loss=%4.0f%% x%d ...%n",
                        controller, loss * 100, options.runs);
                results.add(runAggregate(controller, loss, options));
            }
        }
        System.out.println();
        print(results);
    }

    private static Result runAggregate(String controller, double loss, Options options) {
        List<SingleRun> runs = new ArrayList<>();
        for (int i = 0; i < options.runs; i++) {
            runs.add(runOnce(controller, loss, options, SimulatedLink.DEFAULT_SEED + i));
        }
        int completed = 0;
        double goodputSum = 0;
        double completionSum = 0;
        double stallSum = 0;
        double maxStallSum = 0;
        long worstMaxStall = 0;
        long lost = 0;
        long rtx = 0;
        for (SingleRun r : runs) {
            lost += r.packetsLost();
            rtx += r.packetsRetransmitted();
            if (!r.completed()) {
                continue;
            }
            completed++;
            goodputSum += r.goodputMbps();
            completionSum += r.completionMs();
            stallSum += r.stallTotalMs();
            maxStallSum += r.maxStallMs();
            worstMaxStall = Math.max(worstMaxStall, r.maxStallMs());
        }
        if (completed == 0) {
            return new Result(controller, loss, runs.size(), 0, 0, 0, 0, 0, 0, lost, rtx);
        }
        return new Result(controller, loss, runs.size(), completed, goodputSum / completed,
                completionSum / completed, stallSum / completed, maxStallSum / completed,
                worstMaxStall, lost, rtx);
    }

    private static SingleRun runOnce(String controller, double loss, Options options, long seed) {
        long bps = options.mbps * 1_000_000L / 8;
        Supplier<CongestionController> cc =
                "reno".equals(controller) ? SessionConfig.reno() : SessionConfig.brutal(bps);

        SessionConfig clientConfig = SessionConfig.client(PSK)
                .connectionId(0xBEEF)
                .congestionController(cc)
                .receiveWindowBytes(options.windowBytes)
                .sendBufferBytes(options.sizeBytes + 64 * 1024);
        SessionConfig serverConfig = SessionConfig.server(PSK)
                .congestionController(cc)
                .receiveWindowBytes(options.windowBytes);

        SimulatedLink link = new SimulatedLink(clientConfig, serverConfig)
                .seed(seed)
                .lossRate(loss)
                .latencyNanos(options.latencyMs * MS);

        if (!link.runUntil(link::bothEstablished, 20 * SECOND)) {
            System.err.printf("    handshake failed on seed %d: %s%n", seed, link.clientError);
            return new SingleRun(false, -1, 0, -1, -1, 0, 0);
        }

        byte[] data = new byte[options.sizeBytes];
        new Random(0xC0FFEEL).nextBytes(data);
        link.clientWrites(data);

        long startNanos = link.elapsedNanos();
        long stallTotal = 0;
        long maxStall = 0;
        long currentStall = 0;
        int lastDelivered = 0;
        boolean completed = false;
        long completionNanos = -1;

        while (link.elapsedNanos() - startNanos < options.timeoutSeconds * SECOND) {
            link.run(MS);
            int delivered = link.serverReceivedCount();
            if (delivered >= options.sizeBytes) {
                completed = true;
                completionNanos = link.elapsedNanos() - startNanos;
                break;
            }
            if (delivered > lastDelivered) {
                lastDelivered = delivered;
                currentStall = 0;
            } else {
                currentStall += MS;
                stallTotal += MS;
                maxStall = Math.max(maxStall, currentStall);
            }
        }

        SessionStats stats = link.client.stats();
        double goodput = completed ? options.sizeBytes * 8.0 / (completionNanos / 1e9) / 1e6 : 0;
        return new SingleRun(completed, completionNanos / MS, goodput, stallTotal / MS, maxStall / MS,
                stats.packetsLost(), stats.packetsRetransmitted());
    }

    private static void print(List<Result> results) {
        System.out.printf("%-8s %6s %8s %11s %10s %11s %10s %11s %9s %8s%n",
                "cc", "loss", "goodput", "time", "stallTotal", "maxStall", "worstStall",
                "lost", "rtx", "runs");
        System.out.println("-".repeat(104));
        for (Result r : results) {
            if (r.completedRuns() == 0) {
                System.out.printf("%-8s %5.0f%% %8s%n", r.controller(), r.lossRate() * 100, "TIMED OUT");
                continue;
            }
            System.out.printf("%-8s %5.0f%% %7.2f Mb/s %7.0f ms %8.0f ms %7.0f ms %8d ms %9d %8d %5d/%d%n",
                    r.controller(), r.lossRate() * 100, r.meanGoodputMbps(), r.meanCompletionMs(),
                    r.meanStallTotalMs(), r.meanMaxStallMs(), r.worstMaxStallMs(), r.totalLost(),
                    r.totalRtx(), r.completedRuns(), r.runs());
        }
        System.out.println();
        System.out.println("Means over all runs; lost/rtx are totals. worstStall is the single longest");
        System.out.println("stretch with zero bytes delivered seen in any run -- the hitch a player feels.");
        System.out.println();
        System.out.println("Both controllers share one userspace loss detector and probe timer, so this");
        System.out.println("isolates the congestion algorithm and pacing. It does NOT model the kernel");
        System.out.println("TCP RTO cliff, which is a separate and large part of the case for this design.");
    }

    private static final class Options {

        int sizeBytes = 2 * 1024 * 1024;
        long latencyMs = 20;
        int mbps = 100;
        int windowBytes = 256 * 1024;
        int timeoutSeconds = 120;
        int runs = 5;
        List<Double> lossRates = List.of(0.0, 0.02, 0.05, 0.10, 0.20);
        List<String> controllers = List.of("brutal", "reno");

        static Options parse(String[] args) {
            Options o = new Options();
            List<Double> losses = new ArrayList<>();
            for (String arg : args) {
                String[] parts = arg.replaceFirst("^--", "").split("=", 2);
                if (parts.length != 2) {
                    System.err.println("ignoring malformed argument: " + arg);
                    continue;
                }
                switch (parts[0]) {
                    case "size" -> o.sizeBytes = Integer.parseInt(parts[1]);
                    case "rtt" -> o.latencyMs = Long.parseLong(parts[1]) / 2;
                    case "latency" -> o.latencyMs = Long.parseLong(parts[1]);
                    case "mbps" -> o.mbps = Integer.parseInt(parts[1]);
                    case "window" -> o.windowBytes = Integer.parseInt(parts[1]);
                    case "timeout" -> o.timeoutSeconds = Integer.parseInt(parts[1]);
                    case "runs" -> o.runs = Math.max(1, Integer.parseInt(parts[1]));
                    case "loss" -> {
                        for (String value : parts[1].split(",")) {
                            losses.add(Double.parseDouble(value.trim()));
                        }
                    }
                    case "cc" -> o.controllers = List.of(parts[1].split(","));
                    default -> System.err.println("ignoring unknown argument: " + arg);
                }
            }
            if (!losses.isEmpty()) {
                o.lossRates = List.copyOf(losses);
            }
            return o;
        }
    }
}
