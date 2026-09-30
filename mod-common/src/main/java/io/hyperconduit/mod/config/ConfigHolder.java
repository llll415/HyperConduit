package io.hyperconduit.mod.config;

import org.jetbrains.annotations.Nullable;

/**
 * Holds the loaded configuration for the mixins to consult.
 *
 * <p>The loader-specific entrypoint (Fabric's {@code onInitialize} or NeoForge's mod constructor)
 * calls {@link #install} once at startup with the parsed config. The mixins only ever read it —
 * they must not reload the file themselves, because Mixin runs before any loader event would let
 * it know where the config directory is.
 */
public final class ConfigHolder {

    private static volatile HyperConduitConfig config;

    private ConfigHolder() {
    }

    public static void install(HyperConduitConfig config) {
        ConfigHolder.config = config;
    }

    @Nullable
    public static HyperConduitConfig get() {
        return config;
    }
}
