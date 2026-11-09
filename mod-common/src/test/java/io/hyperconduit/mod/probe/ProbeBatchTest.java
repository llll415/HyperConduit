package io.hyperconduit.mod.probe;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class ProbeBatchTest {
    private static CapabilityDetails details(Capability state) {
        return CapabilityDetails.of(state);
    }

    @Test void boundsAndDeduplicatesThenCancelsActiveAndQueued() {
        Map<String, CompletableFuture<CapabilityDetails>> running = new HashMap<>();
        Map<String, CapabilityDetails> results = new HashMap<>();
        ProbeBatch<String> batch = new ProbeBatch<>(2, 3, key -> {
            var future = new CompletableFuture<CapabilityDetails>(); running.put(key, future); return future;
        }, Runnable::run, results::put);
        for (String key : List.of("a", "a", "b", "c", "overflow")) batch.submit(key);
        assertEquals(Set.of("a","b"), running.keySet());
        assertEquals(Capability.UNKNOWN, results.get("overflow").state());
        running.get("a").complete(details(Capability.ENABLED));
        assertEquals(Set.of("a","b","c"), running.keySet());
        batch.close();
        assertTrue(running.get("b").isCancelled()); assertTrue(running.get("c").isCancelled());
        assertEquals(Capability.ENABLED, results.get("a").state());
        batch.submit("late"); assertFalse(running.containsKey("late"));
    }

    @Test void ignoresLateCallbacksAfterClose() {
        ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        Map<Integer,CapabilityDetails> result = new HashMap<>();
        var future = new CompletableFuture<CapabilityDetails>();
        var batch = new ProbeBatch<Integer>(1, 2, key -> future, callbacks::add, result::put);
        batch.submit(1); future.complete(details(Capability.ENABLED)); batch.close();
        callbacks.removeFirst().run();
        assertEquals(Capability.CHECKING,result.get(1).state());
    }
}
