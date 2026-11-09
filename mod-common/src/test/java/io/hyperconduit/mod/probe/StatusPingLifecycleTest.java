package io.hyperconduit.mod.probe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusPingLifecycleTest {
    @Test
    void onlyTheSamePingingEntryExitsOnDisconnect() {
        assertTrue(StatusPingLifecycle.shouldExitPinging(true, true, true));
        assertFalse(StatusPingLifecycle.shouldExitPinging(false, true, true),
                "a stale response/connection must not change a newer entry");
        assertFalse(StatusPingLifecycle.shouldExitPinging(true, false, true),
                "an edited address must not change the old entry");
        assertFalse(StatusPingLifecycle.shouldExitPinging(true, true, false),
                "SUCCESSFUL/INCOMPATIBLE/UNREACHABLE must never be overwritten");
    }
}
