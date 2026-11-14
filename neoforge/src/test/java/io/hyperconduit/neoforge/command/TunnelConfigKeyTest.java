package io.hyperconduit.neoforge.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunnelConfigKeyTest {

    @Test
    void exposesStableKeysAndCompletionValues() {
        assertTrue(TunnelConfigKey.keys().contains("proxyProtocolV2"));
        assertTrue(TunnelConfigKey.keys().contains("proxyProtocolTrustedSources"));
        assertTrue(TunnelConfigKey.keys().contains("serverToken"));

        assertEquals(List.of("bbr", "reno"), TunnelConfigKey.CONGESTION.valueSuggestions());
        assertEquals(List.of("true", "false"), TunnelConfigKey.PROXY_PROTOCOL_V2.valueSuggestions());
        assertEquals(List.of("clear"),
                TunnelConfigKey.PROXY_PROTOCOL_TRUSTED_SOURCES.valueSuggestions());
    }

    @Test
    void onlyTheServerTokenIsSecret() {
        for (TunnelConfigKey key : TunnelConfigKey.values()) {
            if (key == TunnelConfigKey.SERVER_TOKEN) {
                assertTrue(key.secret());
            } else {
                assertFalse(key.secret());
            }
        }
    }

    @Test
    void exposesIntegerBoundsUsedByValidation() {
        assertEquals(0, TunnelConfigKey.UP_MBPS.minValue());
        assertEquals(1_000_000, TunnelConfigKey.UP_MBPS.maxValue());
        assertEquals(0, TunnelConfigKey.DOWN_MBPS.minValue());
        assertEquals(1_000_000, TunnelConfigKey.DOWN_MBPS.maxValue());
        assertEquals(100, TunnelConfigKey.CONNECT_TIMEOUT_MS.minValue());
        assertEquals(120_000, TunnelConfigKey.CONNECT_TIMEOUT_MS.maxValue());
        assertEquals(1, TunnelConfigKey.MAX_CONNECTIONS.minValue());
        assertEquals(4096, TunnelConfigKey.MAX_CONNECTIONS.maxValue());
    }

    @Test
    void commandPathPointsAtTheTunnelConfigSubtree() {
        assertEquals("/hyperconduit tunnel config upMbps", TunnelConfigKey.UP_MBPS.commandPath());
        assertFalse(TunnelConfigKey.SERVER_TOKEN.commandPath().contains("s3cret"));
    }
}
