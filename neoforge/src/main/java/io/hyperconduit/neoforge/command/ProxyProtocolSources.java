package io.hyperconduit.neoforge.command;

import java.util.ArrayList;
import java.util.List;

/**
 * Strict parser for the {@code proxyProtocolTrustedSources} allow-list.
 *
 * <p>The accepted grammar mirrors the sidecar's {@code net/netip} validation exactly: an entry is
 * either a literal IPv4/IPv6 address or a CIDR range, hostnames are never resolved, and zoned,
 * IPv4-mapped, and unspecified addresses are rejected. CIDR ranges are masked before the address
 * check, so {@code 0.0.0.0/0}, {@code ::/0}, and host-bit forms such as {@code 1.2.3.4/0} are all
 * refused once they collapse to an unspecified network. Loopback literals are valid trusted
 * sources.</p>
 *
 * <p>Entries are split on commas and trimmed. The stored value keeps the user's spelling; the
 * sidecar canonicalizes it again when it loads the configuration.</p>
 */
public final class ProxyProtocolSources {

    /** Matches the sidecar's limit so a saved command value can always be loaded. */
    public static final int MAX_ENTRIES = 128;

    private static final String LITERAL_HINT =
            "可信来源必须是 IP 或 CIDR 字面值（不支持域名），用逗号分隔。";
    private static final String SPECIAL_HINT =
            "可信来源不能是 IPv6 zone、IPv4-mapped 或未指定地址（0.0.0.0、::）。";
    private static final String LIMIT_HINT = "可信来源最多 " + MAX_ENTRIES + " 条。";
    private static final String EMPTY_HINT = "可信来源不能为空；如需清空请使用 clear。";
    private static final String EMPTY_ENTRY_HINT = "可信来源不能包含空条目；请用英文逗号分隔。";

    private ProxyProtocolSources() {
    }

    /**
     * Parses a comma-separated list of literal addresses and CIDR ranges.
     *
     * @throws TunnelCommandException with a Chinese message when the list is empty, contains an
     *         empty entry, exceeds {@link #MAX_ENTRIES}, or contains an invalid entry
     */
    public static List<String> parse(String raw) {
        if (raw == null) {
            throw new TunnelCommandException(EMPTY_HINT);
        }
        String text = raw.strip();
        if (text.isEmpty()) {
            throw new TunnelCommandException(EMPTY_HINT);
        }

        String[] parts = text.split(",", -1);
        List<String> sources = new ArrayList<>(parts.length);
        for (String part : parts) {
            String entry = part.strip();
            if (entry.isEmpty()) {
                throw new TunnelCommandException(EMPTY_ENTRY_HINT);
            }
            if (sources.size() >= MAX_ENTRIES) {
                throw new TunnelCommandException(LIMIT_HINT);
            }
            validate(entry);
            sources.add(entry);
        }
        return List.copyOf(sources);
    }

    private static void validate(String source) {
        int slash = source.indexOf('/');
        if (slash < 0) {
            parseAddress(source).requireTrusted();
            return;
        }
        if (slash != source.lastIndexOf('/')) {
            throw new TunnelCommandException(LITERAL_HINT);
        }

        String address = source.substring(0, slash);
        String bits = source.substring(slash + 1);
        boolean ipv6 = address.indexOf(':') >= 0;
        int prefixBits = parsePrefixLength(bits, ipv6 ? 128 : 32);

        // Mask before validating, like the sidecar: "1.2.3.4/0" must be rejected here
        // because it canonicalizes to the unspecified network 0.0.0.0/0.
        parseAddress(address).masked(prefixBits).requireTrusted();
    }

    /** Mirrors {@code netip.ParsePrefix}: at most three digits, no leading zero, no sign. */
    private static int parsePrefixLength(String bits, int maxBits) {
        if (bits.isEmpty() || bits.length() > 3) {
            throw new TunnelCommandException(LITERAL_HINT);
        }
        if (bits.length() > 1 && (bits.charAt(0) < '1' || bits.charAt(0) > '9')) {
            throw new TunnelCommandException(LITERAL_HINT);
        }
        int value = 0;
        for (int i = 0; i < bits.length(); i++) {
            char c = bits.charAt(i);
            if (c < '0' || c > '9') {
                throw new TunnelCommandException(LITERAL_HINT);
            }
            value = value * 10 + (c - '0');
        }
        if (value > maxBits) {
            throw new TunnelCommandException(LITERAL_HINT);
        }
        return value;
    }

    private static ParsedAddress parseAddress(String text) {
        if (text.indexOf('%') >= 0) {
            throw new TunnelCommandException(SPECIAL_HINT);
        }
        if (text.indexOf(':') >= 0) {
            return new ParsedAddress(true, parseIpv6(text));
        }
        return new ParsedAddress(false, parseIpv4(text));
    }

