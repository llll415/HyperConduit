package io.hyperconduit.neoforge.command;

/**
 * User-facing failure raised while reading or editing the tunnel configuration.
 *
 * <p>Messages are Chinese, describe only the field and the reason, and must never contain a
 * token or any other value the user typed.</p>
 */
public final class TunnelCommandException extends RuntimeException {

    public TunnelCommandException(String message) {
        super(message);
    }
}
