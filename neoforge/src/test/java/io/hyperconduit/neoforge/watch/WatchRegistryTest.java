package io.hyperconduit.neoforge.watch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchRegistryTest {

    @Test
    void repeatedPutReplacesThePreviousValue() {
        WatchRegistry<WatchKey, String> registry = new WatchRegistry<>();
        WatchKey key = WatchKey.source(new Object());

        registry.put(key, "first-stack");
        String previous = registry.put(key, "second-stack");

        assertEquals("first-stack", previous);
        assertEquals(1, registry.size());
        assertEquals("second-stack", registry.get(key).orElseThrow());
    }

    @Test
    void removeUsesStableKeyEquality() {
        WatchRegistry<WatchKey, String> registry = new WatchRegistry<>();
        Object source = new Object();

        registry.put(WatchKey.source(source), "stack");
        assertEquals("stack", registry.remove(WatchKey.source(source)));
        assertTrue(registry.isEmpty());
    }

    @Test
    void differentKeysStayIsolated() {
        WatchRegistry<WatchKey, String> registry = new WatchRegistry<>();
        WatchKey console = WatchKey.source(new Object());
        WatchKey rcon = WatchKey.source(new Object());

        registry.put(console, "console-stack");
        registry.put(rcon, "rcon-stack");

        assertEquals("console-stack", registry.remove(console));
        assertEquals(1, registry.size());
        assertEquals("rcon-stack", registry.get(rcon).orElseThrow());
    }

    @Test
    void removeIfDropsOnlyMatchingEntries() {
        WatchRegistry<WatchKey, String> registry = new WatchRegistry<>();
        WatchKey keep = WatchKey.source(new Object());
        WatchKey drop = WatchKey.source(new Object());

        registry.put(keep, "keep");
        registry.put(drop, "drop");
        registry.removeIf((key, value) -> value.equals("drop"));

        assertEquals(1, registry.size());
        assertTrue(registry.containsKey(keep));
        assertFalse(registry.containsKey(drop));
    }

    @Test
    void snapshotIsStableWhileRegistryChanges() {
        WatchRegistry<WatchKey, String> registry = new WatchRegistry<>();
        registry.put(WatchKey.source(new Object()), "first");

        List<Map.Entry<WatchKey, String>> snapshot = registry.snapshot();
        registry.put(WatchKey.source(new Object()), "second");

        assertEquals(1, snapshot.size());
        assertEquals("first", snapshot.get(0).getValue());
        assertEquals(2, registry.size());
    }

    @Test
    void clearRemovesAllSubscriptions() {
        WatchRegistry<WatchKey, String> registry = new WatchRegistry<>();
        registry.put(WatchKey.player(java.util.UUID.randomUUID()), "player");
        registry.put(WatchKey.source(new Object()), "source");

        registry.clear();

        assertTrue(registry.isEmpty());
    }
}
