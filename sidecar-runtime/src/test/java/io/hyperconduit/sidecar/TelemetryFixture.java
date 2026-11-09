package io.hyperconduit.sidecar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Child-process fixture for telemetry tests. It speaks the managed protocol and
 * emits stats only after the parent sends {@code observe}. It never starts
 * Minecraft or a real sidecar.
 */
public final class TelemetryFixture {

    static final String FINGERPRINT = "SHA256:" + "A".repeat(43);
    private static final long FRAME_INTERVAL_MILLIS = 100;

    private TelemetryFixture() {
    }

    static List<String> command(String mode) {
        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path java = Path.of(javaHome, "bin", windows ? "java.exe" : "java");
        return List.of(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                TelemetryFixture.class.getName(),
                mode);
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IOException | InterruptedException e) {
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException, InterruptedException {
        if (args.length != 1) {
            System.err.println("usage: TelemetryFixture <mode>");
            System.exit(64);
        }
        emit("{\"version\":1,\"event\":\"ready\",\"mode\":\"client\",\"listen\":"
                + "\"127.0.0.1:12345\",\"fingerprint\":\"" + FINGERPRINT + "\"}");
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains("\"observe\"")) {
                observe(args[0]);
            } else if (line.contains("\"shutdown\"")) {
                return;
            }
        }
    }

    private static void observe(String mode) {
        switch (mode) {
            case "observe" -> startFrames();
            case "stale" -> emitFrame(1, 100_000_000L, 1_000, 2_000);
            case "crashAfterStats" -> {
                emitFrame(1, 100_000_000L, 1_000, 2_000);
                Thread.ofPlatform().daemon().name("telemetry-fixture-crash").start(() -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    System.exit(9);
                });
            }
            case "invalidStats" -> emit("{\"version\":1,\"event\":\"stats\",\"sequence\":1,");
            default -> {
                // Unknown modes stay alive and emit nothing; tests never use them.
            }
        }
    }

    private static void startFrames() {
        Thread.ofPlatform().daemon().name("telemetry-fixture-frames").start(() -> {
            long sequence = 0;
            long sent = 0;
            long received = 0;
            while (true) {
                sequence++;
                sent += 1_000;
                received += 2_000;
                emitFrame(sequence, sequence * 100_000_000L, sent, received);
                try {
                    Thread.sleep(FRAME_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    private static void emitFrame(long sequence, long elapsedNanos, long sent, long received) {
        emit("{\"version\":1,\"event\":\"stats\",\"sequence\":" + sequence
                + ",\"elapsedNanos\":" + elapsedNanos
                + ",\"connections\":["
                + "{\"id\":1,\"congestion\":\"bbr\",\"txTargetBytesPerSecond\":0,"
                + "\"smoothedRttNanos\":5000000,\"bytesSent\":" + sent
                + ",\"bytesReceived\":" + received + "},"
                + "{\"id\":2,\"congestion\":\"brutal\",\"txTargetBytesPerSecond\":1250000,"
                + "\"smoothedRttNanos\":8000000,\"bytesSent\":" + (sent / 2)
                + ",\"bytesReceived\":" + (received / 2) + "}"
                + "],\"truncated\":false}");
    }

    private static void emit(String json) {
        PrintStream out = System.out;
        out.println(json);
        out.flush();
    }
}
