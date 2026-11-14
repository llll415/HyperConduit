package io.hyperconduit.sidecar;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Strict parsing and safe formatting of the stderr {@code proxy_log} event. */
class ProxyLogEventTest {

    @Test
    void formatsValidEventWithoutRawMessage() throws IOException {
        ProxyLogEvent.Event event = event("""
                {"version":1,"event":"proxy_log","reason":"untrusted_source",
                 "source":"203.0.113.7:54321","client":"198.51.100.9:443",
                 "length":1200,"suppressed":3}
                """);

        assertNotNull(event);
        assertEquals("[HyperConduit] 已丢弃 UDP 数据：来源=203.0.113.7:54321，"
                        + "声明客户端=198.51.100.9:443（未验证），原因=来源不可信，"
                        + "报文长度=1200，已合并=3。",
                event.format());
    }

    @Test
    void omitsClientSegmentWhenNoClaimWasSent() throws IOException {
        ProxyLogEvent.Event event = event("""
                {"version":1,"event":"proxy_log","reason":"missing_header",
                 "source":"203.0.113.7:54321","length":0,"suppressed":0}
                """);

        assertNotNull(event);
        assertEquals("[HyperConduit] 已丢弃 UDP 数据：来源=203.0.113.7:54321，"
                        + "原因=缺少Proxy Protocol v2头部，报文长度=0，已合并=0。",
                event.format());
    }

    @Test
    void mapsEveryReasonToFixedChineseText() {
        assertEquals("来源不可信", ProxyLogEvent.reasonText("untrusted_source"));
        assertEquals("缺少Proxy Protocol v2头部", ProxyLogEvent.reasonText("missing_header"));
        assertEquals("头部截断", ProxyLogEvent.reasonText("truncated_header"));
        assertEquals("头部无效", ProxyLogEvent.reasonText("invalid_header"));
        assertEquals("不支持的协议类型", ProxyLogEvent.reasonText("unsupported_protocol"));
        assertEquals("地址无效", ProxyLogEvent.reasonText("invalid_address"));
        assertEquals("地址映射数量达到上限", ProxyLogEvent.reasonText("route_limit"));
        assertEquals("地址映射冲突", ProxyLogEvent.reasonText("route_conflict"));
        assertEquals("未知原因", ProxyLogEvent.reasonText("not_a_reason"));
    }

    @Test
    void acceptsEveryWhitelistedReason() throws IOException {
        for (String reason : ProxyLogEvent.REASONS) {
            ProxyLogEvent.Event event = event("{\"version\":1,\"event\":\"proxy_log\","
                    + "\"reason\":\"" + reason + "\",\"source\":\"203.0.113.7:53\","
                    + "\"length\":1,\"suppressed\":0}");
            assertNotNull(event, reason);
            assertEquals(reason, event.reason());
        }
    }

    @Test
    void rejectsMalformedOrUnsafeFrames() {
        assertInvalid("""
                {"version":2,"event":"proxy_log","reason":"untrusted_source",
                 "source":"203.0.113.7:53","length":1,"suppressed":0}
                """);
        assertInvalid("""
                {"version":"1","event":"proxy_log","reason":"untrusted_source",
                 "source":"203.0.113.7:53","length":1,"suppressed":0}
                """);
        assertInvalid("""
                {"version":1,"event":"other","reason":"untrusted_source",
                 "source":"203.0.113.7:53","length":1,"suppressed":0}
                """);
        assertInvalid("""
                {"version":1,"event":"proxy_log","reason":"made_up",
                 "source":"203.0.113.7:53","length":1,"suppressed":0}
                """);
        assertInvalid("""
                {"version":1,"event":"proxy_log","reason":"untrusted_source",
                 "source":"203.0.113.7:53","length":1,"suppressed":0,"message":"raw"}
                """);
        assertInvalid("""
                {"version":1,"event":"proxy_log","reason":"untrusted_source",
                 "source":"203.0.113.7:53","length":1}
                """);
        assertInvalid("""
                {"version":1,"event":"proxy_log","reason":"untrusted_source",
                 "source":"203.0.113.7:53","client":null,"length":1,"suppressed":0}
                """);
        assertInvalid("""
                {"version":1,"event":"proxy_log","reason":"untrusted_source",
                 "source":"203.0.113.7:53","client":"example.com:53",
                 "length":1,"suppressed":0}
                """);
        assertInvalid("""
                {"version":1,"event":"proxy_log","reason":"untrusted_source",
                 "source":42,"length":1,"suppressed":0}
                """);
        assertInvalid("""
                {"version":1,"event":"proxy_log","reason":"untrusted_source",
                 "source":"203.0.113.7:53","length":65536,"suppressed":0}
                """);
        assertInvalid("""
                {"version":1,"event":"proxy_log","reason":"untrusted_source",
                 "source":"203.0.113.7:53","length":-1,"suppressed":0}
                """);
        assertInvalid("""
                {"version":1,"event":"proxy_log","reason":"untrusted_source",
                 "source":"203.0.113.7:53","length":1.5,"suppressed":0}
                """);
        assertInvalid("""
                {"version":1,"event":"proxy_log","reason":"untrusted_source",
                 "source":"203.0.113.7:53","length":1,"suppressed":-1}
                """);
        assertInvalid("""
                {"version":1,"event":"proxy_log","reason":"untrusted_source",
                 "source":"203.0.113.7:53","length":1,"suppressed":1.5}
                """);
        assertInvalid("""
                {"version":1,"event":"proxy_log","reason":"untrusted_source",
                 "reason":"route_limit","source":"203.0.113.7:53",
                 "length":1,"suppressed":0}
                """);
    }

    @Test
    void acceptsOnlyLiteralAddresses() {
        assertEquals("203.0.113.7:53", ProxyLogEvent.address("203.0.113.7:53"));
        String ipv6 = ProxyLogEvent.address("[2001:db8::1]:53");
        assertNotNull(ipv6);
        assertTrue(ipv6.startsWith("["));
        assertTrue(ipv6.endsWith("]:53"));

        assertNull(ProxyLogEvent.address(null));
        assertNull(ProxyLogEvent.address(""));
        assertNull(ProxyLogEvent.address("203.0.113.7"));
        assertNull(ProxyLogEvent.address("203.0.113.7:0"));
        assertNull(ProxyLogEvent.address("203.0.113.7:65536"));
        assertNull(ProxyLogEvent.address("203.0.113.7: 53"));
        assertNull(ProxyLogEvent.address("203.0.113.007:53"));
        assertNull(ProxyLogEvent.address("example.com:53"));
        assertNull(ProxyLogEvent.address("2001:db8::1:53"));
        assertNull(ProxyLogEvent.address("[1.2.3.4]:53"));
        assertNull(ProxyLogEvent.address("[fe80::1%eth0]:53"));
        assertNull(ProxyLogEvent.address("[::1:53"));
        assertNull(ProxyLogEvent.address("1.2.3.4:53\n"));
    }

    private static ProxyLogEvent.Event event(String json) throws IOException {
        return ProxyLogEvent.parse(TelemetryTracker.parseRoot(json));
    }

    private static void assertInvalid(String json) {
        try {
            assertNull(ProxyLogEvent.parse(TelemetryTracker.parseRoot(json)), json);
        } catch (IOException malformed) {
            // A duplicate field is rejected by the shared strict parser.
        }
    }
}