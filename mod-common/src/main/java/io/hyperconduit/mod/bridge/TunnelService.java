package io.hyperconduit.mod.bridge;

import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import io.hyperconduit.sidecar.SidecarProcess;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Starts independent managed roles; no global client/server port assumptions. */
public final class TunnelService {
    private TunnelService() {}

    private static Map<String, Object> common(HyperConduitConfig config) {
        Map<String, Object> values = new HashMap<>();
        values.put("upMbps", config.upMbps);
        values.put("downMbps", config.downMbps);
        values.put("congestion", config.congestion);
        values.put("connectTimeoutMs", config.connectTimeoutMs);
        values.put("maxConnections", config.maxConnections);
        values.put("stateDir", ConfigHolder.configDir().resolve("hyperconduit").toAbsolutePath().toString());
        return values;
    }

    public static SidecarProcess client(HyperConduitConfig config, InetSocketAddress destination, String endpoint) throws IOException {
        Map<String, Object> values = common(config);
        values.put("server", format(destination));
        values.put("serverEndpoint", endpoint);
        values.put("token", config.clientToken);
        return start(config, "client", values);
    }

    public static SidecarProcess server(HyperConduitConfig config, InetSocketAddress listen,
                                         InetSocketAddress backend, String bridgeToken) throws IOException {
        Map<String, Object> values = common(config);
        values.put("listen", format(listen));
        values.put("target", format(backend));
        values.put("token", config.serverToken);
        values.put("bridgeToken", bridgeToken);
        values.put("disableBrutal", config.disableBrutal);
        return start(config, "server", values);
    }

    private static SidecarProcess start(HyperConduitConfig config, String mode, Map<String, Object> values) throws IOException {
        Path root = ConfigHolder.configDir().resolve("hyperconduit").toAbsolutePath();
        return SidecarProcess.startConfigured(root.resolve("bin"), root.resolve("launch"), mode, values,
                Path.of("").toAbsolutePath(), Duration.ofMillis(config.connectTimeoutMs + 5000L), Duration.ofSeconds(2));
    }

    public static String format(InetSocketAddress address) {
        String host = address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress();
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + address.getPort();
    }
}
