package io.hyperconduit.velocity.tunnel;

import io.hyperconduit.sidecar.ProcessStatus;
import io.hyperconduit.sidecar.SidecarProcess;
import io.hyperconduit.sidecar.TelemetrySnapshot;

import java.util.concurrent.CompletableFuture;

/**
 * The narrow view of a managed sidecar process that the controller needs.
 *
 * <p>Production delegates to {@link SidecarProcess}; tests can provide a deterministic fake
 * without launching a real binary. The interface deliberately exposes no mutable process handle.
 */
public interface TunnelProcess {

    CompletableFuture<SidecarProcess.Ready> ready();

    CompletableFuture<Integer> exit();

    boolean isAlive();

    long pid();

    ProcessStatus processStatus();

    boolean processStatusPending();

    void enableTelemetry();

    TelemetrySnapshot telemetry();

    void close();
}
