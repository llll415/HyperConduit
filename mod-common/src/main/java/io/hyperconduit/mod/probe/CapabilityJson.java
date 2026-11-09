package io.hyperconduit.mod.probe;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;

/** Reads only the discovery field, rejecting ambiguity without breaking vanilla status. */
public final class CapabilityJson {
    private CapabilityJson() {}

    public static Capability read(String raw) {
        try {
            JsonReader reader = new JsonReader(new StringReader(raw));
            reader.setLenient(false);
            reader.beginObject();
            Capability result = Capability.UNDECLARED;
            boolean seen = false;
            while (reader.hasNext()) {
                if (!reader.nextName().equals("hyperconduit")) { reader.skipValue(); continue; }
                if (seen) return Capability.UNKNOWN;
                seen = true;
                if (reader.peek() != JsonToken.BEGIN_OBJECT) return Capability.UNKNOWN;
                JsonObject value = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (value.has(key)) return Capability.UNKNOWN;
                    if (key.equals("protocol") && reader.peek() == JsonToken.NUMBER) {
                        String protocol = reader.nextString();
                        if (!protocol.matches("[1-9][0-9]{0,8}")) return Capability.UNKNOWN;
                        value.addProperty(key, Integer.parseInt(protocol));
                    } else if (key.equals("enabled") && reader.peek() == JsonToken.BOOLEAN) {
                        value.addProperty(key, reader.nextBoolean());
                    } else return Capability.UNKNOWN;
                }
                reader.endObject();
                result = Capability.parse(value);
            }
            reader.endObject();
            return reader.peek() == JsonToken.END_DOCUMENT ? result : Capability.UNKNOWN;
        } catch (Exception invalid) { return Capability.UNKNOWN; }
    }
}
