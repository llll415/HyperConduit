package io.hyperconduit.velocity.tunnel;

import io.hyperconduit.velocity.config.VelocityConfig;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class EndpointResolverTest {
    @Test void wildcardTargetsUseMatchingLoopback() {
        for (String host : new String[]{"0.0.0.0", "::"}) {
            var endpoint = EndpointResolver.resolve(new InetSocketAddress(host, 25565)).orElseThrow();
            assertTrue(endpoint.listen().getAddress().isAnyLocalAddress());
            assertTrue(endpoint.target().getAddress().isLoopbackAddress());
            assertEquals(endpoint.listen().getAddress().getAddress().length,
                    endpoint.target().getAddress().getAddress().length);
            assertEquals(25565, endpoint.target().getPort());
        }
        assertTrue(EndpointResolver.resolve(InetSocketAddress.createUnresolved("example.com", 25565)).isEmpty());
        assertTrue(EndpointResolver.resolve(new InetSocketAddress("127.0.0.1", 0)).isEmpty());
    }

    @Test void mapsOutputSeparatelyFromFrpAndNeverEmitsPrivateBridge() {
        var config = new VelocityConfig();
        config.disableVanillaTcp = true;
        config.proxyProtocolV2 = true;
        var endpoint = EndpointResolver.resolve(new InetSocketAddress("127.0.0.1", 25565)).orElseThrow();
        var values = TunnelSettings.serverConfig(config, endpoint, Path.of("state"));
        assertEquals(true, values.get("proxyProtocolV2Output"));
        assertEquals(true, values.get("proxyProtocolV2"));
        assertEquals(true, values.get("tcpAllowed"));
        assertFalse(values.containsKey("bridgeToken"));
        assertEquals("127.0.0.1:25565", values.get("target"));
    }
}
