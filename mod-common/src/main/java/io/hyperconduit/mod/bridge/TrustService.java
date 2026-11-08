package io.hyperconduit.mod.bridge;

import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.sidecar.TrustCommands;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public final class TrustService {
    private TrustService() {}
    public static Map<String, String> list() throws IOException {
        Path root = ConfigHolder.configDir().resolve("hyperconduit").toAbsolutePath();
        return TrustCommands.list(root.resolve("bin"), root.resolve("launch"), root, Path.of("").toAbsolutePath());
    }
    public static void forget(String endpoint) throws IOException {
        Path root = ConfigHolder.configDir().resolve("hyperconduit").toAbsolutePath();
        TrustCommands.forget(root.resolve("bin"), root.resolve("launch"), root, Path.of("").toAbsolutePath(), endpoint);
    }
}
