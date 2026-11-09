package io.hyperconduit.neoforge.watch;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchCleanupTest {

    private static final WatchKey PLAYER = WatchKey.player(UUID.randomUUID());
    private static final WatchKey SOURCE = WatchKey.source(new Object());

    @Test
    void healthyPlayerSubscriptionStays() {
        assertFalse(WatchCleanup.shouldDrop(PLAYER, WatchTargetState.HEALTHY, false));
    }

    @Test
    void disconnectedPlayerSubscriptionDrops() {
        assertTrue(WatchCleanup.shouldDrop(PLAYER, new WatchTargetState(false, true, true), false));
    }

    @Test
    void playerWhoLostPermissionDrops() {
        assertTrue(WatchCleanup.shouldDrop(PLAYER, new WatchTargetState(true, false, true), false));
    }

    @Test
    void staleConnectionAfterReconnectDrops() {
        assertTrue(WatchCleanup.shouldDrop(PLAYER, new WatchTargetState(true, true, false), false));
    }

    @Test
    void missingPlayerStateDrops() {
        assertTrue(WatchCleanup.shouldDrop(PLAYER, null, false));
    }

    @Test
    void nonPlayerSubscriptionIgnoresPlayerState() {
        assertFalse(WatchCleanup.shouldDrop(SOURCE, null, false));
        assertTrue(WatchCleanup.shouldDrop(SOURCE, WatchTargetState.HEALTHY, true));
    }
}
