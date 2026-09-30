package io.hyperconduit.cc;

/** A packet declared lost by the local loss detector, reported to {@link CongestionController}. */
public record LostPacket(long packetNumber, long bytesLost) {
}
