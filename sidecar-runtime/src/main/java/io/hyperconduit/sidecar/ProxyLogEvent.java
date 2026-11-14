package io.hyperconduit.sidecar;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Set;

/**
 * Strict parser and formatter for the stderr {@code proxy_log} event.
 *
 * <p>Only bounded, typed fields are accepted. The raw stderr line is never
 * retained or forwarded: a malformed frame is ignored, and the formatted
 * message is assembled exclusively from whitelisted values so an arbitrary
 * {@code message} field can never reach the game log.
 *
 * <p>{@link Event#format()} maps each validated reason code to a fixed Chinese
 * description; the English protocol code is never written to the user log.
 */
final class ProxyLogEvent {

    private static final Map<String, String> REASON_TEXT = Map.of(
            "untrusted_source", "来源不可信",
            "missing_header", "缺少Proxy Protocol v2头部",
            "truncated_header", "头部截断",
            "invalid_header", "头部无效",
            "unsupported_protocol", "不支持的协议类型",
            "invalid_address", "地址无效",
            "route_limit", "地址映射数量达到上限",
            "route_conflict", "地址映射冲突");
    static final Set<String> REASONS = REASON_TEXT.keySet();
    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "version", "event", "reason", "source", "length", "suppressed");
    private static final Set<String> FIELDS_WITH_CLIENT = Set.of(
            "version", "event", "reason", "source", "client", "length", "suppressed");

    private ProxyLogEvent() {
    }

    /**
     * A validated proxy log event; {@link #format()} is the only output path.
     * {@code reason} remains the protocol code and is mapped to Chinese only
     * when formatted.
     */
    record Event(String source, String client, String reason, int length, long suppressed) {

        String format() {
            StringBuilder message = new StringBuilder("[HyperConduit] 已丢弃 UDP 数据：来源=")
                    .append(source);
            if (client != null) {
                message.append("，声明客户端=").append(client).append("（未验证）");
            }
            return message.append("，原因=").append(reasonText(reason))
                    .append("，报文长度=").append(length)
                    .append("，已合并=").append(suppressed)
                    .append("。")
                    .toString();
        }
    }

    /** Fixed Chinese description for a validated reason code. */
    static String reasonText(String reason) {
        String text = REASON_TEXT.get(reason);
        return text != null ? text : "未知原因";
    }

    /** Returns a validated event, or {@code null} when the frame is malformed. */
    static Event parse(Map<String, Object> root) {
        try {
            return parseChecked(root);
        } catch (RuntimeException invalid) {
            // A malformed or security-rejected frame must never escape the
            // stderr reader; it is simply ignored.
            return null;
        }
    }

    private static Event parseChecked(Map<String, Object> root) {
        if (root == null) return null;
        Set<String> fields = root.keySet();
        if (!fields.equals(REQUIRED_FIELDS) && !fields.equals(FIELDS_WITH_CLIENT)) return null;
        if (!isVersionOne(root.get("version"))) return null;
        if (!"proxy_log".equals(root.get("event"))) return null;

        Object reasonValue = root.get("reason");
        if (!(reasonValue instanceof String reason) || !REASONS.contains(reason)) return null;

        Object sourceValue = root.get("source");
        if (!(sourceValue instanceof String sourceText)) return null;
        String source = address(sourceText);
        if (source == null) return null;

        String client = null;
        if (fields.contains("client")) {
            Object clientValue = root.get("client");
            if (!(clientValue instanceof String clientText)) return null;
            client = address(clientText);
            if (client == null) return null;
        }

        Integer length = integer(root.get("length"), 65535);
        if (length == null) return null;
        Long suppressed = nonNegativeLong(root.get("suppressed"));
        if (suppressed == null) return null;
        return new Event(source, client, reason, length, suppressed);
    }

    private static boolean isVersionOne(Object value) {
        return value instanceof TelemetryTracker.JsonNumber number && number.text().equals("1");
    }

    private static Integer integer(Object value, int maximum) {
        if (!(value instanceof TelemetryTracker.JsonNumber number)) return null;
        String text = number.text();
        if (!text.matches("0|[1-9][0-9]*")) return null;
        try {
            int parsed = Integer.parseInt(text);
            return parsed <= maximum ? parsed : null;
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    private static Long nonNegativeLong(Object value) {
        if (!(value instanceof TelemetryTracker.JsonNumber number)) return null;
        String text = number.text();
        if (!text.matches("0|[1-9][0-9]*")) return null;
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    /**
     * Accepts only a literal IPv4 or bracketed IPv6 address with a decimal
     * port. Hostnames, zone identifiers, and unbracketed IPv6 are rejected.
     * The returned form is canonical and safe to embed in a log line.
     */
    static String address(String text) {
        if (text == null || text.length() > 128) return null;
        int colon = text.lastIndexOf(':');
        if (colon < 1) return null;
        String host = text.substring(0, colon);
        String portText = text.substring(colon + 1);
        if (!portText.matches("[0-9]{1,5}")) return null;
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException overflow) {
            return null;
        }
        if (port < 1 || port > 65535) return null;

        boolean ipv6 = host.startsWith("[");
        if (ipv6) {
            if (host.length() < 3 || !host.endsWith("]")) return null;
            host = host.substring(1, host.length() - 1);
            if (!host.contains(":") || !host.matches("[0-9a-fA-F:.]+")) return null;
        } else {
            if (host.indexOf(':') >= 0 || !isIpv4(host)) return null;
        }
        if (host.indexOf('%') >= 0) return null;

        try {
            String literal = InetAddress.getByName(host).getHostAddress();
            return (literal.contains(":") ? "[" + literal + "]" : literal) + ":" + port;
        } catch (UnknownHostException | RuntimeException invalid) {
            return null;
        }
    }

    private static boolean isIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            // Reject leading zeros so an address is unambiguous decimal.
            if (!part.matches("0|[1-9][0-9]{0,2}")) return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }
}
