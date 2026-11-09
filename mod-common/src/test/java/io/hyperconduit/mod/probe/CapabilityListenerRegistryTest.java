package io.hyperconduit.mod.probe;

import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityListenerRegistryTest {
    @Test void deadProcessStopsAdvertisingBeforeItsExitCallbackRuns() {
        var registry = new CapabilityListenerRegistry();
        var alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        var handle = registry.add(null, false, true);
        handle.observeSidecar(alive::get);
        assertTrue(registry.declaration().enabled());
        alive.set(false);
        assertFalse(registry.declaration().enabled());
        assertFalse(registry.anySidecarAlive());
    }

    @Test void stoppedRegistryCannotBeRevivedByLateBindOrSidecarCallbacks() {
        var registry = new CapabilityListenerRegistry();
        EmbeddedChannel channel = new EmbeddedChannel();
        var pending = channel.newPromise();
        var handle = registry.add(pending, true, true);
        registry.clear();
        pending.setSuccess();
        handle.sidecarAlive(true);
        assertFalse(registry.declaration().enabled());
        assertFalse(registry.declaration().tcpAllowed());
        channel.finishAndReleaseAll();
    }


    @Test void pendingOrFailedDisabledBindNeverDeclaresTcp() {
        var registry = new CapabilityListenerRegistry();
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelPromise pending = channel.newPromise();
        registry.add(pending, false, false);

        assertFalse(registry.declaration().tcpAllowed(), "a bind not yet successful is not a live TCP listener");
        pending.setSuccess();
        assertTrue(registry.declaration().tcpAllowed());
        assertFalse(registry.declaration().enabled());
        channel.close().syncUninterruptibly();
        assertFalse(registry.declaration().tcpAllowed(), "closing the disabled listener must clear tcpAllowed");

        var failedRegistry = new CapabilityListenerRegistry();
        failedRegistry.add(channel.newFailedFuture(new IOException("bind failed")), false, false);
        var declaration = failedRegistry.declaration();
        assertEquals(Capability.DISABLED, declaration.state());
        assertFalse(declaration.tcpAllowed(), "a failed bind must not advertise tcpAllowed");
    }

    @Test void closingOneOfSeveralListenersKeepsTheOthersActive() {
        var registry = new CapabilityListenerRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        var firstHandle = registry.add(first.newSucceededFuture(), false, true);
        var secondHandle = registry.add(second.newSucceededFuture(), false, true);
        firstHandle.sidecarAlive(true);
        secondHandle.sidecarAlive(true);

        var both = registry.declaration();
        assertEquals(Capability.ENABLED, both.state());
        assertTrue(both.tcpAllowed());
        assertTrue(both.tokenRequired());

        first.close().syncUninterruptibly();
        var remaining = registry.declaration();
        assertEquals(Capability.ENABLED, remaining.state(), "closing one listener must not disable the other");
        assertTrue(remaining.tcpAllowed());
        assertTrue(remaining.tokenRequired());

        second.close().syncUninterruptibly();
        var closed = registry.declaration();
        assertEquals(Capability.DISABLED, closed.state());
        assertFalse(closed.tcpAllowed());
    }

    @Test void unixBindThatDoesNotRegisterLeavesExistingTcpFactsUntouched() {
        var registry = new CapabilityListenerRegistry();
        EmbeddedChannel ipv4 = new EmbeddedChannel();
        var handle = registry.add(ipv4.newSucceededFuture(), false, true);
        handle.sidecarAlive(true);
        var before = registry.declaration();
        assertEquals(Capability.ENABLED, before.state());
        assertTrue(before.tcpAllowed());

        // The Unix-domain bind path deliberately does not call registry.add(...).
        var after = registry.declaration();
        assertEquals(before, after, "a non-TCP bind must not overwrite existing IP listener facts");
    }

    @Test void sidecarExitDisablesEnabledButKeepsLiveTcp() {
        var registry = new CapabilityListenerRegistry();
        EmbeddedChannel channel = new EmbeddedChannel();
        var handle = registry.add(channel.newSucceededFuture(), false, true);
        handle.sidecarAlive(true);
        assertEquals(Capability.ENABLED, registry.declaration().state());

        handle.sidecarAlive(false);
        var stopped = registry.declaration();
        assertEquals(Capability.DISABLED, stopped.state());
        assertTrue(stopped.tcpAllowed(), "the public TCP listener is still alive");

        channel.close().syncUninterruptibly();
        assertFalse(registry.declaration().tcpAllowed());
    }

    @Test void mixedSidecarPolicyIsConservativeUnknown() {
        var registry = new CapabilityListenerRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        var firstHandle = registry.add(first.newSucceededFuture(), false, true);
        var secondHandle = registry.add(second.newSucceededFuture(), false, false);
        firstHandle.sidecarAlive(true);
        secondHandle.sidecarAlive(true);

        var declaration = registry.declaration();
        assertEquals(Capability.UNKNOWN, declaration.state(), "mixed token policy must not pick one listener");
        assertFalse(declaration.compatible());
    }
}
