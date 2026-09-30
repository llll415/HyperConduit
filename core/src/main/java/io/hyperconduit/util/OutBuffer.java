package io.hyperconduit.util;

import java.util.Arrays;

/** Growable big-endian byte sink. Not thread-safe. */
public final class OutBuffer {

    private byte[] buf;
    private int pos;

    public OutBuffer() {
        this(64);
    }

    public OutBuffer(int initialCapacity) {
        this.buf = new byte[initialCapacity];
    }

    public int size() {
        return pos;
    }

    public OutBuffer writeByte(int b) {
        ensure(1);
        buf[pos++] = (byte) b;
        return this;
    }

    public OutBuffer writeShort(int v) {
        ensure(2);
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
        return this;
    }

    public OutBuffer writeInt(int v) {
        ensure(4);
        buf[pos++] = (byte) (v >>> 24);
        buf[pos++] = (byte) (v >>> 16);
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
        return this;
    }

    public OutBuffer writeLong(long v) {
        ensure(8);
        for (int i = 56; i >= 0; i -= 8) {
            buf[pos++] = (byte) (v >>> i);
        }
        return this;
    }

    public OutBuffer writeVarInt(long value) {
        int size = VarInt.encodedSize(value);
        ensure(size);
        switch (size) {
            case 1 -> buf[pos++] = (byte) value;
            case 2 -> {
                buf[pos++] = (byte) (VarInt.prefix(value) | (value >>> 8));
                buf[pos++] = (byte) value;
            }
            case 4 -> {
                buf[pos++] = (byte) (VarInt.prefix(value) | (value >>> 24));
                buf[pos++] = (byte) (value >>> 16);
                buf[pos++] = (byte) (value >>> 8);
                buf[pos++] = (byte) value;
            }
            default -> {
                buf[pos++] = (byte) (VarInt.prefix(value) | (value >>> 56));
                for (int i = 48; i >= 0; i -= 8) {
                    buf[pos++] = (byte) (value >>> i);
                }
            }
        }
        return this;
    }

    public OutBuffer writeBytes(byte[] src) {
        return writeBytes(src, 0, src.length);
    }

    public OutBuffer writeBytes(byte[] src, int off, int len) {
        ensure(len);
        System.arraycopy(src, off, buf, pos, len);
        pos += len;
        return this;
    }

    /** Copy-writes {@code len} bytes read from {@code src} starting at its current position. */
    public OutBuffer writeFrom(InBuffer src, int len) {
        ensure(len);
        src.readInto(buf, pos, len);
        pos += len;
        return this;
    }

    public void ensure(int extra) {
        if (pos + extra > buf.length) {
            int cap = buf.length;
            while (cap < pos + extra) {
                cap = cap < 1024 ? cap * 2 : cap + (cap >> 1);
            }
            buf = Arrays.copyOf(buf, cap);
        }
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, pos);
    }

    /** Copies the accumulated bytes into {@code dst} at {@code dstOff}. */
    public void writeTo(byte[] dst, int dstOff) {
        System.arraycopy(buf, 0, dst, dstOff, pos);
    }

    public void clear() {
        pos = 0;
    }
}
