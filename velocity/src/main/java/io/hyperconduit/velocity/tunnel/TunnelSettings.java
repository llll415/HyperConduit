package io.hyperconduit.velocity.tunnel;

import io.hyperconduit.velocity.config.VelocityConfig;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the Velocity configuration to the sidecar's strict JSON keys.
 *
 * <p>This class is intentionally pure so the mapping can be tested without Velocity or a running
 * tunnel. It never emits {@code bridgeToken}: Velocity has no Java bridge preface, so the only
 * way to preserve the player address is {@code proxyProtocolV2Output}. The two features are
 * mutually exclusive in the sidecar, and this plugin uses the latter.</p>
 */
public final class TunnelSettings {

    private TunnelSettings() {
    }

    public static Map<String, Object> serverConfig(VelocityConfig config, EndpointResolver.Endpoint endpoint,
                                                   Path stateDirectory) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("listen", EndpointResolver.format(endpoint.listen()));
        values.put("target", EndpointResolver.format(endpoint.target()));
        values.put("token", config.serverToken);
        values.put("upMbps", config.upMbps);
        values.put("downMbps", config.downMbps);
        values.put("congestion", config.congestion);
        values.put("connectTimeoutMs", config.connectTimeoutMs);
        values.put("maxConnections", config.maxConnections);
        values.put("disableBrutal", config.disableBrutal);

        Path state = stateDirectory.toAbsolutePath().normalize();
        values.put("stateDir", state.toString());
        values.put("identityFile", state.resolve("server-identity.json").toString());

        // Velocity owns the public TCP listener and this plugin never closes it. Report the real
        // state instead of the inert disableVanillaTcp flag.
        values.put("tcpAllowed", true);

        values.put("proxyProtocolV2", config.proxyProtocolV2);
        values.put("proxyProtocolTrustedSources", config.proxyProtocolTrustedSources);
        // Only emit the new key when it is actually needed. A false value adds no information,
        // and omitting it keeps the plugin compatible with sidecars that predate the field.
        if (config.proxyProtocolV2Output) {
            values.put("proxyProtocolV2Output", true);
        }
        return values;
    }
}
