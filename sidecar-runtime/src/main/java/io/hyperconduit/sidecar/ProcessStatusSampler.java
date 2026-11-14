package io.hyperconduit.sidecar;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background, best-effort sampler for one managed process.
 *
 * <p>CPU is derived from the monotonic {@link ProcessHandle#info()
 * totalCpuDuration} delta over wall-clock time and normalized to every logical
 * processor available to this JVM. RSS comes from {@link ProcessMemory} and is
 * cached between (potentially slower) command-based probes.
 *
 * <p>The sampler owns a dedicated daemon thread, created lazily on first use
 * and shut down by {@link #stop()}. OS probes never run on the startup-deadline
 * timer or on a game/caller thread.
 *
 * <p>Every failure is contained: a sampler can only make a field unavailable,
 * it can never disturb the managed process.
 */
final class ProcessStatusSampler {

    private final ProcessHandle handle;
    private final long pid;
    private final Instant startedAt;
    private final long intervalMillis;
    private final long memoryIntervalNanos;
    private final int processors;
    private final Instant createdAt;
    private final long createdNanos;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> task;
    private volatile ProcessStatus latest;
    private volatile boolean stopped;
    private volatile boolean firstSampleComplete;
    private long previousCpuNanos = -1;
    private long previousSampleNanos;
    private long memorySampledNanos;
    private Long cachedMemory;

    ProcessStatusSampler(ProcessHandle handle, long pid, Instant startedAt,
                         Duration interval, Duration memoryInterval) {
        this.handle = handle;
        this.pid = pid;
        this.startedAt = startedAt;
        this.intervalMillis = Math.max(1, interval.toMillis());
        this.memoryIntervalNanos = Math.max(0, memoryInterval.toNanos());
        this.processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        this.createdAt = Instant.now();
        this.createdNanos = System.nanoTime();
    }

    /** Starts the sampler once; repeated calls are cheap no-ops. */
    void start() {
        if (stopped || !alive() || !started.compareAndSet(false, true)) return;
        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hyperconduit-sidecar-status-" + pid);
            thread.setDaemon(true);
            return thread;
        });
        executor = created;
        if (stopped) {
            // stop() won the race before the executor was published.
            firstSampleComplete = true;
            created.shutdownNow();
            if (executor == created) executor = null;
            return;
        }
        try {
            ScheduledFuture<?> scheduled = created.scheduleWithFixedDelay(
                    this::tick, 0, intervalMillis, TimeUnit.MILLISECONDS);
            task = scheduled;
            // stop() may have raced with the assignment above; never leave an
            // unreachable periodic task running.
            if (stopped) scheduled.cancel(false);
        } catch (RuntimeException unavailable) {
            // A rejected scheduler only makes status unavailable; it must not
            // escape into the game thread.
            stopped = true;
            firstSampleComplete = true;
            created.shutdownNow();
            if (executor == created) executor = null;
        }
    }

    /** Returns the latest sample, or a cheap base sample before the first tick. */
    ProcessStatus current() {
        if (!alive()) return stopped();
        ProcessStatus snapshot = latest;
        return snapshot != null
                ? snapshot
                : new ProcessStatus(pid, true, startedAt, uptime(), null, null);
    }

    /**
     * True while the sampler is waiting for its first CPU observation. A
     * stopped process, a failed sampler, or a platform without CPU timing is
     * never reported as pending; those cases are "unavailable", not "loading".
     */
    boolean pending() {
        return alive() && !firstSampleComplete;
    }

    /** Stops future ticks and shuts down the sampler's daemon thread. */
    void stop() {
        stopped = true;
        firstSampleComplete = true;
        ScheduledFuture<?> scheduled = task;
        if (scheduled != null) scheduled.cancel(false);
        ScheduledExecutorService running = executor;
        executor = null;
        if (running != null) running.shutdownNow();
    }

    private void tick() {
        if (stopped) return;
        try {
            if (!alive()) {
                firstSampleComplete = true;
                latest = stopped();
                stop();
                return;
            }
            long now = System.nanoTime();
            Long cpuNanos = cpuNanos();
            Double percent = null;
            if (cpuNanos != null && previousCpuNanos >= 0 && previousSampleNanos != 0) {
                percent = cpuPercent(cpuNanos - previousCpuNanos, now - previousSampleNanos,
                        processors);
            }
            // The first CPU percentage needs two observations. If CPU timing
            // is unavailable, stop reporting "loading" so the UI can show
            // "unavailable" instead.
            if (percent != null || cpuNanos == null) firstSampleComplete = true;
            if (cpuNanos != null) {
                previousCpuNanos = cpuNanos;
                previousSampleNanos = now;
            }
            latest = new ProcessStatus(pid, true, startedAt, uptime(), percent, memory(now));
        } catch (RuntimeException ignored) {
            // Resource sampling is optional; never let it affect the tunnel.
        }
    }

    private boolean alive() {
        try {
            return handle.isAlive();
        } catch (RuntimeException unavailable) {
            // A security manager must not turn a status query into a crash.
            return false;
        }
    }

    private Long cpuNanos() {
        try {
            Optional<Duration> total = handle.info().totalCpuDuration();
            return total.map(Duration::toNanos).orElse(null);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private Long memory(long now) {
        if (memorySampledNanos == 0 || now - memorySampledNanos >= memoryIntervalNanos) {
            cachedMemory = ProcessMemory.read(pid);
            memorySampledNanos = now;
        }
        return cachedMemory;
    }

    private Duration uptime() {
        if (startedAt == null) return null;
        long elapsed = System.nanoTime() - createdNanos;
        if (elapsed < 0) return null;
        Duration base = Duration.between(startedAt, createdAt);
        // Clock resolution can place the reported start a few milliseconds
        // after the sampler was created; do not turn that into "unavailable".
        if (base.isNegative()) base = Duration.ZERO;
        return base.plusNanos(elapsed);
    }

    private ProcessStatus stopped() {
        return new ProcessStatus(pid, false, startedAt, null, null, null);
    }

    /**
     * Converts a CPU-time delta to a percentage of all available logical
     * processors. Returns {@code null} for a reset counter, a non-positive
     * interval, or an impossible processor count.
     */
    static Double cpuPercent(long deltaCpuNanos, long deltaWallNanos, int processors) {
        if (deltaCpuNanos < 0 || deltaWallNanos <= 0 || processors < 1) return null;
        double value = (double) deltaCpuNanos * 100.0
                / ((double) deltaWallNanos * processors);
        if (!Double.isFinite(value)) return null;
        return Math.max(0.0, Math.min(100.0, value));
    }
}
