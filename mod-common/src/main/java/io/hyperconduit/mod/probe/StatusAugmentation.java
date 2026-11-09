package io.hyperconduit.mod.probe;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Pure status-JSON augmentation used by the packet mixin and by tests.
 *
 * <p>This class deliberately contains no Minecraft types: it only reads the advisory
 * {@code hyperconduit} field and re-serializes a status object when a complete declaration is
 * present. Failures here are always non-fatal for the caller; the status packet itself is decoded
 * by vanilla and must never be replaced or swallowed by this helper.
 */
public final class StatusAugmentation {
    private StatusAugmentation() {}

    /** Reads the full declaration; malformed or absent data becomes UNKNOWN/UNDECLARED. */
    public static CapabilityDetails readDetails(String raw) {
        return CapabilityJson.readDetails(raw);
    }

    /** State-only view for callers that do not need the policy flags. */
    public static Capability read(String raw) {
        return readDetails(raw).state();
    }

    /** Returns the declaration attached to a decoded status object, or UNDECLARED when absent. */
    public static CapabilityDetails detailsOf(Object status) {
        if (status instanceof CapabilityStatus capability) {
            try {
                CapabilityDetails details = capability.hyperconduit$details();
                return details == null ? CapabilityDetails.UNDECLARED : details;
            } catch (RuntimeException ignored) {
                return CapabilityDetails.UNDECLARED;
            }
        }
        return CapabilityDetails.UNDECLARED;
    }

    /** Attaches the advisory declaration; capability hooks must never abort vanilla handling. */
    public static void attach(Object status, String raw) {
        if (!(status instanceof CapabilityStatus capability)) {
            return;
        }
        try {
            capability.hyperconduit$details(readDetails(raw));
        } catch (RuntimeException ignored) {
            // Advisory only: the decoded status object remains usable.
        }
    }

    /** Adds a complete declaration to an already encoded status object. Input is not mutated. */
    public static JsonElement decorate(JsonElement json, CapabilityDetails details) {
        if (json == null || !json.isJsonObject() || details == null || !details.policyComplete()) {
            return json;
        }
        JsonElement copy = json.deepCopy();
        copy.getAsJsonObject().add("hyperconduit", details.toJson());
        return copy;
    }

    /**
     * Adds a complete declaration to a cached status JSON string. Returns the original string when
     * no complete declaration is available or the cached value is not parseable; a malformed
     * cached value is left for vanilla's codec to reject, never silently replaced.
     */
    public static String decorate(String raw, CapabilityDetails details) {
        if (raw == null || details == null || !details.policyComplete()) {
            return raw;
        }
        try {
            JsonElement json = JsonParser.parseString(raw);
            // A lenient parser can turn malformed text into a primitive; leave non-objects alone
            // so vanilla's codec still sees the server's original bytes.
            if (!json.isJsonObject()) {
                return raw;
            }
            return decorate(json, details).toString();
        } catch (JsonParseException invalid) {
            return raw;
        }
    }
}
