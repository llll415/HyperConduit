package io.hyperconduit.mod.probe;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityTest {
    @Test void distinguishesDeclarationsFromLackOfEvidence() {
        assertEquals(Capability.UNDECLARED, Capability.parse(null));
        assertEquals(Capability.ENABLED, Capability.parse(Capability.declaration(true)));
        assertEquals(Capability.DISABLED, Capability.parse(Capability.declaration(false)));
        assertEquals(Capability.INCOMPATIBLE, Capability.parse(JsonParser.parseString("{\"protocol\":2,\"enabled\":true}")));
        for (String invalid : new String[]{"null", "[]", "{}", "{\"protocol\":1,\"enabled\":\"true\"}",
                "{\"protocol\":1.0,\"enabled\":true}", "{\"protocol\":1,\"enabled\":true,\"token\":\"bad\"}"}) {
            assertEquals(Capability.UNKNOWN, Capability.parse(JsonParser.parseString(invalid)));
        }
        assertEquals(Capability.UNKNOWN, Capability.fromProbe("timeout"));
    }

    @Test void scopesRestoreEvenOnFailureAndDoNotReachOtherThreads() throws Exception {
        assertFalse(StatusProbeScope.active());
        try (var outer = StatusProbeScope.enter()) {
            assertTrue(StatusProbeScope.active());
            try (var nested = StatusProbeScope.enter()) { assertTrue(StatusProbeScope.active()); }
            assertTrue(StatusProbeScope.active());
            Thread thread = new Thread(() -> assertFalse(StatusProbeScope.active()));
            thread.start(); thread.join();
        }
        assertFalse(StatusProbeScope.active());
    }
}
