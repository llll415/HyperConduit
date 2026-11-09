package io.hyperconduit.mod.probe;

/** Pure guards for leaving the vanilla server-list {@code PINGING} state. */
public final class StatusPingLifecycle {
    private StatusPingLifecycle() {}

    /**
     * A disconnect may end the visible ping only for the same generation/address that started it,
     * and only while vanilla still considers it pinging. Successful/incompatible entries and the
     * legacy fallback must never be overwritten.
     */
    public static boolean shouldExitPinging(boolean sameGeneration, boolean sameAddress,
                                            boolean currentlyPinging) {
        return sameGeneration && sameAddress && currentlyPinging;
    }
}
