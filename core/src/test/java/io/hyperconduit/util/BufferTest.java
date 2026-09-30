package io.hyperconduit.util;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BufferTest {

    @Test
    void varIntUsesShortestEncodingAtEachBoundary() {
        record Case(long value, int size, byte... expected) {
        }
        Case[] cases = {
                new Case(0, 1, (byte) 0x00),
                new Case(37, 1, (byte) 0x25),
                new Case(63, 1, (byte) 0x3F),
                new Case(64, 2, (byte) 0x40, (byte) 0x40),
                new Case(15293, 2, (byte) 0x7B, (byte) 0xBD),
                new Case(16383, 2, (byte) 0x7F, (byte) 0xFF),
                new Case(16384, 4, (byte) 0x80, (byte) 0x00, (byte) 0x40, (byte) 0x00),
                new Case(494878333, 4, (byte) 0x9D, (byte) 0x7F, (byte) 0x3E, (byte) 0x7D),
                new Case(1073741823, 4, (byte) 0xBF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF),
                new Case(1073741824, 8, (byte) 0xC0, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                        (byte) 0x40, (byte) 0x00, (byte) 0x00, (byte) 0x00),
        };
        for (Case c : cases) {
            assertEquals(c.size(), VarInt.encodedSize(c.value()), "size of " + c.value());
            OutBuffer out = new OutBuffer();
            out.writeVarInt(c.value());
            assertArrayEquals(c.expected(), out.toByteArray(), "encoding of " + c.value());
            assertEquals(c.value(), new InBuffer(out.toByteArray()).readVarInt(), "decode of " + c.value());
        }
    }

    @Test
    void varIntRoundTripsAcrossTheFullRange() {
        Random rng = new Random(0xC0FFEE);
        OutBuffer out = new OutBuffer();
        long[] values = new long[4096];
        for (int i = 0; i < values.length; i++) {
            values[i] = rng.nextLong() & VarInt.MAX_VALUE;
            out.writeVarInt(values[i]);
        }
        InBuffer in = new InBuffer(out.toByteArray());
        for (long value : values) {
            assertEquals(value, in.readVarInt());
        }
        assertEquals(0, in.remaining());
    }

    @Test
    void varIntRejectsNegativeAndOversizedValues() {
        assertThrows(IllegalArgumentException.class, () -> VarInt.encodedSize(-1));
        assertThrows(IllegalArgumentException.class, () -> VarInt.encodedSize(1L << 62));
    }

    @Test
    void fixedWidthScalarsRoundTrip() {
        OutBuffer out = new OutBuffer();
        out.writeByte(0xAB).writeShort(0xBEEF).writeInt(0xDEADBEEF).writeLong(0x0123456789ABCDEFL);
        byte[] bytes = out.toByteArray();
        assertEquals(1 + 2 + 4 + 8, bytes.length);

        InBuffer in = new InBuffer(bytes);
        assertEquals(0xAB, in.readByte());
        assertEquals(0xBEEF, in.readShort());
        assertEquals(0xDEADBEEF, in.readInt());
        assertEquals(0x0123456789ABCDEFL, in.readLong());
        assertEquals(0, in.remaining());
    }

    @Test
    void bufferGrowsPastInitialCapacity() {
        OutBuffer out = new OutBuffer(4);
        byte[] big = new byte[10_000];
        new Random(7).nextBytes(big);
        out.writeBytes(big);
        assertEquals(big.length, out.size());
        assertArrayEquals(big, out.toByteArray());
    }

    @Test
    void writeToCopiesWithoutTruncating() {
        OutBuffer out = new OutBuffer();
        out.writeVarInt(300).writeBytes(new byte[]{1, 2, 3});
        byte[] dst = new byte[out.size() + 2];
        out.writeTo(dst, 1);
        assertEquals(0, dst[0]);
        assertEquals(0, dst[dst.length - 1]);

        InBuffer in = new InBuffer(dst, 1, out.size());
        assertEquals(300, in.readVarInt());
        assertArrayEquals(new byte[]{1, 2, 3}, in.readBytes(3));
    }

    @Test
    void writeFromMovesBytesBetweenBuffers() {
        OutBuffer src = new OutBuffer();
        src.writeBytes(new byte[]{9, 8, 7, 6});
        InBuffer in = new InBuffer(src.toByteArray());
        in.readByte();

        OutBuffer dst = new OutBuffer();
        dst.writeFrom(in, in.remaining());
        assertArrayEquals(new byte[]{8, 7, 6}, dst.toByteArray());
    }

    @Test
    void underflowReportsWhatWasMissing() {
        InBuffer in = new InBuffer(new byte[]{1, 2});
        assertEquals(1, in.readByte());
        InBuffer.BufferUnderflow e = assertThrows(InBuffer.BufferUnderflow.class, () -> in.readBytes(4));
        assertTrue(e.getMessage().contains("need 4"));
        assertTrue(e.getMessage().contains("only 1"));
    }
}
