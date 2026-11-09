package io.hyperconduit.mod.probe;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Extra local invariants for the advisory probe model. */
class ProbeInvariantTest {

    @Test
    void evidencePrefersVanillaDeclarationsAndNeverInventsUndeclared() {
        var evidence = new CapabilityEvidence();
        assertEquals(Capability.CHECKING, evidence.state());

        evidence.quic(Capability.ENABLED);
        assertEquals(Capability.ENABLED, evidence.state(), "quic may fill a silent vanilla side");

        evidence.vanilla(Capability.DISABLED);
        assertEquals(Capability.DISABLED, evidence.state(), "vanilla declaration wins over quic");

        evidence.vanilla(Capability.UNDECLARED);
        assertEquals(Capability.ENABLED, evidence.state(), "quic declaration fills a missing vanilla declaration");

        evidence.reset();
        assertEquals(Capability.CHECKING, evidence.state(), "reset drops both sides");

        evidence.vanilla(Capability.UNKNOWN);
        assertEquals(Capability.CHECKING, evidence.state(), "vanilla unknown is not a declaration");
        assertNotEquals(Capability.UNDECLARED, evidence.state());

        evidence.quic(Capability.UNKNOWN);
        assertEquals(Capability.UNKNOWN, evidence.state(), "unknown must not masquerade as undeclared");

        evidence.quic(Capability.UNDECLARED);
        assertEquals(Capability.UNDECLARED, evidence.state(), "explicit undeclared is preserved");

        evidence.reset();
        assertEquals(Capability.CHECKING, evidence.state());
    }

    @Test
    void inlineCompletionsAndFailuresRespectConcurrencyWithoutDeadlock() {
        int concurrency = 3;
        List<String> keys = IntStream.range(0, 60).mapToObj(i -> "key-" + i).toList();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<String> checked = new ArrayList<>();
        Map<String, CapabilityDetails> results = new HashMap<>();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            ProbeBatch<String> batch = new ProbeBatch<>(concurrency, keys.size(), key -> {
                checked.add(key);
                int now = active.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);

                if (key.endsWith("7")) {
                    active.decrementAndGet();
                    throw new IllegalStateException("synchronous failure");
                }

                CompletableFuture<CapabilityDetails> future = new CompletableFuture<>();
                future.whenComplete((value, error) -> active.decrementAndGet());
                if (key.endsWith("9")) future.completeExceptionally(new IllegalStateException("asynchronous failure"));
                else future.complete(CapabilityDetails.of(Capability.ENABLED));
                return future;
            }, Runnable::run, results::put);

            for (String key : keys) batch.submit(key);
            batch.close();

            assertEquals(new HashSet<>(keys), new HashSet<>(checked));
            assertEquals(keys.size(), checked.size(), "each key must be checked exactly once");
            assertTrue(peak.get() <= concurrency, () -> "peak concurrency was " + peak.get());
            for (String key : keys) {
                Capability expected = key.endsWith("7") || key.endsWith("9")
                        ? Capability.UNKNOWN : Capability.ENABLED;
                assertEquals(expected, results.get(key).state(), key);
            }
        });
    }

    @Test
    void closingCancelsQueuedWorkWithoutStartingItAndIgnoresLateCallbacks() {
        List<String> checked = new ArrayList<>();
        Map<String, CompletableFuture<CapabilityDetails>> futures = new LinkedHashMap<>();
        Map<String, CapabilityDetails> published = new HashMap<>();
        ArrayDeque<Runnable> ownerTasks = new ArrayDeque<>();

        ProbeBatch<String> batch = new ProbeBatch<>(1, 4, key -> {
            checked.add(key);
            var future = new CompletableFuture<CapabilityDetails>();
            futures.put(key, future);
            return future;
        }, ownerTasks::add, published::put);

        batch.submit("running");
        batch.submit("queued");
        assertEquals(List.of("running"), checked);
        assertFalse(futures.containsKey("queued"), "queued key must not be checked while a slot is busy");
        assertEquals(Capability.CHECKING, published.get("queued").state());

        batch.close();
        assertEquals(List.of("running"), checked, "close must clear queued work without checking it");
        assertTrue(futures.get("running").isCancelled());
        assertFalse(futures.containsKey("queued"));

        while (!ownerTasks.isEmpty()) ownerTasks.removeFirst().run();
        assertEquals(Capability.CHECKING, published.get("running").state(), "late callback after close must be ignored");
        assertEquals(Capability.CHECKING, published.get("queued").state(), "cancelled queue must never publish a result");
    }

    @Test
    void duplicateAndOverflowKeysStayBoundedAndUnchecked() {
        List<String> checked = new ArrayList<>();
        Map<String, CompletableFuture<CapabilityDetails>> futures = new LinkedHashMap<>();
        Map<String, CapabilityDetails> results = new HashMap<>();

        ProbeBatch<String> batch = new ProbeBatch<>(1, 2, key -> {
            checked.add(key);
            var future = new CompletableFuture<CapabilityDetails>();
            futures.put(key, future);
            return future;
        }, Runnable::run, results::put);

        batch.submit("first");
        batch.submit("first");
        batch.submit("second");
        batch.submit("overflow");

        assertEquals(List.of("first"), checked, "duplicate and overflow keys must not start checks");
        assertEquals(Capability.UNKNOWN, results.get("overflow").state());
        assertFalse(futures.containsKey("overflow"));
        assertEquals(Capability.CHECKING, results.get("second").state());

        futures.get("first").complete(CapabilityDetails.of(Capability.DISABLED));
        assertEquals(List.of("first", "second"), checked);
        assertEquals(Capability.DISABLED, results.get("first").state());

        batch.submit("after-full");
        assertEquals(Capability.UNKNOWN, results.get("after-full").state());
        assertFalse(futures.containsKey("after-full"));
        batch.close();
    }

    @Test
    void nestedScopesRecoverAfterFailureAndStayOffOtherThreads() throws Exception {
        assertFalse(StatusProbeScope.active());

        assertThrows(IllegalStateException.class, () -> {
            try (var outer = StatusProbeScope.enter()) {
                assertTrue(StatusProbeScope.active());
                assertThrows(IllegalStateException.class, () -> {
                    try (var inner = StatusProbeScope.enter()) {
                        assertTrue(StatusProbeScope.active());
                        throw new IllegalStateException("inner failure");
                    }
                });
                assertTrue(StatusProbeScope.active(), "inner close must restore the outer scope");
                throw new IllegalStateException("outer failure");
            }
        });
        assertFalse(StatusProbeScope.active(), "outer close must restore the clean thread");

        try (var scope = StatusProbeScope.enter()) {
            assertTrue(StatusProbeScope.active());
            boolean visible = CompletableFuture.supplyAsync(StatusProbeScope::active)
                    .get(2, TimeUnit.SECONDS);
            assertFalse(visible, "scope must not leak to another thread");
            assertTrue(StatusProbeScope.active());
        }
        assertFalse(StatusProbeScope.active());
    }
}
