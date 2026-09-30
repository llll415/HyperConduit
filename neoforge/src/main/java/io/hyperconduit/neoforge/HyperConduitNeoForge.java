package io.hyperconduit.neoforge;

import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * NeoForge entrypoint: loads the config and hands it to the shared Mixin layer.
 *
 * <p>Everything else — the mixins, the tunnel channels, the congestion control — lives in the
 * loader-agnostic {@code mod-common} module. This class only exists because NeoForge needs a
 * {@link Mod}-annotated class to instantiate, and because the config directory is a loader concept
 * the shared code cannot reach on its own.
 */
@Mod("hyperconduit")
public final class HyperConduitNeoForge {

    public HyperConduitNeoForge() {
        Path configDir = FMLPaths.CONFIGDIR.get();
        HyperConduitConfig config = HyperConduitConfig.load(configDir);
        ConfigHolder.install(config);
        System.out.println("[HyperConduit] config: " + config);
        if (!config.enabled) {
            System.out.println("[HyperConduit] the tunnel is DISABLED, so connections behave exactly "
                    + "as vanilla. To use it, set \"enabled\": true in "
                    + configDir.resolve("hyperconduit.json")
                    + " and set the same \"psk\" on both ends.");
        } else if ("change-me".equals(config.psk)) {
            System.out.println("[HyperConduit] WARNING: the tunnel is enabled but \"psk\" is still "
                    + "the placeholder value. Connections will fail until it is set to a shared "
                    + "secret on both ends.");
        }
    }
}
