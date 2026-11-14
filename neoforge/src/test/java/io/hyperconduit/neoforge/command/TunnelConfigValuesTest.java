package io.hyperconduit.neoforge.command;

import io.hyperconduit.mod.config.HyperConduitConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunnelConfigValuesTest {

    @Test
    void tokenIsNeverDescribedInClearText() {
        HyperConduitConfig config = new HyperConduitConfig();
        assertEquals("未设置", TunnelConfigValues.describe(config, TunnelConfigKey.SERVER_TOKEN));

        config.serverToken = "s3cret-value";
        assertEquals("已设置", TunnelConfigValues.describe(config, TunnelConfigKey.SERVER_TOKEN));
    }

    @Test
    void settingTokenDoesNotEchoIt() {
        HyperConduitConfig config = new HyperConduitConfig();
        TunnelConfigValues.Change change = TunnelConfigValues.apply(
                config, TunnelConfigKey.SERVER_TOKEN, "s3cret-value");

        assertEquals("s3cret-value", config.serverToken);
        assertFalse(change.detail().contains("s3cret-value"));
        assertFalse(change.warning().contains("s3cret-value"));
        assertTrue(change.warning().contains("日志"));
    }

    @Test
    void clearingTokenWarnsAboutLogsAndOpenAccess() {
        HyperConduitConfig config = new HyperConduitConfig();
        config.serverToken = "s3cret-value";

        TunnelConfigValues.Change change = TunnelConfigValues.apply(
                config, TunnelConfigKey.SERVER_TOKEN, "clear");

        assertEquals("", config.serverToken);
        assertTrue(change.detail().contains("清空"));
        assertTrue(change.warning().contains("日志"));
        assertFalse(change.detail().contains("s3cret-value"));
        assertFalse(change.warning().contains("s3cret-value"));
    }

    @Test
    void rejectsUnsafeTokensWithoutEchoingThem() {
        for (String token : List.of(" leading", "trailing ", "line\nbreak", "密钥")) {
            HyperConduitConfig config = new HyperConduitConfig();
            TunnelCommandException failure = assertThrows(TunnelCommandException.class,
                    () -> TunnelConfigValues.apply(config, TunnelConfigKey.SERVER_TOKEN, token));
            assertFalse(failure.getMessage().contains(token));
        }

        String tooLong = "x".repeat(1025);
        TunnelCommandException failure = assertThrows(TunnelCommandException.class,
                () -> TunnelConfigValues.apply(new HyperConduitConfig(),
                        TunnelConfigKey.SERVER_TOKEN, tooLong));
        assertFalse(failure.getMessage().contains(tooLong));
    }

    @Test
    void appliesIntegerRanges() {
        HyperConduitConfig config = new HyperConduitConfig();

        TunnelConfigValues.apply(config, TunnelConfigKey.UP_MBPS, "1000000");
        assertEquals(1_000_000, config.upMbps);

        TunnelConfigValues.apply(config, TunnelConfigKey.DOWN_MBPS, "0");
        assertEquals(0, config.downMbps);

        TunnelConfigValues.apply(config, TunnelConfigKey.CONNECT_TIMEOUT_MS, "120000");
        assertEquals(120_000, config.connectTimeoutMs);

        TunnelConfigValues.apply(config, TunnelConfigKey.MAX_CONNECTIONS, "4096");
        assertEquals(4096, config.maxConnections);
    }

    @Test
    void rejectsOutOfRangeIntegers() {
        record Case(TunnelConfigKey key, String value) {
        }
        List<Case> cases = List.of(
                new Case(TunnelConfigKey.UP_MBPS, "-1"),
                new Case(TunnelConfigKey.UP_MBPS, "1000001"),
                new Case(TunnelConfigKey.DOWN_MBPS, "1000001"),
                new Case(TunnelConfigKey.CONNECT_TIMEOUT_MS, "99"),
                new Case(TunnelConfigKey.CONNECT_TIMEOUT_MS, "120001"),
                new Case(TunnelConfigKey.MAX_CONNECTIONS, "0"),
                new Case(TunnelConfigKey.MAX_CONNECTIONS, "4097"),
                new Case(TunnelConfigKey.MAX_CONNECTIONS, "abc"));

        for (Case testCase : cases) {
            HyperConduitConfig config = new HyperConduitConfig();
            assertThrows(TunnelCommandException.class,
                    () -> TunnelConfigValues.apply(config, testCase.key(), testCase.value()),
                    testCase.key() + "=" + testCase.value());
        }
    }

    @Test
    void normalizesCongestionAndRejectsUnknownControllers() {
        HyperConduitConfig config = new HyperConduitConfig();
        config.congestion = "reno";

        TunnelConfigValues.apply(config, TunnelConfigKey.CONGESTION, "BBR");
        assertEquals("bbr", config.congestion);

        assertThrows(TunnelCommandException.class,
                () -> TunnelConfigValues.apply(config, TunnelConfigKey.CONGESTION, "paced"));
    }

    @Test
    void appliesBooleans() {
        HyperConduitConfig config = new HyperConduitConfig();

        TunnelConfigValues.apply(config, TunnelConfigKey.DISABLE_BRUTAL, "TRUE");
        assertTrue(config.disableBrutal);

        TunnelConfigValues.apply(config, TunnelConfigKey.DISABLE_BRUTAL, "false");
        assertFalse(config.disableBrutal);

        assertThrows(TunnelCommandException.class,
                () -> TunnelConfigValues.apply(config, TunnelConfigKey.DISABLE_BRUTAL, "on"));
    }

    @Test
    void enablingProxyProtocolRequiresTrustedSources() {
        HyperConduitConfig config = new HyperConduitConfig();

        TunnelCommandException failure = assertThrows(TunnelCommandException.class,
                () -> TunnelConfigValues.apply(config, TunnelConfigKey.PROXY_PROTOCOL_V2, "true"));

        assertTrue(failure.getMessage().contains("可信来源"));
        assertFalse(config.proxyProtocolV2);
    }

    @Test
    void enablingProxyProtocolUsesTheApprovedSwitchWording() {
        HyperConduitConfig config = new HyperConduitConfig();
        config.proxyProtocolTrustedSources = List.of("127.0.0.1");

        TunnelConfigValues.Change change = TunnelConfigValues.apply(
                config, TunnelConfigKey.PROXY_PROTOCOL_V2, "true");

        assertTrue(config.proxyProtocolV2);
        assertTrue(change.detail().contains("已开启 Proxy Protocol v2 接收支持，重启隧道程序后生效"));
        assertTrue(change.detail().contains("仅接受可信转发来源的数据"));
        assertTrue(change.detail().contains("新转发会话必须提供合法的 Proxy Protocol v2 头部"));
        assertTrue(change.detail().contains("普通 QUIC 直连无法接入"));
    }

    @Test
    void disablingProxyProtocolKeepsTrustedSources() {
        HyperConduitConfig config = new HyperConduitConfig();
        config.proxyProtocolV2 = true;
        config.proxyProtocolTrustedSources = List.of("127.0.0.1");

        TunnelConfigValues.Change change = TunnelConfigValues.applyProxyProtocolV2(config, false);

        assertFalse(config.proxyProtocolV2);
        assertEquals(List.of("127.0.0.1"), config.proxyProtocolTrustedSources);
        assertTrue(change.detail().contains("已关闭 Proxy Protocol v2 接收支持"));
        assertTrue(change.detail().contains("同步关闭中转端"));
    }

    @Test
    void saveNoticeUsesTheApprovedCopy() {
        assertEquals("[HyperConduit] 已保存配置，重启隧道程序后生效。"
                        + "请执行 /hyperconduit tunnel restart 应用配置。"
                        + "注意：重启会断开所有通过 HyperConduit 连接的玩家，原版 TCP 连接不受影响。",
                TunnelConfigValues.SAVE_NOTICE);
    }

    @Test
    void appliesCommaSeparatedSources() {
        HyperConduitConfig config = new HyperConduitConfig();

        TunnelConfigValues.Change change = TunnelConfigValues.apply(config,
                TunnelConfigKey.PROXY_PROTOCOL_TRUSTED_SOURCES, "127.0.0.1, 10.0.0.0/8");

        assertEquals(List.of("127.0.0.1", "10.0.0.0/8"), config.proxyProtocolTrustedSources);
        assertTrue(change.detail().contains("2 条"));
    }

    @Test
    void invalidSourcesLeaveThePreviousListUntouched() {
        HyperConduitConfig config = new HyperConduitConfig();
        config.proxyProtocolTrustedSources = List.of("127.0.0.1");

        assertThrows(TunnelCommandException.class,
                () -> TunnelConfigValues.apply(config,
                        TunnelConfigKey.PROXY_PROTOCOL_TRUSTED_SOURCES, "not-an-address"));

        assertEquals(List.of("127.0.0.1"), config.proxyProtocolTrustedSources);
    }

    @Test
    void clearingSourcesIsBlockedWhileProxyProtocolIsEnabled() {
        HyperConduitConfig config = new HyperConduitConfig();
        config.proxyProtocolV2 = true;
        config.proxyProtocolTrustedSources = List.of("127.0.0.1");

        assertThrows(TunnelCommandException.class,
                () -> TunnelConfigValues.apply(config,
                        TunnelConfigKey.PROXY_PROTOCOL_TRUSTED_SOURCES, "clear"));

        assertEquals(List.of("127.0.0.1"), config.proxyProtocolTrustedSources);
    }

    @Test
    void clearingSourcesWhenDisabledEmptiesTheList() {
        HyperConduitConfig config = new HyperConduitConfig();
        config.proxyProtocolTrustedSources = List.of("127.0.0.1");

        TunnelConfigValues.apply(config, TunnelConfigKey.PROXY_PROTOCOL_TRUSTED_SOURCES, "clear");

        assertTrue(config.proxyProtocolTrustedSources.isEmpty());
    }

    @Test
    void explainsConfigCodesWithoutEchoingArbitraryMessages() {
        assertTrue(TunnelConfigValues
                .explain(new IllegalStateException("config_proxy_protocol_sources_required"))
                .contains("可信来源"));

        String secret = "SECRET_TOKEN";
        String message = TunnelConfigValues.explain(new IllegalStateException(secret));
        assertFalse(message.contains(secret));
        assertTrue(message.contains("保存失败"));
    }

    @Test
    void permissionHintUsesApprovedText() {
        assertEquals("权限不足，此命令仅供管理员使用。", TunnelConfigValues.permissionHint());
    }
}
