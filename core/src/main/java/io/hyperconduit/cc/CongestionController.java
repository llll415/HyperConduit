package io.hyperconduit.cc;

import java.util.List;

/**
 * Congestion control plus pacing, modelled on quic-go's {@code congestion.CongestionControl} and
 * {@code CongestionControlEx} merged into one interface.
 *
 * <p>The merged shape matters: Hysteria's Brutal ignores the per-packet {@code OnPacketAcked} and
 * {@code OnCongestionEvent} hooks entirely and does all of its accounting in
 * {@code OnCongestionEventEx}, which delivers the acknowledged <em>and</em> lost packets of one
 * event together. Splitting the two would force an implementation to reconstruct the pairing.
 *
 * <p>All times are {@code System.nanoTime()} values; all sizes are bytes. Implementations are not
 * thread-safe and are driven by the session under its own lock.
 */
public interface CongestionController {

    void setRttStats(RttStats rttStats);

    /**
     * Nanoseconds to wait before the next packet may be sent, or {@code 0} if it may go now.
     * A controller without a pacer always returns {@code 0}.
     */
    long nanosUntilSend(long nowNanos);

    /** Whether the pacer has at least one full datagram of budget available. */
    boolean hasPacingBudget(long nowNanos);

    boolean canSend(long bytesInFlight);

    long congestionWindow();

    void onPacketSent(long sentTimeNanos, long bytesInFlight, long packetNumber, long bytes, boolean ackEliciting);

    /**
     * Reports one ACK-processing or loss-timer event. Either list may be empty but not both.
     *
     * <p>The lists are only valid for the duration of this call: the caller reuses them. Read what
     * you need synchronously rather than retaining a reference.
     *
     * @param priorInFlight bytes in flight before this event was applied
     * @param eventTimeNanos time the ACK was received, or the loss timer fired
     */
    void onCongestionEvent(long priorInFlight, long eventTimeNanos, List<AckedPacket> acked, List<LostPacket> lost);

    void setMaxDatagramSize(int size);
}
