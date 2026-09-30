package io.hyperconduit.sim;

import io.hyperconduit.util.Clock;

/**
 * A manually advanced {@link Clock}, for driving the transport deterministically in tests and
 * simulations. Not thread-safe; a simulation owns the thread.
 */
public final class ManualClock implements Clock {

    private long now;

    public ManualClock(long startNanos) {
        this.now = startNanos;
    }

    @Override
    public long nanoTime() {
        return now;
    }

    public void advance(long nanos) {
        now += nanos;
    }

    public void set(long nanos) {
        now = nanos;
    }
}
