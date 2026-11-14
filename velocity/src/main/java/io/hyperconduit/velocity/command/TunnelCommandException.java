package io.hyperconduit.velocity.command;

/** User-facing command failure; the message is safe to send to the command source. */
public final class TunnelCommandException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TunnelCommandException(String message) {
        super(message);
    }
}
