package io.hyperconduit;

/** A peer violated the HyperConduit wire protocol. Distinct from an AEAD authentication failure. */
public class ProtocolException extends Exception {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
