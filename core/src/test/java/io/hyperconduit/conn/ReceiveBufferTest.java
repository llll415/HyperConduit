package io.hyperconduit.conn;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiveBufferTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String drain(ReceiveBuffer buffer) {
        byte[] out = new byte[buffer.available()];
        int n = buffer.read(out, 0, out.length);
        return new String(out, 0, n, StandardCharsets.UTF_8);
    }

    @Test
    void inOrderChunksDeliverImmediately() {
        ReceiveBuffer buffer = new ReceiveBuffer(4096);
        buffer.put(0, bytes("hello "));
        buffer.put(6, bytes("world"));

        assertEquals(11, buffer.available());
        assertEquals(0, buffer.buffered());
        assertEquals("hello world", drain(buffer));
        assertEquals(11, buffer.nextOffset());
        assertEquals(0, buffer.available());
    }

    @Test
    void outOfOrderChunkIsHeldUntilTheGapFills() {
        ReceiveBuffer buffer = new ReceiveBuffer(4096);
        buffer.put(6, bytes("world"));

        assertEquals(0, buffer.available(), "must not deliver across a gap");
        assertEquals(5, buffer.buffered());

        buffer.put(0, bytes("hello "));
        assertEquals(11, buffer.available());
        assertEquals(0, buffer.buffered());
        assertEquals("hello world", drain(buffer));
    }

    @Test
    void severalOutOfOrderChunksAssembleInOneGo() {
        ReceiveBuffer buffer = new ReceiveBuffer(4096);
        buffer.put(7, bytes("C"));
        buffer.put(6, bytes("B"));

        assertEquals(0, buffer.available());
        assertEquals(2, buffer.buffered());

        buffer.put(0, bytes("AAAAAA"));
        assertEquals(8, buffer.available());
        assertEquals(0, buffer.buffered());
        assertEquals("AAAAAABC", drain(buffer));
    }

    @Test
    void exactDuplicateIsIgnored() {
        ReceiveBuffer buffer = new ReceiveBuffer(4096);
        buffer.put(6, bytes("world"));
        buffer.put(6, bytes("world"));
        assertEquals(5, buffer.buffered());

        buffer.put(0, bytes("hello "));
        assertEquals("hello world", drain(buffer));
    }

    @Test
    void chunkAlreadyDeliveredIsIgnored() {
        ReceiveBuffer buffer = new ReceiveBuffer(4096);
        buffer.put(0, bytes("hello"));
        assertEquals("hello", drain(buffer));

        buffer.put(0, bytes("hello"));
        assertEquals(0, buffer.available());
        assertEquals(5, buffer.nextOffset());
    }

    @Test
    void partiallyDeliveredChunkIsTrimmed() {
        ReceiveBuffer buffer = new ReceiveBuffer(4096);
        buffer.put(0, bytes("abc"));
        assertEquals("abc", drain(buffer));

        // A retransmission spanning both delivered and undelivered bytes.
        buffer.put(1, bytes("bcdef"));
        assertEquals("def", drain(buffer));
        assertEquals(6, buffer.nextOffset());
    }

    @Test
    void readHonoursMaxLenAndKeepsTheRemainder() {
        ReceiveBuffer buffer = new ReceiveBuffer(4096);
        buffer.put(0, bytes("abcdef"));

        byte[] out = new byte[2];
        assertEquals(2, buffer.read(out, 0, 2));
        assertArrayEquals(bytes("ab"), out);
        assertEquals(2, buffer.nextOffset());
        assertEquals(4, buffer.available());
        assertEquals("cdef", drain(buffer));
    }

    @Test
    void readWritesAtTheGivenDestinationOffset() {
        ReceiveBuffer buffer = new ReceiveBuffer(4096);
        buffer.put(0, bytes("xy"));
        byte[] dst = new byte[6];
        assertEquals(2, buffer.read(dst, 3, 6));
        assertArrayEquals(new byte[]{0, 0, 0, 'x', 'y', 0}, dst);
    }

    @Test
    void readOnEmptyBufferReturnsZero() {
        ReceiveBuffer buffer = new ReceiveBuffer(4096);
        assertEquals(0, buffer.read(new byte[16], 0, 16));
        assertEquals(0, buffer.nextOffset());
    }

    @Test
    void flowControlCreditAdvancesOnlyAsTheAppDrains() {
        ReceiveBuffer buffer = new ReceiveBuffer(1000);
        assertEquals(1000, buffer.maxAcceptableOffset());

        buffer.put(0, new byte[600]);
        assertEquals(1000, buffer.maxAcceptableOffset(),
                "credit must not move just because data arrived");

        assertEquals(600, buffer.read(new byte[600], 0, 600));
        assertEquals(1600, buffer.maxAcceptableOffset(),
                "credit moves only when the application consumes, which is what applies backpressure");
    }

    @Test
    void zeroLengthChunkIsANoOp() {
        ReceiveBuffer buffer = new ReceiveBuffer(4096);
        buffer.put(0, new byte[0]);
        buffer.put(5, new byte[0]);
        assertEquals(0, buffer.available());
        assertEquals(0, buffer.buffered());
    }

    @Test
    void sliceVariantReadsOnlyTheGivenRange() {
        ReceiveBuffer buffer = new ReceiveBuffer(4096);
        byte[] padded = "XXXhelloYYY".getBytes(StandardCharsets.UTF_8);
        buffer.put(0, padded, 3, 5);
        assertEquals("hello", drain(buffer));
    }

    @Test
    void rejectsNonPositiveWindow() {
        assertThrows(IllegalArgumentException.class, () -> new ReceiveBuffer(0));
        assertThrows(IllegalArgumentException.class, () -> new ReceiveBuffer(-1));
    }

    @Test
    void largeStreamReassemblesCorrectly() {
        ReceiveBuffer buffer = new ReceiveBuffer(1 << 20);
        int chunkSize = 1200;
        int chunks = 500;
        byte[] expected = new byte[chunkSize * chunks];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) (i * 31 + 7);
        }
        // Deliver every chunk out of order, in reverse, so nothing is promotable until the end.
        for (int i = chunks - 1; i >= 1; i--) {
            buffer.put((long) i * chunkSize, Arrays.copyOfRange(expected, i * chunkSize, (i + 1) * chunkSize));
        }
        assertEquals(0, buffer.available());
        assertEquals(expected.length - chunkSize, buffer.buffered());

        buffer.put(0, Arrays.copyOfRange(expected, 0, chunkSize));
        assertEquals(expected.length, buffer.available());

        byte[] actual = new byte[expected.length];
        int read = 0;
        while (read < actual.length) {
            int n = buffer.read(actual, read, Math.min(7777, actual.length - read));
            assertTrue(n > 0, "read must make progress");
            read += n;
        }
        assertArrayEquals(expected, actual);
    }
}
