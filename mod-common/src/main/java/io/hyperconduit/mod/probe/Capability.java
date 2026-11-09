package io.hyperconduit.mod.probe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Public discovery data is advisory, never an authenticated server identity. */
public enum Capability {
    CHECKING, ENABLED, DISABLED, UNDECLARED, UNKNOWN, INCOMPATIBLE;

    /** Current capability wire protocol. Different versions are only shown as incompatible. */
    public static final int PROTOCOL = 2;

    public static Capability parse(JsonElement value) {
        return CapabilityDetails.parse(value).state();
    }

    public static Capability fromProbe(String state) {
        return switch (state) {
            case "enabled" -> ENABLED;
            case "disabled" -> DISABLED;
            case "undeclared" -> UNDECLARED;
            case "incompatible" -> INCOMPATIBLE;
            default -> UNKNOWN;
        };
    }

    /** Legacy state-only helper; the produced object is a complete five-field declaration. */
    public static JsonObject declaration(boolean enabled) {
        return declaration(enabled, false, false, false);
    }

    public static JsonObject declaration(boolean enabled, boolean tcpAllowed,
                                         boolean disableBrutal, boolean tokenRequired) {
        return CapabilityDetails.declaration(enabled, tcpAllowed, disableBrutal, tokenRequired).toJson();
    }

    public boolean declared() { return this == ENABLED || this == DISABLED || this == INCOMPATIBLE; }
}
