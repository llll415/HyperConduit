package io.hyperconduit.net;

import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.channels.DatagramChannel;

/**
 * Socket buffer sizing.
 *
 * <p>Worth its own class because the failure mode is silent and misleading: Brutal intentionally
 * bursts, the OS default UDP receive buffer (often 64-256 KB) overflows, and the resulting drops
 * look exactly like network loss in the statistics. On a real link that inflates the measured loss
 * rate and makes the transport retransmit data it delivered perfectly well.
 */
public final class SocketBuffers {

    private SocketBuffers() {
    }

    /**
     * Requests the given size in both directions and reports what the OS actually granted, since it
     * may clamp to a system maximum.
     */
    public static void apply(DatagramChannel channel, int requestedBytes, String label) {
        try {
            DatagramSocket socket = channel.socket();
            socket.setReceiveBufferSize(requestedBytes);
            socket.setSendBufferSize(requestedBytes);
            int granted = socket.getReceiveBufferSize();
            if (granted < requestedBytes) {
                System.err.printf("[hyperconduit] %s: receive buffer clamped to %,d of %,d bytes;"
                                + " raise the OS limit to avoid self-inflicted loss at high rates%n",
                        label, granted, requestedBytes);
            }
        } catch (SocketException | UnsupportedOperationException e) {
            // Not fatal: a smaller buffer only means earlier self-inflicted loss.
            System.err.printf("[hyperconduit] %s: could not size socket buffers: %s%n",
                    label, e.getMessage());
        }
    }
}
