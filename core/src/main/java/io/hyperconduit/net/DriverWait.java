package io.hyperconduit.net;

import java.io.IOException;
import java.nio.channels.Selector;

/**
 * How the driver waits for the next event.
 *
 * <p>{@link Selector#select(long)} and {@code LockSupport.parkNanos} are quantised to the OS timer
 * resolution, which on Windows is about 15.6 ms unless something has called {@code timeBeginPeriod}.
 * Blocking on them for a short wait therefore adds up to 15 ms of latency to every packet — enough
 * to dominate a measurement of an interactive tunnel, and enough to be felt in game.
 *
 * <p>So short waits spin and long waits block. Spinning only happens while the engine reports that
 * something is due within a couple of milliseconds, which is exactly when an active transfer needs
 * precision; an idle session still blocks and costs nothing.
 */
public final class DriverWait {

    /** Above this, blocking is cheaper than the accuracy it costs. */
    private static final long SPIN_THRESHOLD_NANOS = 2_000_000L;
    private static final long MAX_BLOCK_MILLIS = 1000;

    private DriverWait() {
    }

    /**
     * Waits until a channel is selectable or {@code waitNanos} has elapsed.
     *
     * @param waitNanos how long the engine says it has nothing to do; {@link Long#MAX_VALUE} if idle
     * @return true if a channel became selectable
     */
    public static boolean await(Selector selector, long waitNanos) throws IOException {
        if (waitNanos > SPIN_THRESHOLD_NANOS) {
            long millis = waitNanos == Long.MAX_VALUE
                    ? MAX_BLOCK_MILLIS
                    : Math.min(MAX_BLOCK_MILLIS, Math.max(1, waitNanos / 1_000_000L));
            selector.select(millis);
            return !selector.selectedKeys().isEmpty();
        }
        long deadline = System.nanoTime() + Math.max(0, waitNanos);
        do {
            if (selector.selectNow() > 0) {
                return true;
            }
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        return false;
    }
}
