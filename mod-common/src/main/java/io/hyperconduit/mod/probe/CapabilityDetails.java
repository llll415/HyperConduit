package io.hyperconduit.mod.probe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Objects;

/**
 * Immutable discovery declaration. The three policy flags are present only for a compatible
 * declaration; every other state (unknown, undeclared, incompatible, checking) carries nulls so
 * callers can never mistake "no evidence" for a policy choice.
 *
 * <p>The wire object is exactly five fields:
 * {@code {protocol:2, enabled:bool, tcpAllowed:bool, disableBrutal:bool, tokenRequired:bool}}.
 * A valid declaration is itself the evidence that HyperConduit is installed and supports
 * discovery; no separate installed flag exists.
 */
public record CapabilityDetails(Capability state, Boolean tcpAllowed, Boolean disableBrutal,
                                Boolean tokenRequired) {

    public static final CapabilityDetails CHECKING =
            new CapabilityDetails(Capability.CHECKING, null, null, null);
    public static final CapabilityDetails UNDECLARED =
            new CapabilityDetails(Capability.UNDECLARED, null, null, null);
    public static final CapabilityDetails UNKNOWN =
            new CapabilityDetails(Capability.UNKNOWN, null, null, null);
    public static final CapabilityDetails INCOMPATIBLE =
            new CapabilityDetails(Capability.INCOMPATIBLE, null, null, null);

    public CapabilityDetails {
        Objects.requireNonNull(state, "state");
    }

    /** State-only view kept for the legacy enum-based API. */
    public static CapabilityDetails of(Capability state) {
        if (state == null) return UNDECLARED;
        return switch (state) {
            case CHECKING -> CHECKING;
            case UNDECLARED -> UNDECLARED;
            case UNKNOWN -> UNKNOWN;
            case INCOMPATIBLE -> INCOMPATIBLE;
            case ENABLED, DISABLED -> new CapabilityDetails(state, null, null, null);
        };
    }

    /** True only for a compatible enabled/disabled declaration. */
    public boolean hasPolicy() {
        return state == Capability.ENABLED || state == Capability.DISABLED;
    }

    /** Alias used by connection-time transport discovery; incomplete declarations are never compatible. */
    public boolean compatible() {
        return policyComplete();
    }

    /** True only when the remote explicitly declared enabled=true. */
    public boolean enabled() {
        return state == Capability.ENABLED;
    }

    /** True when this is a complete, compatible declaration safe to use for transport choice. */
    public boolean policyComplete() {
        return hasPolicy() && tcpAllowed != null && disableBrutal != null && tokenRequired != null;
    }

    public static CapabilityDetails declaration(boolean enabled, boolean tcpAllowed,
                                                boolean disableBrutal, boolean tokenRequired) {
        return new CapabilityDetails(enabled ? Capability.ENABLED : Capability.DISABLED,
                tcpAllowed, disableBrutal, tokenRequired);
    }

    public static CapabilityDetails fromProbe(String state, Boolean tcpAllowed,
                                              Boolean disableBrutal, Boolean tokenRequired) {
        Capability capability = Capability.fromProbe(state);
        if (capability == Capability.ENABLED || capability == Capability.DISABLED) {
            if (tcpAllowed == null || disableBrutal == null || tokenRequired == null) {
                return capability == Capability.ENABLED
                        ? new CapabilityDetails(Capability.ENABLED, null, null, null)
                        : new CapabilityDetails(Capability.DISABLED, null, null, null);
            }
            return new CapabilityDetails(capability, tcpAllowed, disableBrutal, tokenRequired);
        }
        return of(capability);
    }

    /**
     * Parses the discovery object. A different positive protocol is recognized as incompatible
     * but its policy fields are never exposed.
     */
    public static CapabilityDetails parse(JsonElement value) {
        if (value == null) return UNDECLARED;
        if (!value.isJsonObject()) return UNKNOWN;
        JsonObject json = value.getAsJsonObject();
        if (!json.has("protocol")) return UNKNOWN;
        Integer protocol = integer(json.get("protocol"));
        if (protocol == null || protocol < 1 || protocol > 999_999_999) return UNKNOWN;
        if (protocol != Capability.PROTOCOL) return INCOMPATIBLE;
        if (json.size() != 5) return UNKNOWN;
        Boolean enabled = bool(json.get("enabled"));
        Boolean tcpAllowed = bool(json.get("tcpAllowed"));
        Boolean disableBrutal = bool(json.get("disableBrutal"));
        Boolean tokenRequired = bool(json.get("tokenRequired"));
        if (enabled == null || tcpAllowed == null || disableBrutal == null || tokenRequired == null) {
            return UNKNOWN;
        }
        return declaration(enabled, tcpAllowed, disableBrutal, tokenRequired);
    }

    /** Serializes this declaration. Only call when {@link #policyComplete()} is true. */
    public JsonObject toJson() {
        if (!policyComplete()) {
            throw new IllegalStateException("Incomplete capability declaration");
        }
        JsonObject json = new JsonObject();
        json.addProperty("protocol", Capability.PROTOCOL);
        json.addProperty("enabled", state == Capability.ENABLED);
        json.addProperty("tcpAllowed", tcpAllowed);
        json.addProperty("disableBrutal", disableBrutal);
        json.addProperty("tokenRequired", tokenRequired);
        return json;
    }

    private static Integer integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isNumber()) return null;
        // Canonical positive integer only: 2.0, 2e0, +2 and leading zeros are not a protocol.
        String raw = primitive.getAsString();
        if (!raw.matches("[1-9][0-9]{0,8}")) return null;
        return Integer.parseInt(raw);
    }

    private static Boolean bool(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isBoolean() ? primitive.getAsBoolean() : null;
    }
}
