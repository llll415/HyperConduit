package io.hyperconduit.mod.probe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Public discovery data is advisory, never an authenticated server identity. */
public enum Capability {
    CHECKING, ENABLED, DISABLED, UNDECLARED, UNKNOWN, INCOMPATIBLE;

    public static final int PROTOCOL = 1;

    public static Capability parse(JsonElement value) {
        if (value == null) return UNDECLARED;
        if (!value.isJsonObject()) return UNKNOWN;
        JsonObject json = value.getAsJsonObject();
        try {
            if (json.size() != 2 || !json.has("protocol") || !json.has("enabled")
                    || !json.get("protocol").isJsonPrimitive() || !json.get("enabled").isJsonPrimitive()
                    || !json.getAsJsonPrimitive("protocol").isNumber() || !json.getAsJsonPrimitive("enabled").isBoolean()) return UNKNOWN;
            String protocol = json.get("protocol").getAsString();
            if (!protocol.matches("[1-9][0-9]{0,8}")) return UNKNOWN;
            if (Integer.parseInt(protocol) != PROTOCOL) return INCOMPATIBLE;
            return json.get("enabled").getAsBoolean() ? ENABLED : DISABLED;
        } catch (RuntimeException invalid) { return UNKNOWN; }
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

    public static JsonObject declaration(boolean enabled) {
        JsonObject json = new JsonObject();
        json.addProperty("protocol", PROTOCOL);
        json.addProperty("enabled", enabled);
        return json;
    }

    public boolean declared() { return this == ENABLED || this == DISABLED || this == INCOMPATIBLE; }
}
