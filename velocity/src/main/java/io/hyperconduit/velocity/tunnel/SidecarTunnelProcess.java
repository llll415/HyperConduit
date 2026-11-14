package io.hyperconduit.velocity.tunnel;

import io.hyperconduit.sidecar.ProcessStatus;
import io.hyperconduit.sidecar.SidecarProcess;
import io.hyperconduit.sidecar.TelemetrySnapshot;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Production adapter around the shared {@link SidecarProcess} runtime. */
final class SidecarTunnelProcess implements TunnelProcess {

    private final SidecarProcess process;

    SidecarTunnelProcess(SidecarProcess process) {
        this.process = Objects.requireNonNull(process, "process");
    }

    @Override
    public CompletableFuture<SidecarProcess.Ready> ready() {
        return process.ready();
    }

    @Override
    public CompletableFuture<Integer> exit() {
        return process.exit();
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public long pid() {
        return process.pid();
    }

    @Override
    public ProcessStatus processStatus() {
        return process.processStatus();
    }

    @Override
    public boolean processStatusPending() {
        return process.processStatusPending();
    }

    @Override
    public void enableTelemetry() {
        process.enableTelemetry();
    }

    @Override
    public TelemetrySnapshot telemetry() {
        return process.telemetry();
    }

    @Override
    public void close() {
        process.close();
    }
}