    /** Mirrors {@code netip.parseIPv4Fields}: exactly four decimal octets, no leading zeros. */
    private static int[] parseIpv4(String text) {
        int[] fields = new int[4];
        int position = 0;
        int value = 0;
        int digits = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                if (digits == 1 && value == 0) {
                    throw new TunnelCommandException(LITERAL_HINT);
                }
                value = value * 10 + (c - '0');
                digits++;
                if (value > 255) {
                    throw new TunnelCommandException(LITERAL_HINT);
                }
            } else if (c == '.') {
                if (i == 0 || i == text.length() - 1 || text.charAt(i - 1) == '.') {
                    throw new TunnelCommandException(LITERAL_HINT);
                }
                if (position == 3) {
                    throw new TunnelCommandException(LITERAL_HINT);
                }
                fields[position++] = value;
                value = 0;
                digits = 0;
            } else {
                throw new TunnelCommandException(LITERAL_HINT);
            }
        }

        if (position < 3) {
            throw new TunnelCommandException(LITERAL_HINT);
        }
        fields[3] = value;
        return fields;
    }

    /**
     * Mirrors {@code netip.parseIPv6}: one optional {@code ::} that must expand to at least one
     * zero group, at most four hex digits per group, and an embedded IPv4 address only in the
     * final two groups. A zone is rejected before this method is reached.
     */
    private static int[] parseIpv6(String text) {
        int[] groups = new int[8];
        int count = 0;
        int ellipsis = -1;
        String rest = text;

        if (rest.length() >= 2 && rest.charAt(0) == ':' && rest.charAt(1) == ':') {
            ellipsis = 0;
            rest = rest.substring(2);
            if (rest.isEmpty()) {
                return groups;
            }
        }

        while (count < 8) {
            int digits = 0;
            int value = 0;
            while (digits < rest.length()) {
                int digit = hexDigit(rest.charAt(digits));
                if (digit < 0) {
                    break;
                }
                if (digits > 3) {
                    throw new TunnelCommandException(LITERAL_HINT);
                }
                value = (value << 4) | digit;
                if (value > 0xFFFF) {
                    throw new TunnelCommandException(LITERAL_HINT);
                }
                digits++;
            }
            if (digits == 0) {
                throw new TunnelCommandException(LITERAL_HINT);
            }
            String afterGroup = rest.substring(digits);

            if (!afterGroup.isEmpty() && afterGroup.charAt(0) == '.') {
                if (ellipsis < 0 && count != 6) {
                    throw new TunnelCommandException(LITERAL_HINT);
                }
                if (count + 2 > 8) {
                    throw new TunnelCommandException(LITERAL_HINT);
                }
                int[] ipv4 = parseIpv4(rest);
                groups[count++] = (ipv4[0] << 8) | ipv4[1];
                groups[count++] = (ipv4[2] << 8) | ipv4[3];
                rest = "";
                break;
            }

            groups[count++] = value;
            rest = afterGroup;
            if (rest.isEmpty()) {
                break;
            }
            if (rest.charAt(0) != ':') {
                throw new TunnelCommandException(LITERAL_HINT);
            }
            if (rest.length() == 1) {
                throw new TunnelCommandException(LITERAL_HINT);
            }
            rest = rest.substring(1);
            if (rest.charAt(0) == ':') {
                if (ellipsis >= 0) {
                    throw new TunnelCommandException(LITERAL_HINT);
                }
                ellipsis = count;
                rest = rest.substring(1);
                if (rest.isEmpty()) {
                    break;
                }
            }
        }

        if (!rest.isEmpty()) {
            throw new TunnelCommandException(LITERAL_HINT);
        }
        if (count < 8) {
            if (ellipsis < 0) {
                throw new TunnelCommandException(LITERAL_HINT);
            }
            int expand = 8 - count;
            for (int i = count - 1; i >= ellipsis; i--) {
                groups[i + expand] = groups[i];
            }
            for (int i = ellipsis; i < ellipsis + expand; i++) {
                groups[i] = 0;
            }
        } else if (ellipsis >= 0) {
            throw new TunnelCommandException(LITERAL_HINT);
        }
        return groups;
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private record ParsedAddress(boolean ipv6, int[] groups) {

        private ParsedAddress masked(int bits) {
            int width = ipv6 ? 16 : 8;
            int[] masked = new int[groups.length];
            int remaining = bits;
            for (int i = 0; i < groups.length; i++) {
                if (remaining >= width) {
                    masked[i] = groups[i];
                    remaining -= width;
                } else if (remaining == 0) {
                    masked[i] = 0;
                } else {
                    int mask = ((1 << remaining) - 1) << (width - remaining);
                    masked[i] = groups[i] & mask;
                    remaining = 0;
                }
            }
            return new ParsedAddress(ipv6, masked);
        }

        private void requireTrusted() {
            if (ipv6) {
                if (isUnspecified()) {
                    throw new TunnelCommandException(SPECIAL_HINT);
                }
                // ::ffff:0:0/96 is the IPv4-mapped range; the sidecar rejects it as ambiguous.
                boolean mapped = groups[0] == 0 && groups[1] == 0 && groups[2] == 0
                        && groups[3] == 0 && groups[4] == 0 && groups[5] == 0xFFFF;
                if (mapped) {
                    throw new TunnelCommandException(SPECIAL_HINT);
                }
                return;
            }
            if (groups[0] == 0 && groups[1] == 0 && groups[2] == 0 && groups[3] == 0) {
                throw new TunnelCommandException(SPECIAL_HINT);
            }
        }

        private boolean isUnspecified() {
            for (int group : groups) {
                if (group != 0) {
                    return false;
                }
            }
            return true;
        }
    }
}
