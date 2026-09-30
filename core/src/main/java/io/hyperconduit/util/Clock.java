package io.hyperconduit.util;

/**
 * Monotonic time source, injected so the transport's timers can be driven deterministically in
 * tests. Values are {@link System#nanoTime()}-compatible: only differences are meaningful, and the
 * origin may be negative.
 */
public interface Clock {

    long nanoTime();

    Clock SYSTEM = System::nanoTime;
}
