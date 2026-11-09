package io.hyperconduit.mod.probe;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityEvidenceTest {
    @Test void silenceIsUnknownButMissingFieldIsUndeclared() {
        var evidence = new CapabilityEvidence();
        assertEquals(Capability.CHECKING,evidence.state());
        evidence.quic(Capability.UNKNOWN); assertEquals(Capability.UNKNOWN,evidence.state());
        evidence.vanilla(Capability.UNDECLARED); assertEquals(Capability.UNDECLARED,evidence.state());
        evidence.quic(Capability.ENABLED); assertEquals(Capability.ENABLED,evidence.state());
        evidence.vanilla(Capability.DISABLED); assertEquals(Capability.DISABLED,evidence.state());
        evidence.reset(); assertEquals(Capability.CHECKING,evidence.state());
    }
}
