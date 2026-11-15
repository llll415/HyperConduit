package io.hyperconduit.velocity.status;

import com.google.gson.JsonObject;
import io.hyperconduit.velocity.config.VelocityConfig;

/**
 * The five-field HyperConduit discovery object attached to the status JSON.
 *
 * <p>The wire shape is identical to the NeoForge status declaration:
 * {@code {protocol:2, enabled, tcpAllowed, disableBrutal, tokenRequired}}.
 * Values come only from the running sidecar configuration snapshot. In
 * Velocity, {@code tcpAllowed} is always true because the proxy's TCP listener
 * remains open; it describes that structural fact and is not an access-control
 * guarantee.</p>
 */
public final class StatusDeclaration {

    public static final int PROTOCOL = 2;

    private StatusDeclaration() {
    }

    /** Returns the declaration, or {@code null} when no tunnel is running. */
    public static JsonObject fromRunning(VelocityConfig runningConfig) {
        if (runningConfig == null || !runningConfig.enabled) {
            return null;
        }
        JsonObject declaration = new JsonObject();
        declaration.addProperty("protocol", PROTOCOL);
        declaration.addProperty("enabled", true);
        declaration.addProperty("tcpAllowed", true);
        declaration.addProperty("disableBrutal", runningConfig.disableBrutal);
        declaration.addProperty("tokenRequired",
                runningConfig.serverToken != null && !runningConfig.serverToken.isEmpty());
        return declaration;
    }
}