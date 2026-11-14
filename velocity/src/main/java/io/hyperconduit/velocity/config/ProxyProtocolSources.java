package io.hyperconduit.velocity.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Strict parser for the {@code proxyProtocolTrustedSources} allow-list.
 *
 * <p>The grammar mirrors the sidecar's {@code net/netip} validation: an entry is either a literal
 * IPv4/IPv6 address or a CIDR range. Hostnames are never resolved, and zoned, IPv4-mapped, and
 * unspecified addresses are rejected so a trusted source can never widen to "everything". CIDR
 * entries are masked before the address check, so {@code 0.0.0.0/0}, {@code ::/0} and host-bit
 * forms such as {@code 1.2.3.4/0} are all refused once they collapse to an unspecified network.
 * Loopback literals are valid trusted sources.</p>
 */
public final class ProxyProtocolSources {

    /** Matches the sidecar's limit so a saved value can always be loaded by the tunnel process. */
    public static final int MAX_ENTRIES = 128;

    private ProxyProtocolSources() {
    }

    /** A stable reason that callers can map to their own localized message. */
    public enum Problem {
        EMPTY_LIST,
        EMPTY_ENTRY,
        TOO_MANY,
        NOT_LITERAL,
        SPECIAL_ADDRESS
    }

    /** Thrown for any invalid entry; the message is a stable enum name, never the value. */
    public static final class InvalidSourceException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final Problem problem;

        InvalidSourceException(Problem problem) {
            super(problem.name());
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    /**
     * Parses a comma-separated command value. Entries are trimmed, so a command may use spaces
     * after commas. Empty entries are rejected instead of being silently dropped.
     */
    public static List<String> parse(String raw) {
        if (raw == null || raw.strip().isEmpty()) {
            throw new InvalidSourceException(Problem.EMPTY_LIST);
        }
        String[] parts = raw.strip().split(",", -1);
        List<String> sources = new ArrayList<>(parts.length);
        for (String part : parts) {
            String entry = part.strip();
            if (entry.isEmpty()) {
                throw new InvalidSourceException(Problem.EMPTY_ENTRY);
            }
            if (sources.size() >= MAX_ENTRIES) {
                throw new InvalidSourceException(Problem.TOO_MANY);
            }
            validateEntry(entry);
            sources.add(entry);
        }
        return List.copyOf(sources);
    }

    /**
     * Validates a configuration array. Unlike {@link #parse(String)}, an empty list is accepted
     * here because the input switch may be disabled; the caller decides whether at least one
     * source is required. Surrounding whitespace is rejected in the file, not silently trimmed.
     */
    public static List<String> validateAll(List<String> raw) {
        if (raw == null) {
            throw new InvalidSourceException(Problem.EMPTY_LIST);
        }
        if (raw.size() > MAX_ENTRIES) {
            throw new InvalidSourceException(Problem.TOO_MANY);
        }
        List<String> sources = new ArrayList<>(raw.size());
        for (String entry : raw) {
            if (entry == null || entry.isEmpty()) {
                throw new InvalidSourceException(Problem.EMPTY_ENTRY);
            }
            if (!entry.equals(entry.strip())) {
                throw new InvalidSourceException(Problem.NOT_LITERAL);
            }
            validateEntry(entry);
            sources.add(entry);
        }
        return List.copyOf(sources);
    }

    /** Validates one literal address or CIDR range. */
    public static void validateEntry(String source) {
        int slash = source.indexOf('/');
        if (slash < 0) {
            parseAddress(source).requireTrusted();
            return;
        }
        if (slash != source.lastIndexOf('/')) {
            throw new InvalidSourceException(Problem.NOT_LITERAL);
        }

        String address = source.substring(0, slash);
        String bits = source.substring(slash + 1);
        boolean ipv6 = address.indexOf(':') >= 0;
        int prefixBits = parsePrefixLength(bits, ipv6 ? 128 : 32);

        // Mask before validating, like the sidecar: "1.2.3.4/0" must be rejected here because it
        // canonicalizes to the unspecified network 0.0.0.0/0.
        parseAddress(address).masked(prefixBits).requireTrusted();
    }

    /** Mirrors {@code netip.ParsePrefix}: at most three digits, no leading zero, no sign. */
    private static int parsePrefixLength(String bits, int maxBits) {
        if (bits.isEmpty() || bits.length() > 3) {
            throw new InvalidSourceException(Problem.NOT_LITERAL);
        }
        if (bits.length() > 1 && (bits.charAt(0) < '1' || bits.charAt(0) > '9')) {
            throw new InvalidSourceException(Problem.NOT_LITERAL);
        }
        int value = 0;
        for (int i = 0; i < bits.length(); i++) {
            char c = bits.charAt(i);
            if (c < '0' || c > '9') {
                throw new InvalidSourceException(Problem.NOT_LITERAL);
            }
            value = value * 10 + (c - '0');
        }
        if (value > maxBits) {
            throw new InvalidSourceException(Problem.NOT_LITERAL);
        }
        return value;
    }

    private static ParsedAddress parseAddress(String text) {
        if (text.indexOf('%') >= 0) {
            throw new InvalidSourceException(Problem.SPECIAL_ADDRESS);
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
                    throw new InvalidSourceException(Problem.NOT_LITERAL);
                }
                value = value * 10 + (c - '0');
                digits++;
                if (value > 255) {
                    throw new InvalidSourceException(Problem.NOT_LITERAL);
                }
            } else if (c == '.') {
                if (i == 0 || i == text.length() - 1 || text.charAt(i - 1) == '.') {
                    throw new InvalidSourceException(Problem.NOT_LITERAL);
                }
                if (position == 3) {
                    throw new InvalidSourceException(Problem.NOT_LITERAL);
                }
                fields[position++] = value;
                value = 0;
                digits = 0;
            } else {
                throw new InvalidSourceException(Problem.NOT_LITERAL);
            }
        }

