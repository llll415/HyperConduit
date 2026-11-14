package io.hyperconduit.sidecar;

import java.time.Duration;
import java.time.Instant;

/**
 * Best-effort point-in-time resource usage of one managed sidecar process.
 *
 * <p>{@link #cpuPercent()} is normalized the way Windows Task Manager reports
 * process CPU: 100 means every logical processor available to this JVM is
 * fully busy, so a process that saturates one of eight cores reports 12.5.
 *
 * <p>{@link #residentMemoryBytes()} is the operating system resident set size
 * (the Windows working set). It is never derived from the Java heap. Both
 * resource fields are {@code null} when the platform probe is unavailable,
 * when the process has already stopped, or before the background sampler has
 * collected its first interval. Callers must not treat {@code null} as zero;
 * {@link SidecarProcess#processStatusPending()} distinguishes the initial
 * sampling window from a value the platform cannot provide.
 *
 * <p>{@link #startedAt()} is {@code null} only when the OS does not expose a
 * process start time. {@link #uptime()} is {@code null} when the start time is
 * unavailable or when the process is no longer running (an uptime for a
 * stopped process would be meaningless).
 */
public record ProcessStatus(
        long pid,
        boolean running,
        Instant startedAt,
        Duration uptime,
        Double cpuPercent,
        Long residentMemoryBytes) {

    public ProcessStatus {
        if (pid <= 0) throw new IllegalArgumentException("PID must be positive");
        if (uptime != null && uptime.isNegative()) {
            throw new IllegalArgumentException("Uptime must not be negative");
        }
        if (cpuPercent != null
                && (!Double.isFinite(cpuPercent) || cpuPercent < 0.0 || cpuPercent > 100.0)) {
            throw new IllegalArgumentException("CPU percent must be between 0 and 100");
        }
        if (residentMemoryBytes != null && residentMemoryBytes < 0) {
            throw new IllegalArgumentException("Resident memory must not be negative");
        }
    }

    /** True when the sampler has produced a CPU value. */
    public boolean cpuAvailable() {
        return cpuPercent != null;
    }

    /** True when the OS probe produced a resident memory value. */
    public boolean memoryAvailable() {
        return residentMemoryBytes != null;
    }
}
