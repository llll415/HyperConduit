package io.hyperconduit.velocity.watch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Stable, insertion-ordered subscription map.
 *
 * <p>Keys are a player UUID or a single console key, so repeated
 * {@code /hyperconduit watch} from the same source cannot create duplicate
 * output. Non-player/non-console sources fall back to object identity; Velocity
 * currently has no such command sources.</p>
 */
final class WatchRegistry<T> {

    record Key(UUID uuid, boolean console, int other) {

        static Key player(UUID uuid) {
            return new Key(Objects.requireNonNull(uuid, "uuid"), false, 0);
        }

        static Key consoleKey() {
            return new Key(null, true, 0);
        }

        static Key other(int identity) {
            return new Key(null, false, identity);
        }
    }

    private final Map<Key, T> targets = new LinkedHashMap<>();

    synchronized boolean put(Key key, T value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        boolean added = !targets.containsKey(key);
        targets.put(key, value);
        return added;
    }

    synchronized boolean remove(Key key) {
        return targets.remove(key) != null;
    }

    synchronized void removeIf(Predicate<T> predicate) {
        targets.values().removeIf(predicate);
    }

    synchronized void removeValue(T value) {
        targets.values().removeIf(candidate -> candidate == value);
    }

    synchronized List<T> snapshot() {
        return List.copyOf(targets.values());
    }

    synchronized boolean isEmpty() {
        return targets.isEmpty();
    }

    synchronized int size() {
        return targets.size();
    }

    synchronized void clear() {
        targets.clear();
    }
}