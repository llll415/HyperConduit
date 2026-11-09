package io.hyperconduit.sidecar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Child-process fixture used by {@link ProbeProcessTest}.
 *
 * <p>It is not the real Go probe implementation. It only speaks the newline-delimited JSON probe
 * protocol so that the Java parent can be exercised without a Go toolchain. Fault modes emit
 * malformed, oversized or duplicated protocol output; behavior modes answer probes from the
 * requested port number:
 *
 * <pre>
 * 1001 enabled, 1002 disabled, 1003 undeclared, 1004 unknown, 1005 incompatible
 * </pre>
 */
public final class ProbeFixture {

    /** Marker that must never leak into a parent-side exception message. */
    static final String SECRET = "TOP-SECRET-probe-stderr-must-not-leak";

    private ProbeFixture() {
    }

    /** Command line that re-launches this fixture in the current JVM. */
    static List<String> command(String mode, String... extra) {
        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path java = Path.of(javaHome, "bin", windows ? "java.exe" : "java");
        List<String> command = new java.util.ArrayList<>(List.of(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                ProbeFixture.class.getName(),
                mode));
        command.addAll(Arrays.asList(extra));
        return List.copyOf(command);
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IOException e) {
            // The parent may close our pipes while forcibly stopping us; exit quietly.
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException, InterruptedException {
        if (args.length < 1) {
            System.err.println("usage: ProbeFixture <mode> [marker]");
            System.exit(64);
        }
        String mode = args[0];
        Path marker = args.length > 1 ? Path.of(args[1]) : null;
        switch (mode) {
            case "ready" -> {
                emitReady();
                serve("normal", marker);
            }
            case "delayedReady" -> {
                Thread.sleep(250);
                emitReady();
                serve("normal", marker);
            }
            case "neverReady" -> Thread.sleep(60_000);
            case "crash" -> System.exit(7);
            case "wrongVersion" -> {
                emit("{\"version\":2,\"event\":\"ready\"}");
                drain();
            }
            case "duplicateReady" -> {
                emitReady();
                emitReady();
                serve("normal", marker);
            }
            case "readyExtraField" -> {
                emit("{\"version\":1,\"event\":\"ready\",\"mode\":\"client\"}");
                drain();
            }
            case "tooLong" -> {
                emitLongLine();
                drain();
            }
            case "badJson" -> {
                emit("not-json");
                drain();
            }
            case "resultBeforeReady" -> {
                awaitMarker(marker);
                emitResult(1, "enabled");
                emitReady();
                serve("normal", marker);
            }
            case "invalidUtf8" -> {
                emitInvalidUtf8();
                drain();
            }
            case "badState" -> {
                emitReady();
                serve("badState", marker);
            }
            case "duplicateResultField" -> {
                emitReady();
                serve("duplicateResultField", marker);
            }
            case "unknownResultField" -> {
                emitReady();
                serve("unknownResultField", marker);
            }
            case "unknownResultId" -> {
                emitReady();
                serve("unknownResultId", marker);
            }
            case "exitOnProbe" -> {
                emitReady();
                serve("exitOnProbe", marker);
            }
            case "neverRespond" -> {
                emitReady();
                serve("neverRespond", marker);
            }
            case "cancelExits" -> {
                emitReady();
                serve("cancelExits", marker);
            }
            case "ignoreShutdown" -> {
                emitReady();
                serve("ignoreShutdown", marker);
            }
            case "noisy" -> {
                emitNoise();
                emitReady();
                serve("normal", marker);
            }
            default -> {
                System.err.println("unknown mode: " + mode);
                System.exit(64);
            }
        }
    }

    private static void serve(String behavior, Path marker) throws IOException, InterruptedException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            JsonObject request;
            try {
                request = JsonParser.parseString(line).getAsJsonObject();
            } catch (RuntimeException invalid) {
                continue;
            }
            String command = request.has("command") && request.get("command").isJsonPrimitive()
                    ? request.get("command").getAsString() : "";
            if ("shutdown".equals(command)) {
                if ("ignoreShutdown".equals(behavior)) {
                    while (true) {
                        Thread.sleep(1_000);
                    }
                }
                return;
            }
            if ("cancel".equals(command)) {
                if ("cancelExits".equals(behavior)) return;
                continue;
            }
            if (!"probe".equals(command)) continue;

            long id = request.get("id").getAsLong();
            String server = request.get("server").getAsString();
            switch (behavior) {
                case "badState" -> emitResult(id, "bogus");
                case "duplicateResultField" -> emitRaw("{\"version\":1,\"event\":\"result\",\"id\":"
                        + id + ",\"state\":\"enabled\",\"state\":\"disabled\"}");
                case "unknownResultField" -> emitRaw("{\"version\":1,\"event\":\"result\",\"id\":"
                        + id + ",\"state\":\"enabled\",\"extra\":1}");
                case "unknownResultId" -> {
                    emitResult(id + 1000, "enabled");
                    emitResult(id, stateFor(server));
                }
                case "exitOnProbe" -> System.exit(9);
                case "neverRespond" -> {
                    // Deliberately leave the request pending until the parent times out.
                }
                case "cancelExits" -> {
                    if (marker != null) Files.writeString(marker, "probe");
                }
                default -> emitResult(id, stateFor(server));
            }
        }
    }

    private static String stateFor(String server) {
        int colon = server.lastIndexOf(':');
        String port = colon < 0 ? "" : server.substring(colon + 1);
        return switch (port) {
            case "1001" -> "enabled";
            case "1002" -> "disabled";
            case "1003" -> "undeclared";
            case "1004" -> "unknown";
            case "1005" -> "incompatible";
            default -> "enabled";
        };
    }

    private static void emitReady() {
        emit("{\"version\":1,\"event\":\"ready\"}");
    }

    private static void emitResult(long id, String state) {
        emit("{\"version\":1,\"event\":\"result\",\"id\":" + id
                + ",\"state\":\"" + state + "\"}");
    }

    private static void emit(String json) {
        PrintStream out = System.out;
        out.println(json);
        out.flush();
    }

    private static void emitRaw(String json) {
        emit(json);
    }

    /** Writes a stdout line larger than the parent's 4096-byte protocol limit. */
    private static void emitLongLine() throws IOException {
        byte[] chunk = new byte[8192];
        Arrays.fill(chunk, (byte) 'x');
        System.out.write(chunk);
        System.out.write('\n');
        System.out.flush();
    }

    /** Writes a non-UTF-8 line; the parent must reject it before parsing JSON. */
    private static void emitInvalidUtf8() throws IOException {
        System.out.write(new byte[]{(byte) 0xC3, (byte) 0x28});
        System.out.write('\n');
        System.out.flush();
    }

    /** Writes well over 1 MiB of raw, non-JSON stderr that must be drained but never retained. */
    private static void emitNoise() throws IOException {
        System.err.println(SECRET);
        byte[] chunk = new byte[8192];
        Arrays.fill(chunk, (byte) 'x');
        for (int i = 0; i < 160; i++) {
            System.err.write(chunk);
        }
        System.err.write('\n');
        System.err.flush();
    }

    /** Reads control input until shutdown or EOF; used by modes that emitted invalid output. */
    private static void drain() throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains("shutdown")) return;
        }
    }

    /** Waits until the parent has queued a request, so a pre-ready result has a pending id. */
    private static void awaitMarker(Path marker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (marker == null || !Files.exists(marker)) {
            if (System.nanoTime() >= deadline) return;
            Thread.sleep(10);
        }
    }
}
