package io.hyperconduit.conn;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryCookieTest {

    @Test
    void cookieBindsPeerCidAndExpiry() {
        RetryCookie cookies = new RetryCookie();
        InetSocketAddress peer = new InetSocketAddress("127.0.0.1", 42000);
        long now = System.currentTimeMillis();
        byte[] cookie = cookies.mint(peer, 0x12345678, now + 5_000L);

        assertTrue(cookies.verify(peer, 0x12345678, cookie, now));
        assertFalse(cookies.verify(new InetSocketAddress("127.0.0.1", 42001), 0x12345678, cookie, now));
        assertFalse(cookies.verify(peer, 0x12345679, cookie, now));
        assertFalse(cookies.verify(peer, 0x12345678, cookie, now + 5_001L));
    }
}
