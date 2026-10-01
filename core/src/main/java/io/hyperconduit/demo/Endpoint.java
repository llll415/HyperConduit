package io.hyperconduit.demo;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Command-line entry point.
 *
 * <pre>
 *   java -jar hyperconduit-core.jar server --bind=0.0.0.0:4444 [--mbps=100] [--cc=brutal]
 *   java -jar hyperconduit-core.jar client --server=HOST:4444 --mode=down|up|rtt|rtt-load
 * </pre>
 *
 * <p>The jar has no dependencies beyond the JDK, so deploying it is copying one file and having a
 * Java 21 runtime on the far end.
 */
public final class Endpoint {

    private Endpoint() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            System.exit(2);
            return;
        }
        Map<String, String> options;
        try {
            options = parse(args);
        } catch (RuntimeException e) {
            System.err.println("error: " + e.getMessage());
            usage();
            System.exit(2);
            return;
        }
        try {
            switch (args[0]) {
                case "server" -> new BenchServer().run(serverOptions(options));
                case "client" -> BenchClient.run(clientOptions(options));
                case "help", "--help", "-h" -> usage();
                default -> {
                    System.err.println("error: unknown command '" + args[0] + "'");
                    usage();
                    System.exit(2);
                }
            }
        } catch (Exception e) {
            System.err.println("error: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String[] parts = args[i].replaceFirst("^--", "").split("=", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("expected --key=value, got '" + args[i] + "'");
            }
            options.put(parts[0], parts[1]);
        }
        return options;
    }

    private static BenchOptions serverOptions(Map<String, String> options) {
        return new BenchOptions(
                address(options.getOrDefault("bind", "0.0.0.0:4444")),
                "server",
                options.getOrDefault("cc", "brutal"),
                intOf(options, "mbps", BenchOptions.DEFAULT_MBPS),
                intOf(options, "window", BenchOptions.DEFAULT_WINDOW),
                longOf(options, "size", BenchOptions.DEFAULT_SIZE),
                intOf(options, "probes", 200),
                longOf(options, "interval", 50),
                intOf(options, "timeout", 120),
                boolOf(options, "verify", true));
    }

    private static BenchOptions clientOptions(Map<String, String> options) {
        String server = options.get("server");
        if (server == null) {
            throw new IllegalArgumentException("--server=<host:port> is required");
        }
        return new BenchOptions(
                address(server),
                options.getOrDefault("mode", "down"),
                options.getOrDefault("cc", "brutal"),
                intOf(options, "mbps", BenchOptions.DEFAULT_MBPS),
                intOf(options, "window", BenchOptions.DEFAULT_WINDOW),
                longOf(options, "size", BenchOptions.DEFAULT_SIZE),
                intOf(options, "probes", 200),
                longOf(options, "interval", 50),
                intOf(options, "timeout", 120),
                boolOf(options, "verify", true));
    }

    private static InetSocketAddress address(String value) {
        int colon = value.lastIndexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("expected host:port, got '" + value + "'");
        }
        return new InetSocketAddress(value.substring(0, colon),
                Integer.parseInt(value.substring(colon + 1)));
    }

    private static int intOf(Map<String, String> options, String key, int fallback) {
        String value = options.get(key);
        return value == null ? fallback : Integer.parseInt(value.trim());
    }

    private static long longOf(Map<String, String> options, String key, long fallback) {
        String value = options.get(key);
        return value == null ? fallback : Long.parseLong(value.trim());
    }

    private static boolean boolOf(Map<String, String> options, String key, boolean fallback) {
        String value = options.get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static void usage() {
        System.out.println("""
                HyperConduit -- userspace reliable UDP tunnel with Brutal congestion control

                Usage:
                  server --bind=<host:port>
                         [--cc=brutal|reno] [--mbps=100] [--window=262144]

                  client --server=<host:port> --mode=<mode>
                         [--cc=brutal|reno] [--mbps=100] [--window=262144]
                         [--size=33554432] [--probes=200] [--interval=50]
                         [--timeout=120] [--verify=true|false]

                Client modes:
                  down       measure downlink throughput only
                  up         measure uplink throughput only
                  rtt        small-probe round-trip latency on an idle tunnel
                  rtt-load   probe latency on one session while a second saturates the
                             downlink -- the measurement that shows whether bulk traffic
                             blocks interactive traffic

                Notes:
                  --mbps is THIS side's Brutal send-rate target. Set it from the receiving
                           end's subscribed bandwidth, not from a measurement of the path:
                           Brutal holds the configured rate regardless of loss, which is the
                           whole point when the loss is a policy rather than congestion.
                  --cc=reno selects an unpaced AIMD baseline, for comparison on the same link.
                  The jar needs only a Java 21+ runtime; it has no other dependencies.
                """);
    }
}
