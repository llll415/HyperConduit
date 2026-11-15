package io.hyperconduit.velocity.status;

import io.hyperconduit.sidecar.TelemetrySnapshot;
import io.hyperconduit.velocity.config.VelocityConfig;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusLinesTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void reportsNotRunningWithoutInventingTunnelCounts() throws Exception {
        List<String> lines = StatusLines.build(null, false, null, List.of(
                player(ALICE, "Alice", "127.0.0.1", 40001),
                player(BOB, "Bob", "127.0.0.1", 40002)));

        assertTrue(lines.get(0).contains("未运行"));
        assertTrue(lines.stream().anyMatch(line -> line.contains("隧道程序：未运行")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("总计 | 隧道 0 | 未识别 2")));
    }

    @Test
    void telemetryUnavailableIsUnknownNotZero() throws Exception {
        VelocityConfig running = running();
        List<String> lines = StatusLines.build(running, true, null,
                List.of(player(ALICE, "Alice", "127.0.0.1", 40001)));

        assertTrue(lines.stream().anyMatch(line -> line.contains("遥测暂不可用")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("[Alice] 未知 | 遥测不可用")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("总计 | 隧道 未知 | 未识别 未知")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("0.00 Mbps")));
    }

    @Test
    void associatesOnlyTheExactEndpointAndShowsUnknownForTheRest() throws Exception {
        VelocityConfig running = running();
        TelemetrySnapshot snapshot = snapshot(
                Map.of(7L, connection(7L, "bbr", true, 1_250_000, 2_500_000)),
                List.of(new TelemetrySnapshot.Bridge(7L, 0L,
                        address("127.0.0.1", 40001), address("203.0.113.5", 5555))));
        List<String> lines = StatusLines.build(running, true, snapshot, List.of(
                player(ALICE, "Alice", "127.0.0.1", 40001),
                player(BOB, "Bob", "127.0.0.1", 40002)));

        assertTrue(lines.get(0).contains("TCP 始终开放"));
        assertTrue(lines.stream().anyMatch(line -> line.contains("[Alice] 隧道 源 203.0.113.5:5555")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("本地发送 BBR")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("[Bob] 未知 | 未匹配到隧道")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("总计 | 隧道 1 | 未识别 1")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("原版TCP")));
    }

    @Test
    void logicalFallbackIsIgnoredWhenProxyProtocolMayRewriteIt() throws Exception {
        VelocityConfig running = running();
        running.proxyProtocolV2Output = true;
        TelemetrySnapshot snapshot = snapshot(Map.of(), List.of(new TelemetrySnapshot.Bridge(
                7L, 0L, address("127.0.0.1", 40001), address("203.0.113.5", 5555))));
        PlayerView alice = new PlayerView(ALICE, "Alice", null, address("127.0.0.1", 40001));

        List<String> rewritten = StatusLines.build(running, true, snapshot, List.of(alice));
        assertTrue(rewritten.stream().anyMatch(line -> line.contains("[Alice] 未知")));

        running.proxyProtocolV2Output = false;
        List<String> direct = StatusLines.build(running, true, snapshot, List.of(alice));
        assertTrue(direct.stream().anyMatch(line -> line.contains("[Alice] 隧道")));
    }

    @Test
    void missingConnectionMetricsAreUnavailableNotZero() throws Exception {
        VelocityConfig running = running();
        TelemetrySnapshot snapshot = snapshot(Map.of(), List.of(new TelemetrySnapshot.Bridge(
                9L, 0L, address("127.0.0.1", 40001), address("203.0.113.5", 5555))));

        List<String> lines = StatusLines.build(running, true, snapshot,
                List.of(player(ALICE, "Alice", "127.0.0.1", 40001)));

        assertTrue(lines.stream().anyMatch(line -> line.contains("指标暂不可用")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("0.00 Mbps")));
    }

    private static VelocityConfig running() {
        VelocityConfig config = new VelocityConfig();
        config.enabled = true;
        return config;
    }

    private static PlayerView player(UUID uuid, String name, String host, int port) throws Exception {
        return new PlayerView(uuid, name, address(host, port), null);
    }

    private static TelemetrySnapshot snapshot(
            Map<Long, TelemetrySnapshot.Connection> connections,
            List<TelemetrySnapshot.Bridge> bindings) {
        return new TelemetrySnapshot(1, 1, false, System.nanoTime(), connections, bindings);
    }

    private static TelemetrySnapshot.Connection connection(long id, String congestion,
                                                           boolean ratesAvailable,
                                                           double sent, double received) {
        return new TelemetrySnapshot.Connection(id, congestion, 0, 5_000_000,
                10_000, 20_000, ratesAvailable, sent, received);
    }

    private static InetSocketAddress address(String host, int port) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(host), port);
    }
}