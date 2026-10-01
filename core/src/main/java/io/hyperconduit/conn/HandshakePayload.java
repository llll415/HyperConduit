package io.hyperconduit.conn;

import io.hyperconduit.ProtocolException;
import io.hyperconduit.util.InBuffer;
import io.hyperconduit.util.OutBuffer;

/** Authenticated Noise payloads for the v2 handshake. */
public final class HandshakePayload {

    private static final int VERSION = 2;

    private HandshakePayload() {
    }

    public record Negotiation(int receiveWindowBytes, int maxDatagramSize) {
        void validate() throws ProtocolException {
            if (receiveWindowBytes <= 0 || receiveWindowBytes > (1 << 28)) {
                throw new ProtocolException("absurd receive window: " + receiveWindowBytes);
            }
            if (maxDatagramSize < 64 || maxDatagramSize > 65535) {
                throw new ProtocolException("absurd datagram size: " + maxDatagramSize);
            }
        }
    }

    public static byte[] encodeClientPayload(Negotiation negotiation) {
        return encode(negotiation);
    }

    public static Negotiation decodeClientPayload(byte[] payload) throws ProtocolException {
        return decode(payload);
    }

    public static byte[] encodeServerPayload(Negotiation negotiation) {
        return encode(negotiation);
    }

    public static Negotiation decodeServerPayload(byte[] payload) throws ProtocolException {
        return decode(payload);
    }

    private static byte[] encode(Negotiation negotiation) {
        OutBuffer out = new OutBuffer(16);
        out.writeVarInt(VERSION);
        out.writeVarInt(negotiation.receiveWindowBytes());
        out.writeVarInt(negotiation.maxDatagramSize());
        return out.toByteArray();
    }

    private static Negotiation decode(byte[] payload) throws ProtocolException {
        try {
            InBuffer in = new InBuffer(payload);
            long version = in.readVarInt();
            if (version != VERSION) {
                throw new ProtocolException("unsupported tunnel version " + version);
            }
            Negotiation negotiation = new Negotiation((int) in.readVarInt(), (int) in.readVarInt());
            negotiation.validate();
            if (in.hasRemaining()) {
                throw new ProtocolException("trailing handshake payload bytes");
            }
            return negotiation;
        } catch (InBuffer.BufferUnderflow e) {
            throw new ProtocolException("truncated handshake payload", e);
        }
    }
}
