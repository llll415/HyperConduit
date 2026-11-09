package io.hyperconduit.mod.probe;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class ProbeBatchTest {
    @Test void boundsAndDeduplicatesThenCancelsActiveAndQueued() {
        Map<String, CompletableFuture<Capability>> running = new HashMap<>();
        Map<String, Capability> results = new HashMap<>();
        ProbeBatch<String> batch = new ProbeBatch<>(2, 3, key -> {
            var future = new CompletableFuture<Capability>(); running.put(key, future); return future;
        }, Runnable::run, results::put);
        for (String key : List.of("a", "a", "b", "c", "overflow")) batch.submit(key);
        assertEquals(Set.of("a","b"), running.keySet());
        assertEquals(Capability.UNKNOWN, results.get("overflow"));
        running.get("a").complete(Capability.ENABLED);
        assertEquals(Set.of("a","b","c"), running.keySet());
        batch.close();
        assertTrue(running.get("b").isCancelled()); assertTrue(running.get("c").isCancelled());
        assertEquals(Capability.ENABLED, results.get("a"));
        batch.submit("late"); assertFalse(running.containsKey("late"));
    }

    @Test void ignoresLateCallbacksAfterClose() {
        ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        Map<Integer,Capability> result = new HashMap<>();
        var future = new CompletableFuture<Capability>();
        var batch = new ProbeBatch<Integer>(1, 2, key -> future, callbacks::add, result::put);
        batch.submit(1); future.complete(Capability.ENABLED); batch.close();
        callbacks.removeFirst().run();
        assertEquals(Capability.CHECKING,result.get(1));
    }
}
