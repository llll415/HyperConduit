package io.hyperconduit.mod.probe;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusAugmentationTest {
    private static final String ENABLED_JSON =
            "{\"description\":{},\"hyperconduit\":{\"protocol\":2,\"enabled\":true,"
                    + "\"tcpAllowed\":false,\"disableBrutal\":true,\"tokenRequired\":true}}";
    private static final String DISABLED_JSON =
            "{\"hyperconduit\":{\"enabled\":false,\"protocol\":2,"
                    + "\"tcpAllowed\":true,\"disableBrutal\":false,\"tokenRequired\":false}}";

    @Test
    void readDetailsNeverThrowsForAbsentMalformedOrIncompleteFields() {
        assertEquals(CapabilityDetails.UNDECLARED, StatusAugmentation.readDetails(null));
        assertEquals(Capability.UNDECLARED, StatusAugmentation.read("{\"description\":{}}"));

        CapabilityDetails enabled = StatusAugmentation.readDetails(ENABLED_JSON);
        assertEquals(Capability.ENABLED, enabled.state());
        assertTrue(enabled.policyComplete());
        assertEquals(Boolean.FALSE, enabled.tcpAllowed());
        assertEquals(Boolean.TRUE, enabled.disableBrutal());
        assertEquals(Boolean.TRUE, enabled.tokenRequired());

        CapabilityDetails disabled = StatusAugmentation.readDetails(DISABLED_JSON);
        assertEquals(Capability.DISABLED, disabled.state());
        assertTrue(disabled.policyComplete());

        // A different positive protocol is incompatible and exposes no policy.
        CapabilityDetails incompatible = StatusAugmentation.readDetails(
                "{\"hyperconduit\":{\"protocol\":1,\"enabled\":true,"
                        + "\"tcpAllowed\":true,\"disableBrutal\":false,\"tokenRequired\":false}}");
        assertEquals(Capability.INCOMPATIBLE, incompatible.state());
        assertFalse(incompatible.policyComplete());

        for (String raw : new String[]{
                "", "{", "[]", "{\"hyperconduit\":null}", "{\"hyperconduit\":[]}",
                "{\"hyperconduit\":{\"protocol\":2,\"enabled\":true}}",
                "{\"hyperconduit\":{\"protocol\":2,\"enabled\":true,\"tcpAllowed\":true,"
                        + "\"disableBrutal\":false}}",
                "{\"hyperconduit\":{\"protocol\":2,\"enabled\":\"true\",\"tcpAllowed\":true,"
                        + "\"disableBrutal\":false,\"tokenRequired\":false}}",
                "{\"hyperconduit\":{\"protocol\":2.0,\"enabled\":true,\"tcpAllowed\":true,"
                        + "\"disableBrutal\":false,\"tokenRequired\":false}}",
                "{\"hyperconduit\":{\"protocol\":2,\"enabled\":true,\"tcpAllowed\":true,"
                        + "\"disableBrutal\":false,\"tokenRequired\":false,\"extra\":1}}",
                "{\"hyperconduit\":{\"protocol\":2,\"protocol\":2,\"enabled\":true,"
                        + "\"tcpAllowed\":true,\"disableBrutal\":false,\"tokenRequired\":false}}",
                ENABLED_JSON + " trailing"}) {
            assertEquals(Capability.UNKNOWN, StatusAugmentation.readDetails(raw).state(), String.valueOf(raw));
        }
    }

    @Test
    void detailsOfMissingOrThrowingHookIsUndeclared() {
        assertEquals(CapabilityDetails.UNDECLARED, StatusAugmentation.detailsOf(new Object()));
        assertEquals(CapabilityDetails.UNDECLARED, StatusAugmentation.detailsOf(null));

        CapabilityStatus throwing = new CapabilityStatus() {
            @Override public Capability hyperconduit$capability() { return Capability.UNDECLARED; }
            @Override public void hyperconduit$capability(Capability capability) { }
            @Override public CapabilityDetails hyperconduit$details() { throw new IllegalStateException("hook"); }
        };
        assertEquals(CapabilityDetails.UNDECLARED, StatusAugmentation.detailsOf(throwing));

        CapabilityDetails expected = CapabilityDetails.declaration(true, true, false, true);
        CapabilityStatus enabled = new CapabilityStatus() {
            @Override public Capability hyperconduit$capability() { return expected.state(); }
            @Override public void hyperconduit$capability(Capability capability) { }
            @Override public CapabilityDetails hyperconduit$details() { return expected; }
        };
        assertEquals(expected, StatusAugmentation.detailsOf(enabled));
    }

    @Test
    void attachSwallowsOnlyAdvisoryHookFailures() {
        StatusAugmentation.attach(new Object(), ENABLED_JSON);

        AtomicReference<CapabilityDetails> captured = new AtomicReference<>();
        CapabilityStatus capturing = new CapabilityStatus() {
            @Override public Capability hyperconduit$capability() { return Capability.UNDECLARED; }
            @Override public void hyperconduit$capability(Capability capability) { }
            @Override public void hyperconduit$details(CapabilityDetails details) { captured.set(details); }
        };
        StatusAugmentation.attach(capturing, ENABLED_JSON);
        assertEquals(Capability.ENABLED, captured.get().state());
        assertTrue(captured.get().policyComplete());

        CapabilityStatus throwing = new CapabilityStatus() {
            @Override public Capability hyperconduit$capability() { return Capability.UNDECLARED; }
            @Override public void hyperconduit$capability(Capability capability) { }
            @Override public void hyperconduit$details(CapabilityDetails details) { throw new IllegalStateException("hook"); }
        };
        StatusAugmentation.attach(throwing, ENABLED_JSON);
    }

    @Test
    void decorateElementAddsCompleteDeclarationWithoutMutatingInput() {
        JsonElement original = JsonParser.parseString("{\"description\":{\"text\":\"hello\"}}");
        CapabilityDetails enabled = CapabilityDetails.declaration(true, false, true, true);
        JsonElement decorated = StatusAugmentation.decorate(original, enabled);
        assertTrue(decorated.getAsJsonObject().has("hyperconduit"));
        assertFalse(original.getAsJsonObject().has("hyperconduit"), "input must not be mutated");
        assertEquals(Capability.ENABLED, StatusAugmentation.readDetails(decorated.toString()).state());

        JsonElement disabled = StatusAugmentation.decorate(original,
                CapabilityDetails.declaration(false, true, false, false));
        assertEquals(Capability.DISABLED, StatusAugmentation.readDetails(disabled.toString()).state());

        assertSame(original, StatusAugmentation.decorate(original, CapabilityDetails.UNDECLARED));
        assertSame(original, StatusAugmentation.decorate(original, CapabilityDetails.UNKNOWN));
        assertSame(original, StatusAugmentation.decorate(original, CapabilityDetails.of(Capability.ENABLED)),
                "an incomplete policy must not be advertised");
        assertNull(StatusAugmentation.decorate((JsonElement) null, enabled));
    }

    @Test
    void decorateStringFallsBackToOriginalWhenMalformedOrIncomplete() {
        String raw = "{\"description\":{}}";
        assertEquals(raw, StatusAugmentation.decorate(raw, CapabilityDetails.UNDECLARED));
        assertEquals(raw, StatusAugmentation.decorate(raw, CapabilityDetails.of(Capability.ENABLED)));
        assertEquals("not json", StatusAugmentation.decorate("not json", CapabilityDetails.declaration(true, true, false, false)));
        assertNull(StatusAugmentation.decorate((String) null, CapabilityDetails.declaration(true, true, false, false)));

        String decorated = StatusAugmentation.decorate(raw,
                CapabilityDetails.declaration(true, false, true, false));
        CapabilityDetails read = StatusAugmentation.readDetails(decorated);
        assertEquals(Capability.ENABLED, read.state());
        assertTrue(read.policyComplete());
        assertEquals(decorated, StatusAugmentation.decorate(decorated, CapabilityDetails.UNDECLARED),
                "an undeclared state must leave the cached string untouched");
    }
}
