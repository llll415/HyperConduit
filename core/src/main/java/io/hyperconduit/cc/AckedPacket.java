package io.hyperconduit.cc;

/** A packet newly acknowledged by the peer, reported to {@link CongestionController}. */
public record AckedPacket(long packetNumber, long bytesAcked) {
}
