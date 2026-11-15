package io.hyperconduit.sidecar;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * Strict literal {@code IP:port} parsing and endpoint comparison.
 *
 * <p>The telemetry schema carries socket addresses as text. This helper never
 * resolves hostnames, never accepts a zone identifier, and never accepts the
 * unspecified address. Two endpoints match only when both the normalized
 * address bytes and the port are equal; an IPv4 address and its IPv4-mapped
 * IPv6 form are the same endpoint.</p>
 */
final class EndpointText {

    private EndpointText() {
    }

    /** Returns a resolved endpoint, or {@code null} when the text is not exact. */
    static InetSocketAddress parse(String text) {
        if (text == null || text.isEmpty() || text.length() > 96) {
            return null;
        }
        int colon = text.lastIndexOf(':');
        if (colon < 1 || colon == text.length() - 1) {
            return null;
        }
        String host = text.substring(0, colon);
        String portText = text.substring(colon + 1);
        if (!portText.matches("[1-9][0-9]{0,4}")) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException invalid) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }

        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        } else if (host.indexOf('[') >= 0 || host.indexOf(']') >= 0) {
            return null;
        }
        if (host.isEmpty() || host.length() > 64) {
            return null;
        }
        // Only IPv4/IPv6 literals: no DNS names, no zone identifiers, no
        // percent escapes. This check also prevents getByName from resolving.
        if (!host.matches("[0-9a-fA-F:.]+")) {
            return null;
        }
        if (host.indexOf(':') < 0) {
            String[] octets = host.split("\\.", -1);
            if (octets.length != 4) {
                return null;
            }
            for (String octet : octets) {
                if (!octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255) {
                    return null;
                }
            }
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isAnyLocalAddress()) {
                return null;
            }
            return new InetSocketAddress(address, port);
        } catch (UnknownHostException invalid) {
            return null;
        }
    }

    /** Stable key used only inside the telemetry parser for duplicate checks. */
    static String key(InetSocketAddress address) {
        if (address == null || address.isUnresolved()) {
            return null;
        }
        return hex(normalized(address.getAddress())) + ":" + address.getPort();
    }

    static boolean matches(InetSocketAddress first, InetSocketAddress second) {
        if (first == null || second == null || first.isUnresolved() || second.isUnresolved()) {
            return false;
        }
        return first.getPort() == second.getPort()
                && java.util.Arrays.equals(normalized(first.getAddress()),
                normalized(second.getAddress()));
    }

    static String format(InetSocketAddress address) {
        if (address == null) {
            return "未知";
        }
        InetAddress ip = address.getAddress();
        String host = ip == null ? address.getHostString() : ip.getHostAddress();
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            return "[" + host + "]:" + address.getPort();
        }
        return host + ":" + address.getPort();
    }

    private static byte[] normalized(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && isV4Mapped(bytes)) {
            return new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
        }
        return bytes;
    }

    private static boolean isV4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(Character.forDigit((value >> 4) & 0x0f, 16));
            text.append(Character.forDigit(value & 0x0f, 16));
        }
        return text.toString();
    }
}