package io.hyperconduit.frame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The frames carried inside an encrypted packet.
 *
 * <p>These live entirely inside AEAD ciphertext, so the type codes are ours to choose and carry no
 * camouflage value. There is deliberately no stream multiplexing: one session carries exactly one
 * ordered byte stream, matching what a Minecraft connection needs. Multiple players are multiple
 * sessions, which also gives each its own pacer instead of making them contend for one.
 */
public sealed interface Frame permits Frame.Ack, Frame.Close, Frame.MaxData, Frame.Ping, Frame.StreamData {

    /** Reliable stream payload at an absolute byte offset; gaps are buffered until filled. */
    record StreamData(long offset, byte[] data) implements Frame {
    }

    /**
     * Cumulative acknowledgement of received packet numbers.
     *
     * <p>The receiver reports only which packet numbers arrived. The sender derives its own
     * acked/lost ratio from that, which is how Hysteria's Brutal gets its {@code ackRate} too
     * (via quic-go's {@code OnCongestionEventEx}); no peer-reported rate field is needed.
     *
     * <p>Ranges are held as absolute inclusive packet-number intervals and converted to RFC 9000's
     * gap encoding on the wire, because both the received-packet tracker and the loss detector
     * work in absolute numbers. The constructor sorts them descending so {@link #largestAcked()}
     * and the wire encoding can both rely on that order.
     *
     * @param ackDelayMicros time between the peer receiving the largest acked packet and sending
     *                       this ACK, so the sender can subtract it from its RTT sample
     */
    record Ack(long ackDelayMicros, List<AckRange> ranges) implements Frame {

        public Ack {
            List<AckRange> sorted = new ArrayList<>(ranges);
            sorted.sort(Comparator.comparingLong(AckRange::largest).reversed());
            ranges = List.copyOf(sorted);
        }

        /** The highest acknowledged packet number, or {@code -1} when there are no ranges. */
        public long largestAcked() {
            return ranges.isEmpty() ? -1 : ranges.get(0).largest();
        }
    }

    /** An inclusive interval of acknowledged packet numbers. */
    record AckRange(long smallest, long largest) {
        public AckRange {
            if (smallest < 0 || smallest > largest) {
                throw new IllegalArgumentException("invalid ACK range [" + smallest + ", " + largest + "]");
            }
        }
    }

    /** Keepalive and PTO probe; ack-eliciting so it forces the peer to reply. */
    record Ping() implements Frame {
    }

    /** Flow-control credit: the peer may send stream data up to (excluding) {@code maxOffset}. */
    record MaxData(long maxOffset) implements Frame {
    }

    /** Graceful shutdown. */
    record Close(int reasonCode, String message) implements Frame {
    }
}
