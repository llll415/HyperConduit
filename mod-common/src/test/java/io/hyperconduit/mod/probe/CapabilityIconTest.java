package io.hyperconduit.mod.probe;

import org.junit.jupiter.api.Test;
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
}
