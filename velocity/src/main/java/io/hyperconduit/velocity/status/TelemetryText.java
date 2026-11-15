package io.hyperconduit.velocity.status;

import io.hyperconduit.sidecar.TelemetrySnapshot;

import java.util.Locale;

/**
 * Velocity-side rendering of the same telemetry semantics as the NeoForge
 * status lines. All directions are local QUIC transport bytes, not Minecraft
 * payload, and unavailable samples are never rendered as zero.
 */
public final class TelemetryText {

    private TelemetryText() {
    }

    public static String describe(TelemetrySnapshot.Connection metrics) {
        if (metrics == null) {
            return "指标暂不可用";
        }
        String congestion = metrics.congestion().toUpperCase(Locale.ROOT);
        if (metrics.congestion().equals("brutal")) {
            congestion += "（目标 " + mbps(metrics.txTargetBytesPerSecond()) + "）";
        }
        String rtt = metrics.smoothedRttNanos() == 0
                ? "待采样"
                : String.format(Locale.ROOT, "%.1f ms", metrics.smoothedRttNanos() / 1_000_000.0);
        String rates = metrics.ratesAvailable()
                ? "发送 " + mbps(metrics.sentBytesPerSecond())
                + " | 接收 " + mbps(metrics.receivedBytesPerSecond())
                : "速率待采样";
        return "本地发送 " + congestion + " | QUIC RTT " + rtt + " | " + rates;
    }

    private static String mbps(double bytesPerSecond) {
        return String.format(Locale.ROOT, "%.2f Mbps", bytesPerSecond * 8 / 1_000_000.0);
    }
}