package io.hyperconduit.mod.probe;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityTest {
    @Test void distinguishesDeclarationsFromLackOfEvidence() {
        assertEquals(Capability.UNDECLARED, Capability.parse(null));
        assertEquals(Capability.ENABLED, Capability.parse(Capability.declaration(true)));
        assertEquals(Capability.DISABLED, Capability.parse(Capability.declaration(false)));
        assertEquals(Capability.INCOMPATIBLE, Capability.parse(JsonParser.parseString("{\"protocol\":1,\"enabled\":true}")));
        for (String invalid : new String[]{"null", "[]", "{}", "{\"protocol\":2,\"enabled\":\"true\"}",
                "{\"protocol\":2.0,\"enabled\":true}", "{\"protocol\":2e0,\"enabled\":true}",
                "{\"protocol\":2,\"enabled\":true,\"token\":\"bad\"}"}) {
            assertEquals(Capability.UNKNOWN, Capability.parse(JsonParser.parseString(invalid)));
        }
        assertEquals(Capability.UNKNOWN, Capability.fromProbe("timeout"));
    }

    @Test void exactFiveFieldDeclarationCarriesPolicy() {
        var details = CapabilityDetails.declaration(true, true, false, true);
        assertEquals(Capability.ENABLED, details.state());
        assertTrue(details.compatible());
        assertTrue(details.enabled());
        assertTrue(details.tcpAllowed());
        assertFalse(details.disableBrutal());
        assertTrue(details.tokenRequired());
        assertEquals(details, CapabilityDetails.parse(details.toJson()));

        var disabled = CapabilityDetails.declaration(false, false, true, false);
        assertEquals(Capability.DISABLED, disabled.state());
        assertTrue(disabled.compatible());
        assertFalse(disabled.enabled());
        assertFalse(disabled.tcpAllowed());
        assertTrue(disabled.disableBrutal());
        assertFalse(disabled.tokenRequired());
    }

    @Test void incompatibleProtocolNeverExposesPolicy() {
        var details = CapabilityDetails.parse(JsonParser.parseString(
                "{\"protocol\":1,\"enabled\":true,\"tcpAllowed\":true,\"disableBrutal\":false,\"tokenRequired\":false}"));
        assertEquals(Capability.INCOMPATIBLE, details.state());
        assertFalse(details.compatible());
        assertNull(details.tcpAllowed());
        assertNull(details.disableBrutal());
        assertNull(details.tokenRequired());
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
