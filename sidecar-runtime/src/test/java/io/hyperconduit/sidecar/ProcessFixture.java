package io.hyperconduit.sidecar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Child-process protocol fixture used by {@link SidecarProcessTest}.
 *
 * <p>This class is not a sidecar server. It only speaks the process protocol (a single JSON
 * {@code ready} event, structured errors on stderr, and the {@code shutdown} command on stdin) so
 * that the parent-side process handling can be exercised without a Go binary.
 */
public final class ProcessFixture {

    /** Marker that must never leak into a parent-side exception message. */
    static final String SECRET = "TOP-SECRET-raw-stderr-must-not-leak";

    /** A structurally valid fingerprint: "SHA256:" followed by 43 base64 characters. */
    static final String FINGERPRINT = "SHA256:" + "A".repeat(43);

    private ProcessFixture() {
    }

    /** Command line that re-launches this fixture in the current JVM. */
    static List<String> command(String mode) {
        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path java = Path.of(javaHome, "bin", windows ? "java.exe" : "java");
        return List.of(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                ProcessFixture.class.getName(),
                mode);
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
        if (args.length != 1) {
            System.err.println("usage: ProcessFixture <mode>");
            System.exit(64);
        }
        switch (args[0]) {
            case "ready" -> {
                emitReady("client", "127.0.0.1:12345");
                awaitShutdown();
            }
            case "neverReady" -> awaitShutdown();
            case "crash" -> System.exit(7);
            case "wrongVersion" -> {
                emit("{\"version\":2,\"event\":\"ready\",\"mode\":\"client\","
                        + "\"listen\":\"127.0.0.1:12345\"}");
                awaitShutdown();
            }
            case "badClientAddr" -> {
                emitReady("client", "0.0.0.0:12345");
                awaitShutdown();
            }
            case "error" -> {
                emitError("{\"version\":1,\"event\":\"error\",\"code\":\"identity_changed\","
                        + "\"op\":\"verify\"}");
                awaitShutdown();
            }
            case "noisy" -> {
                emitNoise();
                emitReady("client", "127.0.0.1:12345");
                awaitShutdown();
            }
            case "tooLong" -> {
                emitTooLongStdoutLine();
                awaitShutdown();
            }
            case "ignoreShutdown" -> {
                emitReady("client", "127.0.0.1:12345");
                while (true) {
                    Thread.sleep(1_000);
                }
            }
            default -> {
                System.err.println("unknown mode: " + args[0]);
                System.exit(64);
            }
        }
    }

    private static void emitReady(String mode, String listen) {
        emit("{\"version\":1,\"event\":\"ready\",\"mode\":\"" + mode + "\",\"listen\":\""
                + listen + "\",\"fingerprint\":\"" + FINGERPRINT + "\"}");
    }

    private static void emit(String json) {
        PrintStream out = System.out;
        out.println(json);
        out.flush();
    }

    private static void emitError(String json) {
        PrintStream err = System.err;
        err.println(json);
        err.flush();
    }

    /**
     * Writes well over 1 MiB of raw, non-JSON stderr. The parent must keep draining this pipe or
     * the fixture would block forever before it can emit {@code ready}.
     */
    private static void emitNoise() throws IOException {
        emitError(SECRET);
        byte[] chunk = new byte[8192];
        Arrays.fill(chunk, (byte) 'x');
        for (int i = 0; i < 160; i++) {
            System.err.write(chunk);
        }
        System.err.write('\n');
        System.err.flush();
    }

    /** Writes a single stdout line larger than the 64 KiB protocol limit. */
    private static void emitTooLongStdoutLine() throws IOException {
        byte[] chunk = new byte[8192];
        Arrays.fill(chunk, (byte) 'x');
        for (int i = 0; i < 9; i++) {
            System.out.write(chunk);
        }
        System.out.write('\n');
        System.out.flush();
    }

    private static void awaitShutdown() throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains("shutdown")) {
                return;
            }
        }
        // EOF is also a clean shutdown request.
    }
}
