package io.hyperconduit.velocity.status;

import com.google.gson.JsonObject;

/**
 * Non-destructive status JSON augmentation.
 *
 * <p>Every other field in the original status object is preserved. The
 * {@code hyperconduit} field is replaced with the declaration derived from the
 * running snapshot. Failures are handled by the caller and must leave the
 * original response untouched.</p>
 */
public final class StatusJson {

    private StatusJson() {
    }

    public static JsonObject augment(JsonObject status, JsonObject declaration) {
        if (status == null || declaration == null) {
            return status;
        }
        JsonObject copy = status.deepCopy();
        copy.add("hyperconduit", declaration.deepCopy());
        return copy;
    }
}