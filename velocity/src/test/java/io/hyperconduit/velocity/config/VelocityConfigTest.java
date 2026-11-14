package io.hyperconduit.velocity.config;

import io.hyperconduit.velocity.command.TunnelConfigKey;
import io.hyperconduit.velocity.command.TunnelConfigValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VelocityConfigTest {
    @TempDir Path root;

    @Test void defaultsAndRoundTripPreserveIdentitySettings() {
        VelocityConfig config = VelocityConfig.load(root);
        assertFalse(config.enabled);
        assertTrue(config.proxyProtocolV2Output);
        assertFalse(config.proxyProtocolV2);
        assertEquals(List.of("127.0.0.1/32", "::1/128"), config.proxyProtocolTrustedSources);
        config.serverToken = "test-secret";
        config.save(root);
        assertEquals(config, VelocityConfig.load(root));
        assertFalse(config.toString().contains("test-secret"));
        VelocityConfig copy = config.copy();
        copy.proxyProtocolTrustedSources.clear();
        assertEquals(2, config.proxyProtocolTrustedSources.size());
    }

    @Test void strictInputRejectsAmbiguityWithoutEchoingSecrets() throws Exception {
        for (String json : List.of("{\"enabled\":true,\"enabled\":false}",
                "{\"unknown\":\"secret\"}", "{\"upMbps\":1.5}",
                "{\"enabled\":\"true\"}", "{} {}", "{\"serverToken\":null}")) {
            Files.writeString(root.resolve(VelocityConfig.FILE_NAME), json);
            var error = assertThrows(IllegalStateException.class, () -> VelocityConfig.load(root));
            assertFalse(error.getMessage().contains("secret"));
        }
    }

    @Test void readsDiskOnEveryOperationAndPreservesEmptySources() throws Exception {
        VelocityConfigStore store = new VelocityConfigStore(root);
        store.update(c -> c.upMbps = 12);
        Files.writeString(store.configFile(), "{\"downMbps\":34,\"proxyProtocolTrustedSources\":[]}");
        store.update(c -> c.upMbps = 56);
        assertEquals(34, store.load().downMbps);
        assertEquals(56, store.load().upMbps);
        assertTrue(store.load().proxyProtocolTrustedSources.isEmpty());
    }

    @Test void invalidTrustAndBandwidthFailBeforeSaving() {
        VelocityConfig config = new VelocityConfig();
        config.proxyProtocolV2 = true;
        config.proxyProtocolTrustedSources = List.of();
        assertThrows(IllegalStateException.class, config::validate);
        for (String source : List.of("example.com", "0.0.0.0/0", "::/0", "::ffff:127.0.0.1")) {
            config.proxyProtocolTrustedSources = List.of(source);
            assertThrows(IllegalStateException.class, config::validate);
        }
        assertThrows(RuntimeException.class, () -> TunnelConfigValues.apply(config,
                TunnelConfigKey.UP_MBPS, "1000001"));
        config.serverToken = "private-token";
        assertEquals("已设置", TunnelConfigValues.describe(config, TunnelConfigKey.SERVER_TOKEN));
    }
}
