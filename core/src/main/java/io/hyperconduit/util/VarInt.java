package io.hyperconduit.util;

/**
 * RFC 9000 variable-length integer: a 2-bit length prefix followed by 6/14/30/62 value bits,
 * big-endian within the field.
 */
public final class VarInt {

    public static final long MAX_VALUE = (1L << 62) - 1;

    private static final int[] LENGTHS = {1, 2, 4, 8};

    private VarInt() {
    }

    public static int encodedSize(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("varint cannot be negative: " + value);
        }
        if (value < (1L << 6)) {
            return LENGTHS[0];
        }
        if (value < (1L << 14)) {
            return LENGTHS[1];
        }
        if (value < (1L << 30)) {
            return LENGTHS[2];
        }
        if (value <= MAX_VALUE) {
            return LENGTHS[3];
        }
        throw new IllegalArgumentException("value exceeds 62 bits: " + value);
    }

    /** The two-bit length prefix for the shortest encoding of {@code value}. */
    static int prefix(long value) {
        return switch (encodedSize(value)) {
            case 1 -> 0x00;
            case 2 -> 0x40;
            case 4 -> 0x80;
            default -> 0xC0;
        };
    }
}
