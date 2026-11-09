package io.hyperconduit.mod.probe;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityIconTest {
    @Test void badgeStaysStrictlyLeftOfVanillaSignal() {
        int left = CapabilityIcon.left(10,300), signal = 10+300-15;
        assertEquals(2,signal-(left+12));
        assertTrue(CapabilityIcon.hovered(left,40,left,40));
        assertFalse(CapabilityIcon.hovered(left,40,signal,40));
        assertFalse(CapabilityIcon.hovered(left,40,left,49));
        for (Capability state : Capability.values()) assertFalse(CapabilityIcon.description(state).isBlank());
    }

    @Test void tooltipAddsFourPolicyLinesOnlyForCompleteDeclarations() {
        List<String> lines = CapabilityIcon.details(
                CapabilityDetails.declaration(true, true, false, true));
        assertEquals(4, lines.size());
        assertTrue(lines.get(0).contains("支持"));
        assertTrue(lines.get(1).contains("TCP"));
        assertTrue(lines.get(2).contains("Brutal"));
        assertTrue(lines.get(3).contains("令牌"));
        assertTrue(CapabilityIcon.details(CapabilityDetails.UNKNOWN).isEmpty());
        assertTrue(CapabilityIcon.details(CapabilityDetails.INCOMPATIBLE).isEmpty());
    }
}
