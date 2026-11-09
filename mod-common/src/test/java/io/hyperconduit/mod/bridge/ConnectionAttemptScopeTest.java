package io.hyperconduit.mod.bridge;

import io.hyperconduit.mod.config.HyperConduitConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionAttemptScopeTest {
    @Test void nestedScopesRestoreAndNeverMutateOriginal() {
        HyperConduitConfig global = new HyperConduitConfig();
        global.enabled = true;
        HyperConduitConfig attempt = global.copy();
        attempt.enabled = false;
        assertNull(ConnectionAttemptScope.snapshot());
        try (var outer = ConnectionAttemptScope.enter(attempt)) {
            assertFalse(ConnectionAttemptScope.snapshot().enabled);
            ConnectionAttemptScope.snapshot().enabled = true;
            assertFalse(ConnectionAttemptScope.snapshot().enabled);
            try (var inner = ConnectionAttemptScope.enter(global)) {
                assertTrue(ConnectionAttemptScope.snapshot().enabled);
            }
            assertFalse(ConnectionAttemptScope.snapshot().enabled);
        }
        assertTrue(global.enabled);
        assertNull(ConnectionAttemptScope.snapshot());
    }

    @Test void connectorReceivesSnapshotWithoutLeakingToLaterThread() throws Exception {
        HyperConduitConfig config = new HyperConduitConfig();
        config.enabled = false;
        AtomicReference<Boolean> result = new AtomicReference<>();
        Thread worker;
        try (var ignored = ConnectionAttemptScope.enter(config)) {
            worker = ConnectionAttemptScope.startConnector(new Thread(() ->
                    result.set(ConnectionAttemptScope.snapshot().enabled), "test-connector"));
        }
        worker.join(3000);
        assertFalse(worker.isAlive());
        assertEquals(false, result.get());
        assertNull(ConnectionAttemptScope.snapshot());
        AtomicReference<HyperConduitConfig> next = new AtomicReference<>();
        Thread later = ConnectionAttemptScope.startConnector(new Thread(() -> next.set(ConnectionAttemptScope.snapshot())));
        later.join(3000);
        assertNull(next.get());
    }

    @Test void scopeIsRestoredOnException() {
        assertThrows(IllegalStateException.class, () -> {
            try (var ignored = ConnectionAttemptScope.enter(new HyperConduitConfig())) {
                throw new IllegalStateException();
            }
        });
        assertNull(ConnectionAttemptScope.snapshot());
    }
}
