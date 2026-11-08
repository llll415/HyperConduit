package io.hyperconduit.sidecar;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one managed process. No automatic restart or transport fallback. */
public final class SidecarProcess implements AutoCloseable {
    public record Ready(int version, String mode, InetSocketAddress listen, String fingerprint) {}

    private static final Set<String> ERROR_CODES = Set.of("config", "tls", "identity_changed", "storage",
            "authentication", "timeout", "canceled", "limit", "target", "transport");
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hyperconduit-sidecar-timer");
        t.setDaemon(true);
        return t;
    });
    private final Process process;
    private final String mode;
    private final Duration stopTimeout;
    private final CompletableFuture<Ready> ready = new CompletableFuture<>();
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    private final CompletableFuture<SidecarException> failure = new CompletableFuture<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicBoolean stopScheduled = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final CountDownLatch readers = new CountDownLatch(2);
    private final Thread hook;
    private volatile ScheduledFuture<?> deadline;
    private volatile PrivateConfig launchConfig;

    public static SidecarProcess start(Path executable, String mode, Path config, Path workingDirectory,
                                       Duration startupTimeout, Duration stopTimeout) throws IOException {
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(executable)) {
            throw new SidecarException("binary_unavailable");
        }
        if (!Files.isRegularFile(config)) throw new SidecarException("config_unavailable");
        return startCommand(List.of(executable.toAbsolutePath().toString(), mode, "--config",
                config.toAbsolutePath().toString(), "--managed"), mode, workingDirectory, startupTimeout, stopTimeout);
    }

    /** Extracts and verifies the bundled executable immediately before launch. */
    public static SidecarProcess startBundled(Path cacheDirectory, String mode, Path config, Path workingDirectory,
                                              Duration startupTimeout, Duration stopTimeout) throws IOException {
        Path executable;
        try { executable = new BinaryExtractor().extract(cacheDirectory); }
        catch (IllegalArgumentException unsupported) { throw new SidecarException("unsupported_platform"); }
        return start(executable, mode, config, workingDirectory, startupTimeout, stopTimeout);
    }

    /** Owns the per-process credential file until the managed process exits. */
    public static SidecarProcess startConfigured(Path cacheDirectory, Path configDirectory, String mode,
                                                 Map<String, ?> config, Path workingDirectory,
                                                 Duration startupTimeout, Duration stopTimeout) throws IOException {
        PrivateConfig file = PrivateConfig.create(configDirectory, config);
        try {
            SidecarProcess child = startBundled(cacheDirectory, mode, file.path(), workingDirectory, startupTimeout, stopTimeout);
            child.launchConfig = file;
            child.exit().whenComplete((code, error) -> {
                try { file.close(); } catch (IOException ignored) { /* private permissions remain in place */ }
            });
            return child;
        } catch (IOException | RuntimeException e) {
            try { file.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    // Also used by process-level fault injection tests; not a public arbitrary launcher.
    static SidecarProcess startCommand(List<String> command, String mode, Path workingDirectory,
                                       Duration startupTimeout, Duration stopTimeout) throws IOException {
        if (!Set.of("client", "server").contains(mode)) throw new IllegalArgumentException("Invalid sidecar mode");
        checkTimeout(startupTimeout);
        checkTimeout(stopTimeout);
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        builder.environment().remove("HYSTERIA_BRUTAL_DEBUG");
        builder.environment().remove("HYSTERIA_BBR_DEBUG");
        Process process = builder.start();
        try {
            return new SidecarProcess(process, mode, startupTimeout, stopTimeout);
        } catch (RuntimeException e) {
            process.destroyForcibly();
            throw e;
        }
    }

    private SidecarProcess(Process process, String mode, Duration startupTimeout, Duration stopTimeout) {
        this.process = process;
        this.mode = mode;
        this.stopTimeout = stopTimeout;
        hook = new Thread(this::close, "hyperconduit-sidecar-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        deadline = TIMER.schedule(() -> fail("startup_timeout"), startupTimeout.toMillis(), TimeUnit.MILLISECONDS);
        pump(process.getInputStream(), true);
        pump(process.getErrorStream(), false);
        Thread.ofPlatform().daemon().name("hyperconduit-sidecar-exit").start(() -> {
            try {
                int code = process.waitFor();
                readers.await(2, TimeUnit.SECONDS); // Allow final structured errors to drain first.
                if (!stopping.get() && !failed.get()) {
                    String reason = ready.isDone() ? "unexpected_exit" : "exited_before_ready";
                    SidecarException error = new SidecarException(reason);
                    failure.complete(error);
                    ready.completeExceptionally(error);
                }
                cancelDeadline();
                removeHook();
                exit.complete(code);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            } finally {
                closeStreams();
            }
        });
        ready.whenComplete((value, error) -> { if (ready.isCancelled()) stopAsync(); });
    }

    public CompletableFuture<Ready> ready() { return ready; }
    public CompletableFuture<Integer> exit() { return exit.copy(); }
    public CompletableFuture<SidecarException> failure() { return failure.copy(); }
    public boolean isAlive() { return process.isAlive(); }
    public long pid() { return process.pid(); }

    private static void checkTimeout(Duration value) {
        if (value == null || value.toMillis() < 1 || value.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Timeout must be between 1ms and 5 minutes");
        }
    }

    private void pump(InputStream input, boolean stdout) {
        Thread.ofPlatform().daemon().name("hyperconduit-sidecar-" + (stdout ? "stdout" : "stderr")).start(() -> {
            try (input) {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int next;
                boolean discard = false;
                while ((next = input.read()) != -1) {
                    if (next == '\n') {
                        if (!discard) event(line.toString(StandardCharsets.UTF_8), stdout);
                        line.reset();
                        discard = false;
                    } else if (!discard) {
                        if (line.size() >= 65536) {
                            if (stdout) fail("protocol_line_too_long");
                            line.reset();
                            discard = true;
                        } else line.write(next);
                    }
                }
                if (!discard && line.size() > 0) event(line.toString(StandardCharsets.UTF_8), stdout);
            } catch (IOException e) {
                if (!stopping.get() && process.isAlive()) fail("output_unavailable");
            } finally {
                readers.countDown();
            }
        });
    }

    private void event(String text, boolean stdout) {
        if (text.isBlank()) return;
        try {
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setLenient(false);
            JsonObject object = new JsonObject();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (object.has(name)) throw new IOException("Duplicate protocol field");
                if (name.equals("version")) {
                    if (reader.peek() != JsonToken.NUMBER || !reader.nextString().equals("1")) {
                        fail("protocol_version"); return;
                    }
                    object.addProperty(name, 1);
                } else {
                    if (reader.peek() != JsonToken.STRING) throw new IOException("Invalid protocol field");
                    if (!Set.of("event", "mode", "listen", "fingerprint", "code", "op").contains(name)) {
                        throw new IOException("Unknown protocol field");
                    }
                    object.addProperty(name, reader.nextString());
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing protocol data");
            if (object.get("version").getAsInt() != 1) { fail("protocol_version"); return; }
            String type = object.get("event").getAsString();
            if ("error".equals(type)) {
                String code = object.get("code").getAsString();
                fail(ERROR_CODES.contains(code) ? code : "transport");
            } else if (stdout && "ready".equals(type)) {
                if (!mode.equals(object.get("mode").getAsString())) { fail("protocol_mode"); return; }
                InetSocketAddress address = parseListen(object.get("listen").getAsString());
                String fingerprint = object.has("fingerprint") ? object.get("fingerprint").getAsString() : "";
                if (!fingerprint.isEmpty() && !fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}")) {
                    fail("protocol_fingerprint"); return;
                }
                if (ready.complete(new Ready(1, mode, address, fingerprint))) cancelDeadline();
                else if (!stopping.get()) fail("protocol_duplicate_ready");
            } else if (stdout) fail("protocol_event");
        } catch (RuntimeException | IOException e) {
            // Raw stderr is consumed but never retained: it may contain secrets.
            if (stdout) fail("protocol_invalid");
        }
    }

    private InetSocketAddress parseListen(String text) throws IOException {
        int colon = text.lastIndexOf(':');
        if (colon < 1) throw new IOException("Invalid listener");
        String host = text.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (!host.matches("[0-9a-fA-F:.]+")) throw new IOException("Listener must be an IP literal");
        if (!host.contains(":")) {
            String[] octets = host.split("\\.", -1);
            if (octets.length != 4) throw new IOException("Invalid IPv4 listener");
            for (String octet : octets) {
                if (!octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255) {
                    throw new IOException("Invalid IPv4 listener");
                }
            }
        }
        InetAddress ip = InetAddress.getByName(host);
        int port = Integer.parseInt(text.substring(colon + 1));
        if (port < 1 || port > 65535 || (mode.equals("client") && !ip.isLoopbackAddress())) {
            throw new IOException("Invalid listener address");
        }
        return new InetSocketAddress(ip, port);
    }

    private void fail(String code) {
        if (!failed.compareAndSet(false, true)) return;
        SidecarException error = new SidecarException(code);
        failure.complete(error);
        ready.completeExceptionally(error);
        stopAsync();
    }

    private void stopAsync() {
        if (!stopScheduled.compareAndSet(false, true)) return;
        Thread.ofPlatform().daemon().name("hyperconduit-sidecar-stop").start(this::close);
    }

    @Override public void close() {
        if (!stopping.compareAndSet(false, true)) {
            try { stopped.await(stopTimeout.toMillis() + 3000, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            cleanupConfig();
            return;
        }
        cancelDeadline();
        if (!ready.isDone()) ready.completeExceptionally(new SidecarException("canceled"));
        List<ProcessHandle> descendants = process.descendants().toList();
        // Never let a child that stopped reading stdin block the shutdown caller.
        Thread.ofPlatform().daemon().name("hyperconduit-sidecar-control").start(() -> {
            try {
                process.getOutputStream().write("{\"version\":1,\"command\":\"shutdown\"}\n".getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().flush();
                process.getOutputStream().close();
            } catch (IOException ignored) {}
        });
        try {
            if (!process.waitFor(stopTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                descendants.forEach(ProcessHandle::destroy);
                process.destroy();
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        } finally {
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            closeStreams();
            removeHook();
            cleanupConfig();
            stopped.countDown();
        }
    }

    private void closeStreams() {
        try { process.getOutputStream().close(); } catch (IOException ignored) {}
        try { process.getInputStream().close(); } catch (IOException ignored) {}
        try { process.getErrorStream().close(); } catch (IOException ignored) {}
    }

    private void cancelDeadline() {
        ScheduledFuture<?> scheduled = deadline;
        if (scheduled != null) scheduled.cancel(false);
    }

    private void cleanupConfig() {
        PrivateConfig config = launchConfig;
        if (config != null && !process.isAlive()) {
            try { config.close(); } catch (IOException ignored) { /* retry on explicit close; never remove unrelated files */ }
        }
    }

    private void removeHook() {
        try { Runtime.getRuntime().removeShutdownHook(hook); }
        catch (IllegalStateException ignored) { /* JVM shutdown is already in progress. */ }
    }
}
