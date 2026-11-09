package io.hyperconduit.mod.probe;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

/** Reads only the discovery field, rejecting ambiguity without breaking vanilla status. */
public final class CapabilityJson {
    private CapabilityJson() {}

    /** State-only compatibility view. */
    public static Capability read(String raw) {
        return readDetails(raw).state();
    }

    /**
     * Reads the exact five-field declaration. A duplicate or unknown field, a wrong type, a
     * malformed object or trailing data is {@link Capability#UNKNOWN}. A valid object with a
     * different positive protocol is {@link Capability#INCOMPATIBLE} and exposes no policy.
     */
    public static CapabilityDetails readDetails(String raw) {
        if (raw == null) return CapabilityDetails.UNDECLARED;
        try {
            JsonReader reader = new JsonReader(new StringReader(raw));
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return CapabilityDetails.UNKNOWN;
            reader.beginObject();
            CapabilityDetails result = CapabilityDetails.UNDECLARED;
            boolean seen = false;
            while (reader.hasNext()) {
                if (!reader.nextName().equals("hyperconduit")) { reader.skipValue(); continue; }
                if (seen) return CapabilityDetails.UNKNOWN;
                seen = true;
                if (reader.peek() != JsonToken.BEGIN_OBJECT) return CapabilityDetails.UNKNOWN;
                CapabilityDetails declaration = readDeclaration(reader);
                if (declaration == null) return CapabilityDetails.UNKNOWN;
                result = declaration;
            }
            reader.endObject();
            return reader.peek() == JsonToken.END_DOCUMENT ? result : CapabilityDetails.UNKNOWN;
        } catch (Exception invalid) {
            return CapabilityDetails.UNKNOWN;
        }
    }

    /** Returns null for an invalid object; the caller then abandons the reader. */
    private static CapabilityDetails readDeclaration(JsonReader reader) throws java.io.IOException {
        reader.beginObject();
        Set<String> keys = new HashSet<>(5);
        Integer protocol = null;
        Boolean enabled = null;
        Boolean tcpAllowed = null;
        Boolean disableBrutal = null;
        Boolean tokenRequired = null;
        while (reader.hasNext()) {
            String key = reader.nextName();
            if (!keys.add(key)) return null;
            switch (key) {
                case "protocol" -> {
                    if (reader.peek() != JsonToken.NUMBER) return null;
                    String raw = reader.nextString();
                    if (!raw.matches("[1-9][0-9]{0,8}")) return null;
                    protocol = Integer.parseInt(raw);
                }
                case "enabled" -> {
                    if (reader.peek() != JsonToken.BOOLEAN) return null;
                    enabled = reader.nextBoolean();
                }
                case "tcpAllowed" -> {
                    if (reader.peek() != JsonToken.BOOLEAN) return null;
                    tcpAllowed = reader.nextBoolean();
                }
                case "disableBrutal" -> {
                    if (reader.peek() != JsonToken.BOOLEAN) return null;
                    disableBrutal = reader.nextBoolean();
                }
                case "tokenRequired" -> {
                    if (reader.peek() != JsonToken.BOOLEAN) return null;
                    tokenRequired = reader.nextBoolean();
                }
                default -> {
                    return null;
                }
            }
        }
        reader.endObject();
        if (protocol == null || protocol < 1 || protocol > 999_999_999) return null;
        if (protocol != Capability.PROTOCOL) return CapabilityDetails.INCOMPATIBLE;
        if (keys.size() != 5 || enabled == null || tcpAllowed == null
                || disableBrutal == null || tokenRequired == null) {
            return null;
        }
        return CapabilityDetails.declaration(enabled, tcpAllowed, disableBrutal, tokenRequired);
    }
}
