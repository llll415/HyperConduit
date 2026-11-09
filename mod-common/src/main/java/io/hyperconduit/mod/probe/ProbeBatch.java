package io.hyperconduit.mod.probe;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Small bounded batch scheduler. All methods/callbacks run on its owner's thread. */
public final class ProbeBatch<K> implements AutoCloseable {
    private final int concurrency;
    private final int capacity;
    private final Function<K, CompletableFuture<CapabilityDetails>> check;
    private final java.util.concurrent.Executor owner;
    private final BiConsumer<K, CapabilityDetails> publish;
    private final ArrayDeque<K> queued = new ArrayDeque<>();
    private final Map<K, CompletableFuture<CapabilityDetails>> active = new HashMap<>();
    private final java.util.HashSet<K> seen = new java.util.HashSet<>();
    private boolean closed;
    private boolean pumping;

    public ProbeBatch(int concurrency, int capacity,
                      Function<K, CompletableFuture<CapabilityDetails>> check,
                      java.util.concurrent.Executor owner,
                      BiConsumer<K, CapabilityDetails> publish) {
        if (concurrency < 1 || capacity < concurrency) throw new IllegalArgumentException("Invalid probe bounds");
        this.concurrency = concurrency; this.capacity = capacity; this.check = check;
        this.owner = owner; this.publish = publish;
    }

    public void submit(K key) {
        if (closed || seen.contains(key)) return;
        // Completed entries need not occupy a queue slot, but the list itself is bounded too.
        if (seen.size() >= capacity) { publish.accept(key, CapabilityDetails.UNKNOWN); return; }
        seen.add(key); queued.addLast(key); publish.accept(key, CapabilityDetails.CHECKING); pump();
    }

    private void pump() {
        if (pumping) return;
        pumping = true;
        try {
            while (!closed && active.size() < concurrency && !queued.isEmpty()) {
                K key = queued.removeFirst();
                CompletableFuture<CapabilityDetails> future;
                try { future = java.util.Objects.requireNonNull(check.apply(key)); }
                catch (RuntimeException failed) { publish.accept(key, CapabilityDetails.UNKNOWN); continue; }
                active.put(key, future);
                future.whenComplete((result, error) -> owner.execute(() -> {
                    if (closed || active.get(key) != future) return;
                    active.remove(key);
                    publish.accept(key, error == null && result != null
                            ? result : CapabilityDetails.UNKNOWN);
                    pump();
                }));
            }
        } finally { pumping = false; }
    }

    @Override public void close() {
        closed = true; queued.clear();
        var futures = active.values().toArray(CompletableFuture[]::new);
        active.clear(); seen.clear();
        for (var future : futures) future.cancel(true);
    }
}
