package io.hyperconduit.frame;

import io.hyperconduit.ProtocolException;
import io.hyperconduit.util.InBuffer;
import io.hyperconduit.util.OutBuffer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes and decodes the frame list inside one packet's plaintext.
 *
 * <p>Decoding treats its input as hostile. Even though frames only arrive inside authenticated
 * ciphertext, every length read from the wire is checked against what remains before it drives an
 * allocation, so a corrupt or deliberately crafted packet cannot cause an oversized allocation or
 * an unbounded loop.
 */
public final class FrameCodec {

    private static final int MAX_FRAMES_PER_PACKET = 1024;
    private static final int MAX_ACK_RANGES = 512;
    private static final int MAX_CLOSE_MESSAGE_BYTES = 1024;

    private FrameCodec() {
    }

    public static void encode(List<Frame> frames, OutBuffer out) {
        for (Frame frame : frames) {
            encodeFrame(frame, out);
        }
    }

    public static List<Frame> decode(byte[] data, int off, int len) throws ProtocolException {
        InBuffer in = new InBuffer(data, off, len);
        List<Frame> frames = new ArrayList<>();
        try {
            while (in.hasRemaining()) {
                if (frames.size() >= MAX_FRAMES_PER_PACKET) {
                    throw new ProtocolException("more than " + MAX_FRAMES_PER_PACKET + " frames in one packet");
                }
                frames.add(decodeFrame(in));
            }
        } catch (InBuffer.BufferUnderflow e) {
            throw new ProtocolException("truncated frame", e);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("malformed frame: " + e.getMessage(), e);
        }
        return frames;
    }

    private static void encodeFrame(Frame frame, OutBuffer out) {
        switch (frame) {
            case Frame.StreamData data -> {
                out.writeByte(FrameType.STREAM_DATA.code());
                out.writeVarInt(data.offset());
                out.writeVarInt(data.data().length);
                out.writeBytes(data.data());
            }
            case Frame.Ack ack -> {
                List<Frame.AckRange> ranges = ack.ranges();
                if (ranges.isEmpty()) {
                    throw new IllegalArgumentException("ACK frame must carry at least one range");
                }
                out.writeByte(FrameType.ACK.code());
                out.writeVarInt(ack.largestAcked());
                out.writeVarInt(ack.ackDelayMicros());
                out.writeVarInt(ranges.size() - 1);
                Frame.AckRange first = ranges.get(0);
                out.writeVarInt(first.largest() - first.smallest());
                for (int i = 1; i < ranges.size(); i++) {
                    Frame.AckRange previous = ranges.get(i - 1);
                    Frame.AckRange current = ranges.get(i);
                    out.writeVarInt(previous.smallest() - current.largest() - 2);
                    out.writeVarInt(current.largest() - current.smallest());
                }
            }
            case Frame.Ping ignored -> out.writeByte(FrameType.PING.code());
            case Frame.MaxData maxData -> {
                out.writeByte(FrameType.MAX_DATA.code());
                out.writeVarInt(maxData.maxOffset());
            }
            case Frame.Close close -> {
                byte[] message = close.message() == null
                        ? new byte[0]
                        : close.message().getBytes(StandardCharsets.UTF_8);
                out.writeByte(FrameType.CLOSE.code());
                out.writeVarInt(Math.max(0, close.reasonCode()));
                out.writeVarInt(message.length);
                out.writeBytes(message);
            }
        }
    }

    private static Frame decodeFrame(InBuffer in) throws ProtocolException {
        int code = in.readByte();
        FrameType type = FrameType.fromCode(code);
        if (type == null) {
            throw new ProtocolException("unknown frame type 0x" + Integer.toHexString(code));
        }
        return switch (type) {
            case STREAM_DATA -> {
                long offset = in.readVarInt();
                long length = in.readVarInt();
                if (length > in.remaining()) {
                    throw new ProtocolException("STREAM_DATA claims " + length
                            + " bytes but only " + in.remaining() + " remain");
                }
                yield new Frame.StreamData(offset, in.readBytes((int) length));
            }
            case ACK -> decodeAck(in);
            case PING -> new Frame.Ping();
            case MAX_DATA -> new Frame.MaxData(in.readVarInt());
            case CLOSE -> {
                long reasonCode = in.readVarInt();
                long messageLength = in.readVarInt();
                if (messageLength > MAX_CLOSE_MESSAGE_BYTES) {
                    throw new ProtocolException("CLOSE message too long: " + messageLength);
                }
                if (messageLength > in.remaining()) {
                    throw new ProtocolException("CLOSE message overruns the packet");
                }
                yield new Frame.Close((int) reasonCode,
                        new String(in.readBytes((int) messageLength), StandardCharsets.UTF_8));
            }
        };
    }

    private static Frame.Ack decodeAck(InBuffer in) throws ProtocolException {
        long largestAcked = in.readVarInt();
        long ackDelayMicros = in.readVarInt();
        long additionalRangeCount = in.readVarInt();
        if (additionalRangeCount > MAX_ACK_RANGES) {
            throw new ProtocolException("ACK claims " + additionalRangeCount + " additional ranges");
        }
        long firstRangeLength = in.readVarInt();
        if (firstRangeLength > largestAcked) {
            throw new ProtocolException("first ACK range underflows packet number zero");
        }
        List<Frame.AckRange> ranges = new ArrayList<>((int) additionalRangeCount + 1);
        long smallest = largestAcked - firstRangeLength;
        ranges.add(new Frame.AckRange(smallest, largestAcked));

        long previousSmallest = smallest;
        for (long i = 0; i < additionalRangeCount; i++) {
            long gap = in.readVarInt();
            long length = in.readVarInt();
            // RFC 9000: the gap counts unacknowledged packets strictly between the two ranges.
            long currentLargest = previousSmallest - gap - 2;
            long currentSmallest = currentLargest - length;
            if (currentLargest < 0 || currentSmallest < 0) {
                throw new ProtocolException("ACK range " + i + " underflows packet number zero");
            }
            ranges.add(new Frame.AckRange(currentSmallest, currentLargest));
            previousSmallest = currentSmallest;
        }
        return new Frame.Ack(ackDelayMicros, ranges);
    }
}
