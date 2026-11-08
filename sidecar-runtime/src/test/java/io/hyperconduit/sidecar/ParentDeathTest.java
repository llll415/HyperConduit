package io.hyperconduit.sidecar;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-binary orphan test: a parent JVM starts a managed Go sidecar and is then killed without
 * running any Java shutdown hook. The Go child must stop because its stdin pipe reaches EOF.
 */
class ParentDeathTest {

    @Test
    void killingParentJvmStopsManagedGoChild(@TempDir Path directory) throws Exception {
        Path binary = new BinaryExtractor().extract(directory.resolve("bin"));
        Path config = directory.resolve("server.json");
        Files.writeString(config, new Gson().toJson(Map.of(
                "target", "localhost:25565",
                "stateDir", directory.resolve("state").toString())));

        Process parent = null;
        BufferedReader parentOutput = null;
        ProcessHandle child = null;
        try {
            List<String> command = List.of(
                    javaExecutable().toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    ParentCrashFixture.class.getName(),
                    binary.toString(),
                    config.toString(),
                    directory.toString());
            parent = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();

            BufferedReader output = new BufferedReader(new InputStreamReader(
                    parent.getInputStream(), StandardCharsets.UTF_8));
            parentOutput = output;
            CompletableFuture<String> pidLine = CompletableFuture.supplyAsync(() -> {
                try {
                    return output.readLine();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });

            String reported = pidLine.get(10, TimeUnit.SECONDS);
            assertNotNull(reported, "fixture exited before reporting the Go child pid");
            long childPid = Long.parseLong(reported.trim());
            assertTrue(childPid > 0, "invalid Go child pid: " + childPid);

            child = ProcessHandle.of(childPid).orElseThrow(
                    () -> new AssertionError("managed Go child is not running: " + childPid));
            assertTrue(child.isAlive(), "managed Go child is not alive: " + childPid);

            // TerminateProcess / SIGKILL: no shutdown hook, no close(), no protocol shutdown.
            assertTrue(parent.destroyForcibly().waitFor(5, TimeUnit.SECONDS),
                    "fixture parent JVM did not terminate");

            awaitDeath(child, Duration.ofSeconds(5));
        } finally {
            if (parent != null) {
                parent.destroyForcibly();
                try {
                    parent.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (parentOutput != null) {
                try {
                    parentOutput.close();
                } catch (Exception ignored) {
                    // Best-effort cleanup only.
                }
            }
            // Only ever kill the exact child handle that was reported by the fixture.
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }

    private static void awaitDeath(ProcessHandle child, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (child.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(child.isAlive(), "managed Go child survived parent death: " + child.pid());
    }

    private static Path javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }
}
