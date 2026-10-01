package io.hyperconduit.conn;

import io.hyperconduit.ProtocolException;
import io.hyperconduit.util.InBuffer;
import io.hyperconduit.util.OutBuffer;

/** Cleartext v2 handshake envelope used before per-session transport keys exist. */
public final class HandshakePacket {

    public static final int MAGIC = 0x4843;
    public static final int VERSION = 2;
    public static final int TYPE_INITIAL = 1;
    public static final int TYPE_RETRY = 2;
    public static final int TYPE_HANDSHAKE = 3;
    public static final int TYPE_REJECT = 4;
    public static final int HEADER_LEN = 12;
    public static final int MAX_COOKIE_LEN = 64;

    public record Decoded(int type, int connectionId, byte[] cookie, byte[] payload) {
    }

    private HandshakePacket() {
    }

    public static boolean isHandshake(byte[] datagram, int offset, int length) {
        return length >= HEADER_LEN && ((datagram[offset] & 0xFF) << 8 | (datagram[offset + 1] & 0xFF)) == MAGIC
                && (datagram[offset + 2] & 0xFF) == VERSION;
    }

    public static int peekConnectionId(byte[] datagram, int offset, int length) {
        if (length < HEADER_LEN) {
            throw new IllegalArgumentException("truncated v2 handshake packet");
        }
        return ((datagram[offset + 4] & 0xFF) << 24)
                | ((datagram[offset + 5] & 0xFF) << 16)
                | ((datagram[offset + 6] & 0xFF) << 8)
                | (datagram[offset + 7] & 0xFF);
    }

    public static byte[] initial(int connectionId, byte[] cookie, byte[] noiseMessage1) {
        return encode(TYPE_INITIAL, connectionId, cookie, noiseMessage1);
    }

    public static byte[] retry(int connectionId, byte[] cookie) {
        return encode(TYPE_RETRY, connectionId, cookie, new byte[0]);
    }

    public static byte[] handshake(int connectionId, byte[] noiseMessage) {
        return encode(TYPE_HANDSHAKE, connectionId, new byte[0], noiseMessage);
    }

    public static byte[] reject(int connectionId, byte reason) {
        return encode(TYPE_REJECT, connectionId, new byte[0], new byte[]{reason});
    }

    public static Decoded decode(byte[] datagram, int offset, int length) throws ProtocolException {
        if (!isHandshake(datagram, offset, length)) {
            throw new ProtocolException("not a HyperConduit v2 handshake packet");
        }
        try {
            InBuffer in = new InBuffer(datagram, offset, length);
            int magic = in.readShort();
            int version = in.readByte();
            int type = in.readByte();
            int connectionId = in.readInt();
            int cookieLength = in.readShort();
            int payloadLength = in.readShort();
            if (magic != MAGIC || version != VERSION || type < TYPE_INITIAL || type > TYPE_REJECT
                    || cookieLength > MAX_COOKIE_LEN || payloadLength != in.remaining() - cookieLength) {
                throw new ProtocolException("invalid v2 handshake packet");
            }
            byte[] cookie = in.readBytes(cookieLength);
            byte[] payload = in.readBytes(payloadLength);
            return new Decoded(type, connectionId, cookie, payload);
        } catch (InBuffer.BufferUnderflow e) {
            throw new ProtocolException("truncated v2 handshake packet", e);
        }
    }

    private static byte[] encode(int type, int connectionId, byte[] cookie, byte[] payload) {
        byte[] actualCookie = cookie == null ? new byte[0] : cookie;
        byte[] actualPayload = payload == null ? new byte[0] : payload;
        if (actualCookie.length > MAX_COOKIE_LEN || actualPayload.length > 0xFFFF) {
            throw new IllegalArgumentException("oversized v2 handshake packet");
        }
        OutBuffer out = new OutBuffer(HEADER_LEN + actualCookie.length + actualPayload.length);
        out.writeShort(MAGIC);
        out.writeByte(VERSION);
        out.writeByte(type);
        out.writeInt(connectionId);
        out.writeShort(actualCookie.length);
        out.writeShort(actualPayload.length);
        out.writeBytes(actualCookie);
        out.writeBytes(actualPayload);
        return out.toByteArray();
    }
}
