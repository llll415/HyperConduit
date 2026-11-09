package io.hyperconduit.mod.bridge;

import io.hyperconduit.mod.config.HyperConduitConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TransportConsentRaceTest {
    @Test void concurrentDoubleConfirmationContinuesExactlyOnce() throws Exception {
        HyperConduitConfig global = new HyperConduitConfig();
        global.enabled = true;
        TransportConsent consent = new TransportConsent(global);
        consent.discovered(true, false, true);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger continued = new AtomicInteger();
        Runnable confirm = () -> {
            try { start.await(); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            if (consent.proceed(true) != null) continued.incrementAndGet();
        };
        Thread first = new Thread(confirm);
        Thread second = new Thread(confirm);
        first.start(); second.start(); start.countDown();
        first.join(3000); second.join(3000);
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals(1, continued.get());
        assertTrue(global.enabled);
    }
}
