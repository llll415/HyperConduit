package io.hyperconduit.mod.bridge;

import io.hyperconduit.mod.config.HyperConduitConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransportConsentTest {
    @Test void cancellationDiscardsLateResponseAndCannotConnect() {
        TransportConsent consent = new TransportConsent(new HyperConduitConfig());
        assertNull(consent.proceed(false));
        consent.cancel();
        assertFalse(consent.discovered(true, true, false));
        assertNull(consent.proceed(true));
    }

    @Test void consentIsOncePerAttemptAndDoesNotRememberOrModifySettings() {
        HyperConduitConfig global = new HyperConduitConfig();
        global.enabled = true;
        global.clientToken = "existing-token";
        TransportConsent consent = new TransportConsent(global);
        assertTrue(consent.discovered(true, false, true));
        assertFalse(consent.discovered(true, true, false));
        assertNull(consent.proceed(false), "an unauthenticated declaration must not silently downgrade");
        HyperConduitConfig tcp = consent.proceed(true);
        assertFalse(tcp.enabled);
        assertEquals(global.clientToken, tcp.clientToken);
        assertTrue(global.enabled);
        assertNull(consent.proceed(true), "double click must not start another connection");
        TransportConsent next = new TransportConsent(global);
        assertTrue(next.discovered(false, false, false));
        assertTrue(next.proceed(false).enabled, "next attempt follows the unchanged global setting");
    }

    @Test void temporaryTunnelKeepsCredentialsAndDoesNotEnableGlobally() {
        HyperConduitConfig global = new HyperConduitConfig();
        global.enabled = false;
        global.clientToken = "existing-token";
        TransportConsent consent = new TransportConsent(global);
        consent.discovered(true, true, false);
        assertEquals(TransportChoicePolicy.Decision.ASK_TUNNEL, consent.decision());
        assertNull(consent.proceed(false));
        assertTrue(consent.proceed(true).enabled);
        assertFalse(global.enabled);
    }
}
