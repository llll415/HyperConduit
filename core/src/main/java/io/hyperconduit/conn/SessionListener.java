package io.hyperconduit.conn;

/**
 * Session lifecycle events. Called from whichever thread drives the engine, so implementations must
 * be short and must not call back into the engine except through its thread-safe methods.
 */
public interface SessionListener {

    /** The Noise handshake completed and transport keys are installed. */
    default void onEstablished() {
    }

    /** The peer has confirmed the handshake by sending its first protected transport packet. */
    default void onPeerConfirmed() {
    }

    /** In-order stream bytes became available; call {@link SessionEngine#read}. */
    default void onReadable() {
    }

    /** The send buffer drained below its limit; the application may write again. */
    default void onWritable() {
    }

    /** The peer closed the session, or the local side finished closing it. */
    default void onClosed(int reasonCode, String message) {
    }

    /** The session was torn down by a protocol error, authentication failure, or timeout. */
    default void onError(Throwable cause) {
    }
}
