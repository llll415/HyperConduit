package io.hyperconduit.sidecar;

import java.io.IOException;

/**
 * Safe process failure: never includes config contents or raw child output.
 *
 * <p>No raw cause is attached: Minecraft's ConnectScreen may unwrap one layer and display the raw
 * cause, which could contain English text or local paths. The message is user-facing Chinese text;
 * the original internal code remains available through {@link #code()} for diagnostics.
 */
public final class SidecarException extends IOException {
    private final String code;
    private final String diagnosticCode;

    public SidecarException(String code) {
        this(code, null);
    }

    /** Keeps a lower-layer code-only diagnostic without attaching a raw cause to the user error. */
    SidecarException(String code, String diagnosticCode) {
        super(SidecarErrorText.describe(code));
        this.code = code;
        this.diagnosticCode = diagnosticCode;
    }

    /** The original, internal error code; diagnostics should use this instead of the message. */
    public String code() { return code; }

    /** Package-visible code-only detail; never included in the user-facing message. */
    String diagnosticCode() { return diagnosticCode; }
}
