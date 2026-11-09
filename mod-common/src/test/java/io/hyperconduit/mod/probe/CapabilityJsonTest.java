package io.hyperconduit.mod.probe;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityJsonTest {
    @Test void readsCapabilityWithoutConfusingVanillaFields() {
        assertEquals(Capability.UNDECLARED, CapabilityJson.read("{\"description\":\"hyperconduit\",\"players\":{}}"));
        assertEquals(Capability.ENABLED, CapabilityJson.read("{\"description\":{},\"hyperconduit\":{\"protocol\":1,\"enabled\":true}}"));
        assertEquals(Capability.DISABLED, CapabilityJson.read("{\"hyperconduit\":{\"enabled\":false,\"protocol\":1}}"));
        assertEquals(Capability.INCOMPATIBLE, CapabilityJson.read("{\"hyperconduit\":{\"enabled\":true,\"protocol\":2}}"));
    }

    @Test void duplicateFieldsAndMalformedDeclarationsAreNotAccepted() {
        for (String raw : new String[]{
                "{\"hyperconduit\":{\"protocol\":1,\"protocol\":2,\"enabled\":true}}",
                "{\"hyperconduit\":{},\"hyperconduit\":{\"protocol\":1,\"enabled\":true}}",
                "{\"hyperconduit\":{\"protocol\":1.0,\"enabled\":true}}",
                "{\"hyperconduit\":{\"protocol\":1,\"enabled\":\"true\"}}",
                "{\"hyperconduit\":{\"protocol\":1,\"enabled\":true,\"token\":\"no\"}}", "{", "{} {}"}) {
            assertEquals(Capability.UNKNOWN, CapabilityJson.read(raw));
        }
    }
}
