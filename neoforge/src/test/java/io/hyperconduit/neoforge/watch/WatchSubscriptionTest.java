package io.hyperconduit.neoforge.watch;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression scenarios for the original bug: every command invocation has a fresh
 * {@code CommandSourceStack}, so identity must come from the underlying source/UUID instead.
 */
class WatchSubscriptionTest {

    @Test
    void freshConsoleStackFromWatchStopRemovesTheSubscription() {
        WatchRegistry<WatchKey, String> registry = new WatchRegistry<>();
        Object consoleSource = new Object();

        registry.put(WatchKey.source(consoleSource), "stack-from-watch");
        String removed = registry.remove(WatchKey.source(consoleSource));

        assertEquals("stack-from-watch", removed);
        assertTrue(registry.isEmpty());
    }

    @Test
    void repeatedWatchKeepsOnlyTheLatestStack() {
        WatchRegistry<WatchKey, String> registry = new WatchRegistry<>();
        Object consoleSource = new Object();

        registry.put(WatchKey.source(consoleSource), "old-stack");
        registry.put(WatchKey.source(consoleSource), "new-stack");

        assertEquals(1, registry.size());
        assertEquals("new-stack", registry.get(WatchKey.source(consoleSource)).orElseThrow());
    }

    @Test
    void commandBlockAndRconSourcesDoNotCrossSubscribe() {
        WatchRegistry<WatchKey, String> registry = new WatchRegistry<>();
        Object commandBlockSource = new Object();
        Object rconSource = new Object();

        registry.put(WatchKey.source(commandBlockSource), "command-block");
        registry.put(WatchKey.source(rconSource), "rcon");

        registry.remove(WatchKey.source(rconSource));

        assertEquals(1, registry.size());
        assertEquals("command-block", registry.get(WatchKey.source(commandBlockSource)).orElseThrow());
        assertFalse(registry.containsKey(WatchKey.source(rconSource)));
    }

    @Test
    void reconnectReplacesOldPlayerStackByUuid() {
        WatchRegistry<WatchKey, String> registry = new WatchRegistry<>();
        UUID playerId = UUID.randomUUID();

        registry.put(WatchKey.player(playerId), "old-connection-stack");
        registry.put(WatchKey.player(playerId), "new-connection-stack");

        assertEquals(1, registry.size());
        assertEquals("new-connection-stack", registry.get(WatchKey.player(playerId)).orElseThrow());
    }

    @Test
    void serverStopClearsEverySubscription() {
        WatchRegistry<WatchKey, String> registry = new WatchRegistry<>();
        registry.put(WatchKey.player(UUID.randomUUID()), "player-stack");
        registry.put(WatchKey.source(new Object()), "console-stack");

        registry.clear();

        assertTrue(registry.isEmpty());
    }
}
