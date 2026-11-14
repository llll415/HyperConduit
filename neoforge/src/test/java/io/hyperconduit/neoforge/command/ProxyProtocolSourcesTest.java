package io.hyperconduit.neoforge.command;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyProtocolSourcesTest {

    @Test
    void acceptsLiteralAddressesAndCidrRanges() {
        for (String source : List.of(
                "127.0.0.1",
                "10.0.0.0/8",
                "192.168.1.5/24",
                "1.2.3.4/8",
                "128.0.0.0/1",
                "0.0.0.1",
                "255.255.255.255",
                "::1",
                "::2",
                "2001:DB8::/32",
                "1:2:3:4:5:6:1.2.3.4",
                "::1.2.3.4")) {
            assertEquals(List.of(source), ProxyProtocolSources.parse(source), source);
        }
    }

    @Test
    void splitsOnCommasAndTrimsEntries() {
        assertEquals(List.of("127.0.0.1", "::1"), ProxyProtocolSources.parse(" 127.0.0.1 , ::1 "));
    }

    @Test
    void rejectsNonLiteralAndUnsafeEntries() {
        for (String source : List.of(
                "proxy.example.com",
                "010.0.0.1",
                "256.0.0.1",
                "127.1",
                "1.2.3",
                "1.2.3.4.5",
                "fe80::1%eth0",
                "fe80::%eth0/64",
                "0.0.0.0",
                "::",
                "0.0.0.0/0",
                "::/0",
                "1.2.3.4/0",
                "127.0.0.1/1",
                "::1/1",
                "::ffff:127.0.0.1",
                "::ffff:10.0.0.0/104",
                "10.0.0.0/33",
                "2001:db8::/129",
                "10.0.0.0/08",
                "10.0.0.0/-1",
                "10.0.0.0/8/8",
                "10.0.0.0/",
                "/8",
                "1:2:3:4:5:6:7",
                "1::2::3",
                "1:2:3:4:5:6:7:8::",
                "1:2:3:4:5:6:7:8:9",
                "12345::1",
                ":::")) {
            TunnelCommandException failure = assertThrows(TunnelCommandException.class,
                    () -> ProxyProtocolSources.parse(source), source);
            assertTrue(failure.getMessage().startsWith("可信来源"), source);
        }
    }

    @Test
    void rejectsEmptyListsAndEmptyEntries() {
        for (String raw : List.of("", "   ", ",", " , ", "127.0.0.1,,::1", "127.0.0.1,")) {
            assertThrows(TunnelCommandException.class, () -> ProxyProtocolSources.parse(raw), raw);
        }
    }

    @Test
    void enforcesTheEntryLimit() {
        List<String> sources = new ArrayList<>();
        for (int i = 1; i <= ProxyProtocolSources.MAX_ENTRIES; i++) {
            sources.add("10.0.0." + i);
        }
        assertEquals(ProxyProtocolSources.MAX_ENTRIES,
                ProxyProtocolSources.parse(String.join(",", sources)).size());

        sources.add("10.0.0.129");
        TunnelCommandException failure = assertThrows(TunnelCommandException.class,
                () -> ProxyProtocolSources.parse(String.join(",", sources)));
        assertTrue(failure.getMessage().contains("128"));
    }

    @Test
    void errorsDoNotEchoTheEntry() {
        TunnelCommandException failure = assertThrows(TunnelCommandException.class,
                () -> ProxyProtocolSources.parse("secret.invalid"));
        assertFalse(failure.getMessage().contains("secret.invalid"));
    }
}
