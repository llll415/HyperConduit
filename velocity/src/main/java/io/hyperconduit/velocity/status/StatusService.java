package io.hyperconduit.velocity.status;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.hyperconduit.sidecar.TelemetrySnapshot;
import io.hyperconduit.velocity.config.VelocityConfig;
import io.hyperconduit.velocity.tunnel.TunnelProcess;
import io.hyperconduit.velocity.tunnel.VelocityTunnelController;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Gathers one consistent running snapshot for status and watch output. */
public final class StatusService {

    private final ProxyServer server;
    private final VelocityTunnelController controller;

    public StatusService(ProxyServer server, VelocityTunnelController controller) {
        this.server = Objects.requireNonNull(server, "server");
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    /**
     * Builds status lines from the running configuration and the live sidecar
     * telemetry. It never reads the on-disk configuration, so a saved but
     * not-yet-applied edit cannot appear as effective state.
     */
    public List<String> lines() {
        VelocityConfig running = controller.runningConfig();
        boolean processRunning = controller.isRunning();
        TelemetrySnapshot snapshot = telemetry(processRunning);
        return StatusLines.build(running, processRunning, snapshot, players());
    }

    private TelemetrySnapshot telemetry(boolean processRunning) {
        if (!processRunning) {
            return null;
        }
        TunnelProcess process = controller.process();
        if (process == null) {
            return null;
        }
        try {
            return process.telemetry();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private List<PlayerView> players() {
        List<PlayerView> views = new ArrayList<>();
        for (Player player : server.getAllPlayers()) {
            InetSocketAddress primary = PlayerEndpoints.realTcpPeer(player);
            InetSocketAddress fallback;
            try {
                fallback = player.getRemoteAddress();
            } catch (RuntimeException unavailable) {
                fallback = null;
            }
            views.add(new PlayerView(player.getUniqueId(), player.getUsername(), primary, fallback));
        }
        views.sort(Comparator.comparing(PlayerView::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(player -> player.uuid().toString()));
        return views;
    }
}