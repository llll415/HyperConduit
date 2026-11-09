package io.hyperconduit.neoforge.watch;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiPredicate;

/**
 * Small thread-safe key/value registry used for active watch subscriptions.
 *
 * <p>{@link #put} intentionally replaces the previous value for the same key, so
 * issuing {@code watch} again never creates duplicate output. {@link #snapshot()} returns a copy so
 * the server tick can iterate while commands mutate the registry.</p>
 */
public final class WatchRegistry<K, V> {

    private final ConcurrentMap<K, V> entries = new ConcurrentHashMap<>();

    public V put(K key, V value) {
        return this.entries.put(key, value);
    }

    public V remove(K key) {
        return this.entries.remove(key);
    }

    public Optional<V> get(K key) {
        return Optional.ofNullable(this.entries.get(key));
    }

    public boolean containsKey(K key) {
        return this.entries.containsKey(key);
    }

    public int size() {
        return this.entries.size();
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    public void clear() {
        this.entries.clear();
    }

    public void removeIf(BiPredicate<? super K, ? super V> predicate) {
        this.entries.entrySet().removeIf(entry -> predicate.test(entry.getKey(), entry.getValue()));
    }

    public List<Map.Entry<K, V>> snapshot() {
        return this.entries.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
    }
}