        if (position < 3) {
            throw new InvalidSourceException(Problem.NOT_LITERAL);
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
                    throw new InvalidSourceException(Problem.NOT_LITERAL);
                }
                value = (value << 4) | digit;
                if (value > 0xFFFF) {
                    throw new InvalidSourceException(Problem.NOT_LITERAL);
                }
                digits++;
            }
            if (digits == 0) {
                throw new InvalidSourceException(Problem.NOT_LITERAL);
            }
            String afterGroup = rest.substring(digits);

            if (!afterGroup.isEmpty() && afterGroup.charAt(0) == '.') {
                if (ellipsis < 0 && count != 6) {
                    throw new InvalidSourceException(Problem.NOT_LITERAL);
                }
                if (count + 2 > 8) {
                    throw new InvalidSourceException(Problem.NOT_LITERAL);
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
                throw new InvalidSourceException(Problem.NOT_LITERAL);
            }
            if (rest.length() == 1) {
                throw new InvalidSourceException(Problem.NOT_LITERAL);
            }
            rest = rest.substring(1);
            if (rest.charAt(0) == ':') {
                if (ellipsis >= 0) {
                    throw new InvalidSourceException(Problem.NOT_LITERAL);
                }
                ellipsis = count;
                rest = rest.substring(1);
                if (rest.isEmpty()) {
                    break;
                }
            }
        }

        if (!rest.isEmpty()) {
            throw new InvalidSourceException(Problem.NOT_LITERAL);
        }
        if (count < 8) {
            if (ellipsis < 0) {
                throw new InvalidSourceException(Problem.NOT_LITERAL);
            }
            int expand = 8 - count;
            for (int i = count - 1; i >= ellipsis; i--) {
                groups[i + expand] = groups[i];
            }
            for (int i = ellipsis; i < ellipsis + expand; i++) {
                groups[i] = 0;
            }
        } else if (ellipsis >= 0) {
            throw new InvalidSourceException(Problem.NOT_LITERAL);
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
                    throw new InvalidSourceException(Problem.SPECIAL_ADDRESS);
                }
                // ::ffff:0:0/96 is the IPv4-mapped range; the sidecar rejects it as ambiguous.
                boolean mapped = groups[0] == 0 && groups[1] == 0 && groups[2] == 0
                        && groups[3] == 0 && groups[4] == 0 && groups[5] == 0xFFFF;
                if (mapped) {
                    throw new InvalidSourceException(Problem.SPECIAL_ADDRESS);
                }
                return;
            }
            if (groups[0] == 0 && groups[1] == 0 && groups[2] == 0 && groups[3] == 0) {
                throw new InvalidSourceException(Problem.SPECIAL_ADDRESS);
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
