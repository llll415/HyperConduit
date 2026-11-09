package io.hyperconduit.mod.bridge;

import org.junit.jupiter.api.Test;

import static io.hyperconduit.mod.bridge.TransportChoicePolicy.Decision.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TransportChoicePolicyTest {
    @Test void onlyTwoExplicitMismatchCasesAsk() {
        for (boolean client : new boolean[] {false, true}) {
            for (boolean declared : new boolean[] {false, true}) {
                for (boolean enabled : new boolean[] {false, true}) {
                    for (boolean tcp : new boolean[] {false, true}) {
                        var expected = !declared ? CURRENT
                                : client && !enabled && tcp ? ASK_TCP
                                : !client && !tcp ? ASK_TUNNEL : CURRENT;
                        assertEquals(expected, TransportChoicePolicy.evaluate(client, declared, enabled, tcp));
                    }
                }
            }
        }
    }

    @Test void unknownDiscoveryNeverSuggestsDowngradeOrEnablesTunnel() {
        assertEquals(CURRENT, TransportChoicePolicy.evaluate(true, false, false, true));
        assertEquals(CURRENT, TransportChoicePolicy.evaluate(false, false, true, false));
    }

    @Test void disabledClientAndTcpAllowedNeverAsksEvenWhenServerIsDisabled() {
        assertEquals(CURRENT, TransportChoicePolicy.evaluate(false, true, false, true));
        assertEquals(CURRENT, TransportChoicePolicy.evaluate(false, true, true, true));
    }

    @Test void enabledTunnelNeverPromptsForTcpMerelyBecauseTcpIsAvailable() {
        assertEquals(CURRENT, TransportChoicePolicy.evaluate(true, true, true, true));
        assertEquals(CURRENT, TransportChoicePolicy.evaluate(true, true, true, false));
    }
}
