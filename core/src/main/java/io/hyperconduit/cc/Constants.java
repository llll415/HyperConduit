package io.hyperconduit.cc;

/**
 * Transport constants, taken from {@code apernet/quic-go} ({@code internal/protocol/params.go})
 * and Hysteria's {@code internal/congestion/common/pacer.go} so the Java port behaves identically
 * to the reference implementation.
 */
public final class Constants {

    /** QUIC's conservative initial datagram size; also Brutal's floor for the congestion window. */
    public static final int INITIAL_PACKET_SIZE = 1280;

    /** Floor on how finely packets may be paced, and on the loss-detection delay. */
    public static final long MIN_PACING_DELAY_NANOS = 1_000_000L;
    public static final long TIMER_GRANULARITY_NANOS = 1_000_000L;

    /** RTT assumed before the first sample, so Brutal has a usable window from the start. */
    public static final long DEFAULT_INITIAL_RTT_NANOS = 100_000_000L;

    public static final long MAX_ACK_DELAY_NANOS = 25_000_000L;

    /** RFC 9002 loss detection: time threshold as an RTT multiplier, and packet-count threshold. */
    public static final double TIME_THRESHOLD = 9.0 / 8.0;
    public static final int PACKET_THRESHOLD = 3;

    /** Pacer burst limits, from Hysteria's common.Pacer. */
    public static final int MAX_BURST_PACKETS = 10;
    public static final int MAX_BURST_PACING_DELAY_MULTIPLIER = 4;

    private Constants() {
    }
}
