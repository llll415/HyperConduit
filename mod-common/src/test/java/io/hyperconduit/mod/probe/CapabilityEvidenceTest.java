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

    @Test void vanillaDetailsWinAndKeepTheirPolicy() {
        var evidence = new CapabilityEvidence();
        var quic = CapabilityDetails.declaration(true, true, false, true);
        var vanilla = CapabilityDetails.declaration(false, true, true, false);
        evidence.quic(quic);
        assertEquals(quic, evidence.details());
        evidence.vanilla(vanilla);
        assertEquals(vanilla, evidence.details(), "vanilla declaration wins with its full policy");
        assertEquals(Capability.DISABLED, evidence.state());

        evidence.vanilla(Capability.UNDECLARED);
        assertEquals(quic, evidence.details(), "quic fills a silent vanilla side");
    }
}
