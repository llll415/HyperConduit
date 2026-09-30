package io.hyperconduit.util;

/** Big-endian read cursor over a byte array. Not thread-safe. */
public final class InBuffer {

    private final byte[] buf;
    private final int end;
    private int pos;

    public InBuffer(byte[] buf) {
        this(buf, 0, buf.length);
    }

    public InBuffer(byte[] buf, int off, int len) {
        this.buf = buf;
        this.pos = off;
        this.end = off + len;
    }

    public int remaining() {
        return end - pos;
    }

    public boolean hasRemaining() {
        return pos < end;
    }

    public int position() {
        return pos;
    }

    public int readByte() {
        require(1);
        return buf[pos++] & 0xFF;
    }

    public int readShort() {
        require(2);
        int v = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
        pos += 2;
        return v;
    }

    public int readInt() {
        require(4);
        int v = ((buf[pos] & 0xFF) << 24)
                | ((buf[pos + 1] & 0xFF) << 16)
                | ((buf[pos + 2] & 0xFF) << 8)
                | (buf[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    public long readLong() {
        require(8);
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (buf[pos + i] & 0xFFL);
        }
        pos += 8;
        return v;
    }

    public long readVarInt() {
        require(1);
        int first = buf[pos] & 0xFF;
        int size = 1 << (first >>> 6);
        require(size);
        // The top two bits are the length prefix; strip them from the value.
        long value = first & 0x3FL;
        for (int i = 1; i < size; i++) {
            value = (value << 8) | (buf[pos + i] & 0xFFL);
        }
        pos += size;
        return value;
    }

    public byte[] readBytes(int len) {
        require(len);
        byte[] out = new byte[len];
        System.arraycopy(buf, pos, out, 0, len);
        pos += len;
        return out;
    }

    /** Reads {@code len} bytes into {@code dst} at {@code dstOff} without copying into a new array. */
    public void readInto(byte[] dst, int dstOff, int len) {
        require(len);
        System.arraycopy(buf, pos, dst, dstOff, len);
        pos += len;
    }

    public byte[] array() {
        return buf;
    }

    private void require(int n) {
        if (end - pos < n) {
            throw new BufferUnderflow(n, end - pos);
        }
    }

    public static final class BufferUnderflow extends RuntimeException {
        public BufferUnderflow(int wanted, int available) {
            super("need " + wanted + " bytes, only " + available + " available");
        }
    }
}
