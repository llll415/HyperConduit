package io.hyperconduit.mod.probe;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityJsonTest {
    private static final String ENABLED =
            "{\"protocol\":2,\"enabled\":true,\"tcpAllowed\":true,\"disableBrutal\":false,\"tokenRequired\":true}";
    private static final String DISABLED =
            "{\"protocol\":2,\"enabled\":false,\"tcpAllowed\":true,\"disableBrutal\":true,\"tokenRequired\":false}";

    @Test void readsCapabilityWithoutConfusingVanillaFields() {
        assertEquals(Capability.UNDECLARED, CapabilityJson.read("{\"description\":\"hyperconduit\",\"players\":{}}"));
        assertEquals(Capability.ENABLED, CapabilityJson.read("{\"description\":{},\"hyperconduit\":" + ENABLED + "}"));
        assertEquals(Capability.DISABLED, CapabilityJson.read("{\"hyperconduit\":" + DISABLED + "}"));
        assertEquals(Capability.INCOMPATIBLE, CapabilityJson.read("{\"hyperconduit\":{\"protocol\":1,\"enabled\":true}}"));
    }

    @Test void exposesFullPolicyOnlyForCompatibleDeclarations() {
        var details = CapabilityJson.readDetails("{\"hyperconduit\":" + ENABLED + "}");
        assertEquals(Capability.ENABLED, details.state());
        assertTrue(details.compatible());
        assertTrue(details.enabled());
        assertTrue(details.tcpAllowed());
        assertFalse(details.disableBrutal());
        assertTrue(details.tokenRequired());

        var old = CapabilityJson.readDetails("{\"hyperconduit\":{\"protocol\":1,\"enabled\":true}}");
        assertEquals(Capability.INCOMPATIBLE, old.state());
        assertFalse(old.compatible());
        assertNull(old.tcpAllowed());
    }

    @Test void duplicateFieldsAndMalformedDeclarationsAreNotAccepted() {
        for (String raw : new String[]{
                "{\"hyperconduit\":{\"protocol\":2,\"protocol\":2,\"enabled\":true,\"tcpAllowed\":true,\"disableBrutal\":false,\"tokenRequired\":true}}",
                "{\"hyperconduit\":{},\"hyperconduit\":" + ENABLED + "}",
                "{\"hyperconduit\":{\"protocol\":2.0,\"enabled\":true,\"tcpAllowed\":true,\"disableBrutal\":false,\"tokenRequired\":true}}",
                "{\"hyperconduit\":{\"protocol\":2e0,\"enabled\":true,\"tcpAllowed\":true,\"disableBrutal\":false,\"tokenRequired\":true}}",
                "{\"hyperconduit\":{\"protocol\":02,\"enabled\":true,\"tcpAllowed\":true,\"disableBrutal\":false,\"tokenRequired\":true}}",
                "{\"hyperconduit\":{\"protocol\":2,\"enabled\":\"true\",\"tcpAllowed\":true,\"disableBrutal\":false,\"tokenRequired\":true}}",
                "{\"hyperconduit\":{\"protocol\":2,\"enabled\":true,\"tcpAllowed\":true,\"disableBrutal\":false}}",
                "{\"hyperconduit\":{\"protocol\":2,\"enabled\":true,\"tcpAllowed\":true,\"disableBrutal\":false,\"tokenRequired\":true,\"token\":\"no\"}}",
                "{", "{} {}"}) {
            assertEquals(Capability.UNKNOWN, CapabilityJson.read(raw), raw);
        }
    }
}
