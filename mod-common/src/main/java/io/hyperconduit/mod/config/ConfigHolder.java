package io.hyperconduit.mod.config;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/** Shared runtime configuration for loader-neutral mixins. */
public final class ConfigHolder {

    private static volatile HyperConduitConfig config;
    private static volatile Path configDir;

    private ConfigHolder() {
    }

    public static void install(HyperConduitConfig config) {
        ConfigHolder.config = config;
    }

    public static void install(HyperConduitConfig config, Path configDir) {
        ConfigHolder.config = config;
        ConfigHolder.configDir = configDir;
    }

    public static void save(HyperConduitConfig config) {
        Path directory = configDir;
        if (directory == null) {
            throw new IllegalStateException("HyperConduit config directory is not installed");
        }
        config.save(directory);
        install(config);
    }

    @Nullable
    public static HyperConduitConfig get() {
        return config;
    }

    public static Path configDir() {
        Path directory = configDir;
        if (directory == null) {
            throw new IllegalStateException("HyperConduit config directory is not installed");
        }
        return directory;
    }
}
