package io.hyperconduit.sidecar;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the safe, code-independent user-facing text for sidecar failures. */
class SidecarExceptionTest {

    private static final String PREFIX = "[HyperConduit] ";

    @Test
    void knownCodesAreChinesePrefixedAndDoNotEchoTheCode() {
        List<String> codes = List.of(
                "listener_port_required", "listener_failed",
                "identity_changed", "authentication", "timeout", "startup_timeout", "ready_timeout",
                "binary_unavailable", "binary_invalid", "config_unavailable", "unsupported_platform",
                "launch_failed", "address_unresolved", "config", "tls",
                "storage", "target", "transport", "limit", "canceled", "not_running",
                "unexpected_exit", "exited_before_ready", "interrupted", "output_unavailable",
                "protocol_version", "protocol_invalid", "protocol_mode", "protocol_fingerprint",
                "protocol_duplicate_ready", "protocol_event", "protocol_state", "protocol_capability",
                "protocol_line_too_long");
        for (String code : codes) {
            SidecarException failure = new SidecarException(code);
            assertEquals(code, failure.code());
            assertSafeChineseMessage(failure.getMessage(), code);
        }
    }

    @Test
    void identityAuthenticationAndTimeoutHaveSpecificMessages() {
        assertTrue(new SidecarException("identity_changed").getMessage().contains("身份"));
        assertTrue(new SidecarException("authentication").getMessage().contains("认证"));
        assertTrue(new SidecarException("timeout").getMessage().contains("超时"));
    }

    @Test
    void processStartProtocolPlatformAndBinaryCodesAreMapped() {
        assertTrue(new SidecarException("binary_unavailable").getMessage().contains("隧道程序"));
        assertTrue(new SidecarException("binary_invalid").getMessage().contains("损坏"));
        assertTrue(new SidecarException("unsupported_platform").getMessage().contains("架构"));
        assertTrue(new SidecarException("launch_failed").getMessage().contains("启动"));
        assertTrue(new SidecarException("address_unresolved").getMessage().contains("地址"));
        assertTrue(new SidecarException("startup_timeout").getMessage().contains("超时"));
        assertTrue(new SidecarException("protocol_invalid").getMessage().contains("协议"));
        assertTrue(new SidecarException("exited_before_ready").getMessage().contains("退出"));
    }

    @Test
    void configFailureKeepsDiagnosticCodeOutOfTheUserMessage() {
        SidecarException failure = new SidecarException("config_unavailable", "private_config_permissions");

        assertEquals("config_unavailable", failure.code());
        assertEquals("private_config_permissions", failure.diagnosticCode());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("private_config_permissions"));
        assertFalse(failure.toString().contains("private_config_permissions"));
    }

    @Test
    void unknownCodeIsNeverEchoed() {
        String untrusted = "secret_token=abc\u0000../../passwd";
        SidecarException failure = new SidecarException(untrusted);

        assertEquals(untrusted, failure.code());
        assertEquals(PREFIX + "隧道进程遇到未知错误，请查看日志。", failure.getMessage());
        assertFalse(failure.getMessage().contains("secret_token"));
        assertFalse(failure.getMessage().contains("passwd"));
        assertFalse(failure.toString().contains("secret_token"));
    }

    @Test
    void nullCodeIsSafe() {
        SidecarException failure = new SidecarException(null);

        assertNull(failure.code());
        assertEquals(PREFIX + "隧道进程遇到未知错误，请查看日志。", failure.getMessage());
        assertFalse(failure.getMessage().contains("null"));
    }

    @Test
    void trustCodesAreChinesePrefixedAndDoNotEchoTheCode() {
        List<String> codes = List.of(
                "trust_command_timeout", "trust_command_canceled", "trust_protocol_version",
                "trust_response_limit", "trust_command_failed");
        for (String code : codes) {
            assertSafeChineseMessage(SidecarErrorText.describeTrust(new IOException(code)), code);
        }
    }

    @Test
    void trustMapperDelegatesStartupCodes() {
        assertTrue(SidecarErrorText.describeTrust(new IOException("binary_invalid")).contains("损坏"));
        assertTrue(SidecarErrorText.describeTrust(new IOException("launch_failed")).contains("启动"));
    }

    @Test
    void unknownTrustFailureIsNeverEchoed() {
        String untrusted = "raw trust stderr token=abc";
        String message = SidecarErrorText.describeTrust(new IOException(untrusted));

        assertEquals(PREFIX + "无法访问信任记录，请稍后重试。", message);
        assertFalse(message.contains("token"));
        assertFalse(message.contains("stderr"));
        assertEquals(PREFIX + "无法访问信任记录，请稍后重试。", SidecarErrorText.describeTrust(null));
    }

    private static void assertSafeChineseMessage(String message, String code) {
        assertTrue(message.startsWith(PREFIX), () -> "missing prefix: " + message);
        assertTrue(message.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF),
                () -> "missing Chinese text: " + message);
        assertFalse(message.contains(code), () -> "message echoed the internal code: " + message);
        assertFalse(message.contains("HC"), () -> "use the full HyperConduit name: " + message);
    }
}
