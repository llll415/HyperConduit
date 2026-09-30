package io.hyperconduit.conn;

import io.hyperconduit.frame.Frame;

import java.util.List;

/**
 * A packet that has been handed to the network and not yet acknowledged.
 *
 * <p>Holds the frames it carried so they can be re-queued verbatim if it is declared lost. Verbatim
 * re-queueing is what keeps {@link ReceiveBuffer}'s chunk invariant true: a retransmission always
 * repeats an identical (offset, data) pair rather than re-chunking it.
 *
 * <p>Pure-ACK packets are tracked too, even though they never enter bytes in flight and never
 * contribute a retransmittable frame. They have to be, because the peer acknowledges every packet
 * number it receives, and the RTT sample is only valid when the largest acknowledged number is one
 * we can look up — otherwise the sample would silently never be taken.
 */
final class SentPacket {

    final long packetNumber;
    final long sendTimeNanos;
    final int length;
    final List<Frame> frames;
    final boolean ackEliciting;

    /** False for pure-ACK packets, which are excluded from bytes in flight and from ack counting. */
    boolean inBytesInFlight;
    /** Set once acknowledged, so a duplicate ACK cannot feed the congestion controller twice. */
    boolean acknowledged;

    SentPacket(long packetNumber, long sendTimeNanos, int length, List<Frame> frames,
               boolean ackEliciting) {
        this.packetNumber = packetNumber;
        this.sendTimeNanos = sendTimeNanos;
        this.length = length;
        this.frames = frames;
        this.ackEliciting = ackEliciting;
        this.inBytesInFlight = ackEliciting;
    }
}
