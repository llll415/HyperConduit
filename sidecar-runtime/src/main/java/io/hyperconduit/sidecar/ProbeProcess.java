package io.hyperconduit.sidecar;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns one long-lived {@code hyperconduit-tunnel probe} child and multiplexes bounded capability
 * probes over it. The child is launched without a config file and without {@code --managed}; it
 * never spawns per request and is never restarted.
 *
 * <p>Wire format (newline-delimited JSON):
 * <pre>
 * child -> parent  {"version":1,"event":"ready"}
 * parent -> child  {"version":1,"command":"probe","id":1,"server":"127.0.0.1:443"}
 * parent -> child  {"version":1,"command":"cancel","id":1}
 * parent -> child  {"version":1,"command":"shutdown"}
 * child -> parent  {"version":1,"event":"result","id":1,"state":"enabled",
 *                   "capability":{"protocol":2,"enabled":true,"tcpAllowed":true,
 *                                 "disableBrutal":false,"tokenRequired":true}}
 * </pre>
 *
 * <p>{@link #probe(InetSocketAddress)} completes with exactly one of {@code enabled},
 * {@code disabled}, {@code undeclared}, {@code unknown} or {@code incompatible};
 * {@link #probeDetails(InetSocketAddress)} additionally carries the exact five-field capability
 * object, and only for a valid compatible declaration. Readiness and probes are bounded to five
 * seconds; a timed-out probe completes with {@code unknown} and is cancelled on the wire. Output
 * is strict: lines are at most 4096 bytes, duplicate/unknown fields are rejected, and a result is
 * accepted only after a successful {@code ready}. stderr is drained and never retained.
 *
 * <p>All worker threads are daemon threads. {@link #close()} is bounded, sends {@code shutdown},
 * then escalates to destroy; do not call it from a render thread. The child also exits on stdin
 * EOF, so a parent JVM that dies without its hook does not leave an orphan.
 */
public final class ProbeProcess implements AutoCloseable {

    /**
     * Exact result state strings accepted on the wire and returned by {@link #probe}.
     */
    public static final Set<String> STATES = Set.of(
            "enabled", "disabled", "undeclared", "unknown", "incompatible");

    /** Capability wire protocol carried inside {@link Declaration}. */
    public static final int CAPABILITY_PROTOCOL = 2;

    private static final int PROTOCOL_VERSION = 1;

    /**
     * One probe result. {@code capability} is non-null only for a valid compatible declaration;
     * unknown, undeclared, incompatible and timeout results carry no policy at all.
     */
    public record Details(String state, Declaration capability) {
        public static Details state(String state) {
            return new Details(state, null);
        }

        public boolean declared() {
            return capability != null;
        }
    }

    /** Public capability object; it contains no credentials or token material. */
    public record Declaration(int protocol, boolean enabled, boolean tcpAllowed,
                              boolean disableBrutal, boolean tokenRequired) {
        public boolean compatible() {
            return protocol == CAPABILITY_PROTOCOL;
        }
    }
    private static final int MAX_PENDING = 32;
    private static final int MAX_LINE_BYTES = 4096;
    private static final int WRITE_QUEUE_CAPACITY = 64;
    private static final Duration DEFAULT_READY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration FORCE_KILL_TIMEOUT = Duration.ofMillis(500);

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hyperconduit-probe-timer");
        t.setDaemon(true);
        return t;
    });

    private final Process process;
    private final Duration shutdownTimeout;
    private final Duration probeTimeout;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    private final CompletableFuture<SidecarException> failure = new CompletableFuture<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicBoolean exited = new AtomicBoolean();
    private final AtomicBoolean stopScheduled = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final CountDownLatch readers = new CountDownLatch(2);
    private final Object pendingLock = new Object();
    private final Map<Long, Pending> pending = new HashMap<>();
    private final ArrayBlockingQueue<Write> writes = new ArrayBlockingQueue<>(WRITE_QUEUE_CAPACITY);
    private final Object writeLock = new Object();
    private final Thread hook;
    private final Thread sender;
    private volatile ScheduledFuture<?> deadline;
    private long nextId = 1; // Guarded by pendingLock.

    /**
     * Extracts the bundled executable and starts it in probe mode.
     *
     * <p>Extraction may take a while and happens synchronously on the caller's thread. The method
     * returns as soon as the process is launched; readiness is reported through {@link #ready()}.
     */
    public static ProbeProcess startBundled(Path cacheDirectory, Path workingDirectory) throws IOException {
        Path executable;
        try {
            executable = new BinaryExtractor().extract(cacheDirectory);
        } catch (IllegalArgumentException unsupported) {
            throw new SidecarException("unsupported_platform");
        }
        return startCommand(List.of(executable.toAbsolutePath().toString(), "probe"), workingDirectory);
    }

    /**
     * Starts an arbitrary command with the probe protocol. Package-visible for process-level fault
     * tests; production callers use {@link #startBundled(Path, Path)}.
     */
    static ProbeProcess startCommand(List<String> command, Path workingDirectory) throws IOException {
        return startCommand(command, workingDirectory, DEFAULT_READY_TIMEOUT,
                DEFAULT_PROBE_TIMEOUT, DEFAULT_SHUTDOWN_TIMEOUT);
    }

    /** Test hook that shortens the bounded deadlines without changing production limits. */
    static ProbeProcess startCommand(List<String> command, Path workingDirectory,
                                     Duration readyTimeout, Duration probeTimeout,
                                     Duration shutdownTimeout) throws IOException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        if (command.isEmpty()) throw new IllegalArgumentException("Command must not be empty");
        checkTimeout(readyTimeout);
        checkTimeout(probeTimeout);
        checkTimeout(shutdownTimeout);
        if (readyTimeout.compareTo(DEFAULT_READY_TIMEOUT) > 0) {
            throw new IllegalArgumentException("Ready timeout must not exceed 5 seconds");
        }
        if (probeTimeout.compareTo(DEFAULT_PROBE_TIMEOUT) > 0) {
            throw new IllegalArgumentException("Probe timeout must not exceed 5 seconds");
        }

        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        builder.environment().remove("HYSTERIA_BRUTAL_DEBUG");
        builder.environment().remove("HYSTERIA_BBR_DEBUG");
        Process process = builder.start();
        try {
            return new ProbeProcess(process, readyTimeout, probeTimeout, shutdownTimeout);
        } catch (RuntimeException e) {
            process.destroyForcibly();
            throw e;
        }
    }

    private ProbeProcess(Process process, Duration readyTimeout, Duration probeTimeout,
                         Duration shutdownTimeout) {
        this.process = process;
        this.probeTimeout = probeTimeout;
        this.shutdownTimeout = shutdownTimeout;
        hook = new Thread(this::close, "hyperconduit-probe-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        deadline = TIMER.schedule(() -> {
            if (!ready.isDone()) fail("ready_timeout");
        }, readyTimeout.toMillis(), TimeUnit.MILLISECONDS);
        ready.whenComplete((value, error) -> {
            if (ready.isCancelled()) stopAsync();
        });
        pumpStdout();
        pumpStderr();
        sender = Thread.ofPlatform().daemon().name("hyperconduit-probe-writer").start(this::writeLoop);
        Thread.ofPlatform().daemon().name("hyperconduit-probe-exit").start(this::watchExit);
    }

    /** Completes when the child has emitted its single {@code ready} event. */
    public CompletableFuture<Void> ready() {
        return ready;
    }

    /** Completes with the child's exit code. */
    public CompletableFuture<Integer> exit() {
        return exit.copy();
    }

    /** Completes with a safe, code-only failure if the process or protocol fails. */
    public CompletableFuture<SidecarException> failure() {
        return failure.copy();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public long pid() {
        return process.pid();
    }

    /**
     * Queues one capability probe for the already-resolved endpoint.
     *
     * <p>The returned future completes with one of {@link #STATES}. A probe that reaches its
     * bounded deadline completes with {@code unknown} and sends {@code cancel} when the request
     * was already written. It fails with a {@link SidecarException} whose code is {@code limit}
     * when 32 requests are already pending, {@code canceled} when the caller cancels the future,
     * or a process-level code such as {@code unexpected_exit}.
     *
     * @param server resolved IP and port; the address must not be unresolved and no DNS lookup is
     *               performed here
     */
    public CompletableFuture<String> probe(InetSocketAddress server) {
        CompletableFuture<Details> details = probeDetails(server);
        CompletableFuture<String> state = new CompletableFuture<>();
        details.whenComplete((value, error) -> {
            if (error != null) state.completeExceptionally(error);
            else state.complete(value.state());
        });
        // Cancelling the compatibility wrapper must cancel the actual pending wire request.
        state.whenComplete((value, error) -> {
            if (state.isCancelled()) details.cancel(true);
        });
        return state;
    }

    /**
     * Queues one capability probe and returns the structured declaration. The returned future
     * fails with the same codes as {@link #probe(InetSocketAddress)}; cancelling it sends a wire
     * cancel for a request that was already written.
     */
    public CompletableFuture<Details> probeDetails(InetSocketAddress server) {
        Objects.requireNonNull(server, "server");
        InetAddress address = server.getAddress();
        if (address == null) throw new IllegalArgumentException("Probe server must be resolved");
        int port = server.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid probe port");
        String endpoint = formatEndpoint(address, port);

        Pending request;
        synchronized (pendingLock) {
            if (stopping.get() || failed.get()) return failedFuture("not_running");
            if (pending.size() >= MAX_PENDING) return failedFuture("limit");
            long id = allocateId();
            request = new Pending(id);
            pending.put(id, request);
        }

        CompletableFuture<Details> result = request.result;
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) cancel(request.id);
        });

        request.timeout = TIMER.schedule(() -> timeout(request.id), probeTimeout.toMillis(),
                TimeUnit.MILLISECONDS);
        synchronized (request) {
            if (request.done) cancelTimer(request);
        }

        ready.whenComplete((ignored, error) -> {
            if (error != null) {
                Pending removed = removePending(request.id);
                if (removed != null) finishFailure(removed, asSidecarException(error));
            } else {
                enqueue(request.id, requestLine(request.id, endpoint));
            }
        });
        return result;
    }

    private static void checkTimeout(Duration value) {
        if (value == null || value.toMillis() < 1 || value.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Timeout must be between 1ms and 5 minutes");
        }
    }

    private static String formatEndpoint(InetAddress address, int port) {
        String host = address.getHostAddress();
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("Invalid probe host");
        if (host.indexOf(':') >= 0) return "[" + host + "]:" + port;
        return host + ":" + port;
    }

    private static String requestLine(long id, String endpoint) {
        JsonObject request = new JsonObject();
        request.addProperty("version", PROTOCOL_VERSION);
        request.addProperty("command", "probe");
        request.addProperty("id", id);
        request.addProperty("server", endpoint);
        return request + "\n";
    }

    private static String cancelLine(long id) {
        JsonObject request = new JsonObject();
        request.addProperty("version", PROTOCOL_VERSION);
        request.addProperty("command", "cancel");
        request.addProperty("id", id);
        return request + "\n";
    }

    private static <T> CompletableFuture<T> failedFuture(String code) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(new SidecarException(code));
        return future;
    }

    private long allocateId() {
        // Guarded by pendingLock. Skip zero and wrap before overflow; never reuse a pending id.
        for (int attempt = 0; attempt <= MAX_PENDING; attempt++) {
            long candidate = nextId;
            nextId = candidate == Long.MAX_VALUE ? 1 : candidate + 1;
            if (candidate > 0 && !pending.containsKey(candidate)) return candidate;
        }
        throw new IllegalStateException("No probe request id is available");
    }

    private Pending removePending(long id) {
        synchronized (pendingLock) {
            return pending.remove(id);
        }
    }

    private Pending pendingFor(long id) {
        synchronized (pendingLock) {
            return pending.get(id);
        }
    }

    private void enqueue(long id, String line) {
        if (stopping.get() || failed.get()) {
            Pending removed = removePending(id);
            if (removed != null) finishFailure(removed, new SidecarException("not_running"));
            return;
        }
        if (!writes.offer(new Write(id, line))) {
            Pending removed = removePending(id);
            if (removed != null) finishFailure(removed, new SidecarException("limit"));
        }
    }

    private void enqueueControl(String line) {
        // Control messages are best effort: their pending request is already gone.
        writes.offer(new Write(0, line));
    }

    private void timeout(long id) {
        Pending request = removePending(id);
        if (request == null) return;
        boolean sent;
        synchronized (request) {
            if (request.done) return;
            request.done = true;
            sent = request.sent;
        }
        cancelTimer(request);
        request.result.complete(Details.state("unknown"));
        if (sent) enqueueControl(cancelLine(id));
    }

    private void cancel(long id) {
        Pending request = removePending(id);
        if (request == null) return;
        boolean sent;
        synchronized (request) {
            if (request.done) return;
            request.done = true;
            sent = request.sent;
        }
        cancelTimer(request);
        request.result.completeExceptionally(new SidecarException("canceled"));
        if (sent) enqueueControl(cancelLine(id));
    }

    private void complete(long id, Details details) {
        Pending request = removePending(id);
        if (request == null) return; // Late result after timeout/cancel is ignored.
        synchronized (request) {
            if (request.done) return;
            request.done = true;
        }
        cancelTimer(request);
        request.result.complete(details);
    }

    private void finishFailure(Pending request, SidecarException error) {
        synchronized (request) {
            if (request.done) return;
            request.done = true;
        }
        cancelTimer(request);
        request.result.completeExceptionally(error);
    }

    private static void cancelTimer(Pending request) {
        ScheduledFuture<?> task = request.timeout;
        if (task != null) task.cancel(false);
    }

    private void writeLoop() {
        try {
            while (!stopping.get() && !exited.get()) {
                Write item = writes.poll(100, TimeUnit.MILLISECONDS);
                if (item == null) continue;
                if (item.id != 0) {
                    Pending request = pendingFor(item.id);
                    if (request == null) continue;
                    synchronized (request) {
                        if (request.done) continue;
                        request.sent = true;
                    }
                }
                if (stopping.get() || exited.get()) return;
                try {
                    writeLine(item.line);
                } catch (IOException e) {
                    // A process that just died is reported by watchExit, not as a write error.
                    if (!stopping.get() && process.isAlive()) fail("output_unavailable");
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeLine(String line) throws IOException {
        synchronized (writeLock) {
            OutputStream out = process.getOutputStream();
            out.write(line.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private void sendShutdown() {
        try {
            synchronized (writeLock) {
                OutputStream out = process.getOutputStream();
                out.write("{\"version\":1,\"command\":\"shutdown\"}\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException ignored) {
            // The child may already be gone; the bounded destroy path below still runs.
        }
    }

    private void pumpStdout() {
        Thread.ofPlatform().daemon().name("hyperconduit-probe-stdout").start(() -> {
            try (InputStream input = process.getInputStream()) {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int next;
                boolean discard = false;
                while ((next = input.read()) != -1) {
                    if (next == '\n') {
                        if (!discard && line.size() > 0) {
                            try {
                                event(decode(line));
                            } catch (IOException invalid) {
                                fail("protocol_invalid");
                            }
                        }
                        line.reset();
                        discard = false;
                    } else if (!discard) {
                        if (line.size() >= MAX_LINE_BYTES) {
                            fail("protocol_line_too_long");
                            line.reset();
                            discard = true;
                        } else {
                            line.write(next);
                        }
                    }
                }
                if (!discard && line.size() > 0) {
                    try {
                        event(decode(line));
                    } catch (IOException invalid) {
                        fail("protocol_invalid");
                    }
                }
            } catch (IOException e) {
                if (!stopping.get() && process.isAlive()) fail("output_unavailable");
            } finally {
                readers.countDown();
            }
        });
    }

    private void pumpStderr() {
        Thread.ofPlatform().daemon().name("hyperconduit-probe-stderr").start(() -> {
            try (InputStream input = process.getErrorStream()) {
                byte[] buffer = new byte[8192];
                while (input.read(buffer) != -1) {
                    // Raw stderr may contain secrets: consume it and never retain or log it.
                }
            } catch (IOException e) {
                if (!stopping.get() && process.isAlive()) fail("output_unavailable");
            } finally {
                readers.countDown();
            }
        });
    }

    private static String decode(ByteArrayOutputStream line) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(line.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Invalid UTF-8", e);
        }
    }

    private void event(String text) {
        if (text.isBlank()) return;
        try {
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setLenient(false);
            Set<String> seen = new HashSet<>();
            Integer version = null;
            String type = null;
            Long id = null;
            String state = null;
            Declaration capability = null;

            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!seen.add(name)) throw new IOException("Duplicate protocol field");
                switch (name) {
                    case "version" -> version = readVersion(reader);
                    case "event" -> type = readString(reader);
                    case "id" -> id = readId(reader);
                    case "state" -> state = readString(reader);
                    case "capability" -> capability = readCapability(reader);
                    default -> throw new IOException("Unknown protocol field");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Trailing protocol data");
            }
            if (version == null || version != PROTOCOL_VERSION) {
                fail("protocol_version");
                return;
            }
            if (type == null) throw new IOException("Missing protocol event");

            if ("ready".equals(type)) {
                if (id != null || state != null || capability != null) {
                    throw new IOException("Unexpected ready field");
                }
                if (ready.isDone()) {
                    if (!stopping.get()) fail("protocol_duplicate_ready");
                    return;
                }
                cancelDeadline();
                ready.complete(null);
            } else if ("result".equals(type)) {
                if (id == null || state == null) throw new IOException("Missing result field");
                // A result before a successful ready would let the parent trust an answer from a
                // child that never completed its handshake.
                if (!ready.isDone() || ready.isCompletedExceptionally()) {
                    fail("protocol_invalid");
                    return;
                }
                if (!STATES.contains(state)) {
                    fail("protocol_state");
                    return;
                }
                boolean declared = "enabled".equals(state) || "disabled".equals(state);
                if (declared) {
                    // A valid declaration must carry the complete capability object, and its
                    // enabled flag must agree with the state.
                    if (capability == null
                            || !capability.compatible()
                            || capability.enabled() != "enabled".equals(state)) {
                        fail("protocol_capability");
                        return;
                    }
                    complete(id, new Details(state, capability));
                } else {
                    if (capability != null) {
                        fail("protocol_capability");
                        return;
                    }
                    complete(id, Details.state(state));
                }
            } else {
                fail("protocol_event");
            }
        } catch (RuntimeException | IOException invalid) {
            fail("protocol_invalid");
        }
    }

    private static Integer readVersion(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw new IOException("Version must be a number");
        String raw = reader.nextString();
        if (!raw.matches("0|[1-9][0-9]{0,8}")) throw new IOException("Invalid version");
        return Integer.parseInt(raw);
    }

    private static Long readId(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw new IOException("Id must be a number");
        String raw = reader.nextString();
        if (!raw.matches("[1-9][0-9]{0,18}")) throw new IOException("Id must be a positive integer");
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) throw new IOException("Id must be positive");
            return value;
        } catch (NumberFormatException overflow) {
            throw new IOException("Id is out of range", overflow);
        }
    }

    private static String readString(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw new IOException("Expected a string");
        return reader.nextString();
    }

    /** Strictly reads the exact five-field capability object; no token or unknown fields. */
    private static Declaration readCapability(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            throw new IOException("Capability must be an object");
        }
        reader.beginObject();
        Set<String> seen = new HashSet<>(5);
        Integer protocol = null;
        Boolean enabled = null;
        Boolean tcpAllowed = null;
        Boolean disableBrutal = null;
        Boolean tokenRequired = null;
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!seen.add(name)) throw new IOException("Duplicate capability field");
            switch (name) {
                case "protocol" -> {
                    if (reader.peek() != JsonToken.NUMBER) throw new IOException("Invalid capability protocol");
                    String raw = reader.nextString();
                    if (!raw.matches("[1-9][0-9]{0,8}")) throw new IOException("Invalid capability protocol");
                    protocol = Integer.parseInt(raw);
                }
                case "enabled" -> {
                    if (reader.peek() != JsonToken.BOOLEAN) throw new IOException("Invalid capability enabled");
                    enabled = reader.nextBoolean();
                }
                case "tcpAllowed" -> {
                    if (reader.peek() != JsonToken.BOOLEAN) throw new IOException("Invalid capability tcpAllowed");
                    tcpAllowed = reader.nextBoolean();
                }
                case "disableBrutal" -> {
                    if (reader.peek() != JsonToken.BOOLEAN) throw new IOException("Invalid capability disableBrutal");
                    disableBrutal = reader.nextBoolean();
                }
                case "tokenRequired" -> {
                    if (reader.peek() != JsonToken.BOOLEAN) throw new IOException("Invalid capability tokenRequired");
                    tokenRequired = reader.nextBoolean();
                }
                default -> throw new IOException("Unknown capability field");
            }
        }
        reader.endObject();
        if (protocol == null || protocol != CAPABILITY_PROTOCOL) {
            throw new IOException("Unsupported capability protocol");
        }
        if (seen.size() != 5 || enabled == null || tcpAllowed == null
                || disableBrutal == null || tokenRequired == null) {
            throw new IOException("Missing capability field");
        }
        return new Declaration(protocol, enabled, tcpAllowed, disableBrutal, tokenRequired);
    }

    private void watchExit() {
        int code;
        try {
            code = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            readers.await(1, TimeUnit.SECONDS); // Let final results drain before declaring failure.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        exited.set(true);
        sender.interrupt();
        if (!stopping.get() && !failed.get()) {
            failWithoutStop(ready.isDone() ? "unexpected_exit" : "exited_before_ready");
        }
        cancelDeadline();
        removeHook();
        exit.complete(code);
        closeStreams();
    }

    private void fail(String code) {
        if (!failed.compareAndSet(false, true)) return;
        SidecarException error = new SidecarException(code);
        failure.complete(error);
        ready.completeExceptionally(error);
        failAllPending(error);
        cancelDeadline();
        stopAsync();
    }

    private void failWithoutStop(String code) {
        if (!failed.compareAndSet(false, true)) return;
        SidecarException error = new SidecarException(code);
        failure.complete(error);
        ready.completeExceptionally(error);
        failAllPending(error);
    }

    private void failAllPending(SidecarException error) {
        List<Pending> copy;
        synchronized (pendingLock) {
            copy = new ArrayList<>(pending.values());
            pending.clear();
        }
        for (Pending request : copy) {
            finishFailure(request, error);
        }
    }

    private static SidecarException asSidecarException(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof SidecarException sidecar) return sidecar;
        return new SidecarException("not_running");
    }

    private void stopAsync() {
        if (!stopScheduled.compareAndSet(false, true)) return;
        Thread.ofPlatform().daemon().name("hyperconduit-probe-stop").start(this::close);
    }

    private void cancelDeadline() {
        ScheduledFuture<?> scheduled = deadline;
        if (scheduled != null) scheduled.cancel(false);
    }

    @Override
    public void close() {
        if (!stopping.compareAndSet(false, true)) {
            try {
                stopped.await(shutdownTimeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }

        cancelDeadline();
        SidecarException canceled = new SidecarException("canceled");
        if (!ready.isDone()) ready.completeExceptionally(canceled);
        failAllPending(canceled);
        writes.clear();
        if (sender != null) sender.interrupt();

        List<ProcessHandle> descendants = process.descendants().toList();
        // Never let a child that stopped reading stdin block the shutdown caller.
        Thread.ofPlatform().daemon().name("hyperconduit-probe-control").start(this::sendShutdown);
        try {
            if (!process.waitFor(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                descendants.forEach(ProcessHandle::destroy);
                process.destroy();
                if (!process.waitFor(FORCE_KILL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
                descendants.stream().filter(ProcessHandle::isAlive)
                        .forEach(ProcessHandle::destroyForcibly);
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        } finally {
            descendants.stream().filter(ProcessHandle::isAlive)
                    .forEach(ProcessHandle::destroyForcibly);
            closeStreams();
            removeHook();
            stopped.countDown();
        }
    }

    private void closeStreams() {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
        }
        try {
            process.getErrorStream().close();
        } catch (IOException ignored) {
        }
    }

    private void removeHook() {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown is already in progress.
        }
    }

    private static final class Pending {
        private final long id;
        private final CompletableFuture<Details> result = new CompletableFuture<>();
        private boolean sent; // Guarded by this.
        private boolean done; // Guarded by this.
        private volatile ScheduledFuture<?> timeout;

        private Pending(long id) {
            this.id = id;
        }
    }

    /** Queue item; id 0 means a control line whose request is already gone. */
    private record Write(long id, String line) {
    }
}
