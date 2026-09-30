package io.hyperconduit.conn;

/**
 * What the send loop is permitted to put on the wire right now, mirroring quic-go's
 * {@code SendMode}. The ordering of the checks that produce these is significant: probes win over
 * congestion limits (otherwise a stalled connection could never recover), and congestion limits win
 * over pacing (a full window is a hard stop, an empty pacer budget is only a short wait).
 */
public enum SendMode {

    /** Send anything: data, ACKs, probes. */
    ANY,

    /** The congestion window is full; only ACKs may go out. */
    ACK_ONLY,

    /** The window has room but the pacer's token bucket is empty; wait and retry. */
    PACING_LIMITED,

    /** The probe timeout fired; send probe packets to elicit an ACK. */
    PTO_PROBE,

    /** Nothing may be sent (too many packets outstanding). */
    NONE
}
