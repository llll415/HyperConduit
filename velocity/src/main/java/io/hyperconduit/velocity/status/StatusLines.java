package io.hyperconduit.velocity.status;

import io.hyperconduit.sidecar.TelemetrySnapshot;
import io.hyperconduit.velocity.config.VelocityConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure status-line builder shared by {@code /hyperconduit status} and the
 * one-second watch task.
 *
 * <p>The line structure follows the NeoForge server status: one running-config
 * line, one line per online player, and a totals line. Velocity has no local
 * memory connection and cannot prove that an unmatched player used vanilla TCP,
 * so unmatched players are reported as {@code 未知} instead of being counted as
 * vanilla TCP.</p>
 */
public final class StatusLines {

    private static final String PREFIX = "[HyperConduit] ";

    private StatusLines() {
    }

    public static List<String> build(VelocityConfig runningConfig, boolean processRunning,
                                     TelemetrySnapshot snapshot, List<PlayerView> players) {
        List<PlayerView> online = players == null ? List.of() : players;
        List<String> lines = new ArrayList<>();
        lines.add(PREFIX + "服务端配置 | " + configStatus(runningConfig, processRunning));
        if (!processRunning) {
            lines.add(PREFIX + "隧道程序：未运行");
            lines.add(PREFIX + "总计 | 隧道 0 | 未识别 " + online.size());
            return lines;
        }
        if (snapshot == null) {
            lines.add(PREFIX + "遥测暂不可用：无法关联玩家与隧道");
            for (PlayerView player : online) {
                lines.add(PREFIX + "[" + player.name() + "] 未知 | 遥测不可用");
            }
            lines.add(PREFIX + "总计 | 隧道 未知 | 未识别 未知");
            return lines;
        }

        boolean allowLogicalFallback = runningConfig == null || !runningConfig.proxyProtocolV2Output;
        Map<UUID, TunnelAssociation.Match> matches =
                TunnelAssociation.associate(online, snapshot, allowLogicalFallback);
        int tunnels = 0;
        int unknown = 0;
        for (PlayerView player : online) {
            TunnelAssociation.Match match = matches.get(player.uuid());
            if (match == null) {
                unknown++;
                lines.add(PREFIX + "[" + player.name() + "] 未知 | 未匹配到隧道");
                continue;
            }
            tunnels++;
            TelemetrySnapshot.Connection metrics =
                    snapshot.connections().get(match.bridge().connectionId());
            lines.add(PREFIX + "[" + player.name() + "] 隧道 源 "
                    + match.bridge().peerAddress() + " | " + TelemetryText.describe(metrics));
        }
        lines.add(PREFIX + "总计 | 隧道 " + tunnels + " | 未识别 " + unknown);
        return lines;
    }

    private static String configStatus(VelocityConfig runningConfig, boolean processRunning) {
        if (runningConfig == null) {
            return processRunning ? "运行中（配置快照不可用）" : "未运行";
        }
        if (!runningConfig.enabled) {
            return "已禁用";
        }
        return "已启用 | " + (runningConfig.disableBrutal ? "Brutal 禁用" : "允许协商 Brutal")
                + " | TCP 始终开放（不可关闭）";
    }
}