package io.hyperconduit.sidecar;

import java.io.IOException;

/** Safe process failure: never includes config contents or raw child output. */
public final class SidecarException extends IOException {
    private final String code;

    public SidecarException(String code) {
        super("Sidecar: " + code);
        this.code = code;
    }

    public String code() { return code; }
}
