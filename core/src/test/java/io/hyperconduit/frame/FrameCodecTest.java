package io.hyperconduit.frame;

import io.hyperconduit.ProtocolException;
import io.hyperconduit.util.OutBuffer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameCodecTest {

    private static List<Frame> roundTrip(Frame... frames) throws ProtocolException {
        OutBuffer out = new OutBuffer();
        FrameCodec.encode(List.of(frames), out);
        return FrameCodec.decode(out.toByteArray(), 0, out.size());
    }

    @Test
    void streamDataRoundTrips() throws Exception {
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        List<Frame> decoded = roundTrip(new Frame.StreamData(4242, payload));

        assertEquals(1, decoded.size());
        Frame.StreamData data = (Frame.StreamData) decoded.get(0);
        assertEquals(4242, data.offset());
        assertArrayEquals(payload, data.data());
    }

    @Test
    void emptyStreamDataRoundTrips() throws Exception {
        Frame.StreamData data = (Frame.StreamData) roundTrip(new Frame.StreamData(0, new byte[0])).get(0);
        assertEquals(0, data.data().length);
    }

    @Test
    void ackWithMultipleRangesRoundTrips() throws Exception {
        // Deliberately unsorted: the Ack constructor must normalise the order.
        Frame.Ack ack = new Frame.Ack(777, List.of(
                new Frame.AckRange(0, 1),
                new Frame.AckRange(8, 10),
                new Frame.AckRange(4, 5)));

        assertEquals(List.of(
                new Frame.AckRange(8, 10),
                new Frame.AckRange(4, 5),
                new Frame.AckRange(0, 1)), ack.ranges());
        assertEquals(10, ack.largestAcked());

        Frame.Ack decoded = (Frame.Ack) roundTrip(ack).get(0);
        assertEquals(777, decoded.ackDelayMicros());
        assertEquals(10, decoded.largestAcked());
        assertEquals(ack.ranges(), decoded.ranges());
    }

    @Test
    void singleRangeAckRoundTrips() throws Exception {
        Frame.Ack decoded = (Frame.Ack) roundTrip(
                new Frame.Ack(0, List.of(new Frame.AckRange(5, 5)))).get(0);
        assertEquals(List.of(new Frame.AckRange(5, 5)), decoded.ranges());
    }

    @Test
    void controlFramesRoundTrip() throws Exception {
        List<Frame> decoded = roundTrip(new Frame.Ping(), new Frame.MaxData(1L << 40),
                new Frame.Close(3, "shutdown"));

        assertEquals(3, decoded.size());
        assertTrue(decoded.get(0) instanceof Frame.Ping);
        assertEquals(1L << 40, ((Frame.MaxData) decoded.get(1)).maxOffset());
        Frame.Close close = (Frame.Close) decoded.get(2);
        assertEquals(3, close.reasonCode());
        assertEquals("shutdown", close.message());
    }

    @Test
    void severalFramesShareOnePacket() throws Exception {
        List<Frame> decoded = roundTrip(
                new Frame.Ack(10, List.of(new Frame.AckRange(0, 3))),
                new Frame.StreamData(0, new byte[]{9, 9}),
                new Frame.Ping());

        assertEquals(3, decoded.size());
        assertTrue(decoded.get(0) instanceof Frame.Ack);
        assertTrue(decoded.get(1) instanceof Frame.StreamData);
        assertTrue(decoded.get(2) instanceof Frame.Ping);
    }

    @Test
    void unknownFrameTypeIsRejected() {
        OutBuffer out = new OutBuffer();
        out.writeByte(0x7F); // not a FrameType
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(out.toByteArray(), 0, out.size()));
    }

    @Test
    void streamDataClaimingMoreBytesThanRemainIsRejected() {
        OutBuffer out = new OutBuffer();
        out.writeByte(FrameType.STREAM_DATA.code());
        out.writeVarInt(0);
        out.writeVarInt(10_000); // far beyond what follows
        out.writeBytes(new byte[]{1, 2});

        ProtocolException e = assertThrows(ProtocolException.class,
                () -> FrameCodec.decode(out.toByteArray(), 0, out.size()));
        assertTrue(e.getMessage().contains("STREAM_DATA"));
    }

    @Test
    void oversizedCloseMessageIsRejected() {
        OutBuffer out = new OutBuffer();
        out.writeByte(FrameType.CLOSE.code());
        out.writeVarInt(1);
        out.writeVarInt(1_000_000);
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(out.toByteArray(), 0, out.size()));
    }

    @Test
    void truncatedFrameIsRejected() {
        OutBuffer out = new OutBuffer();
        FrameCodec.encode(List.of(new Frame.StreamData(0, new byte[8])), out);
        byte[] truncated = new byte[out.size() - 3];
        System.arraycopy(out.toByteArray(), 0, truncated, 0, truncated.length);
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(truncated, 0, truncated.length));
    }

    @Test
    void malformedAckRangeIsRejected() {
        OutBuffer out = new OutBuffer();
        out.writeByte(FrameType.ACK.code());
        out.writeVarInt(5);   // largestAcked
        out.writeVarInt(0);   // ackDelay
        out.writeVarInt(1);   // one additional range
        out.writeVarInt(0);   // first range length
        out.writeVarInt(100); // gap far larger than any packet number seen -> underflows below zero
        out.writeVarInt(0);
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(out.toByteArray(), 0, out.size()));
    }

    @Test
    void excessiveAckRangeCountIsRejected() {
        OutBuffer out = new OutBuffer();
        out.writeByte(FrameType.ACK.code());
        out.writeVarInt(1000);
        out.writeVarInt(0);
        out.writeVarInt(100_000); // absurd additional-range count
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(out.toByteArray(), 0, out.size()));
    }

    @Test
    void decodeRespectsOffsetAndLengthOfASlice() throws Exception {
        OutBuffer frames = new OutBuffer();
        FrameCodec.encode(List.of(new Frame.Ping()), frames);

        byte[] padded = new byte[frames.size() + 6];
        frames.writeTo(padded, 3);

        // Reading the slice must ignore the padding on both sides.
        List<Frame> decoded = FrameCodec.decode(padded, 3, frames.size());
        assertEquals(1, decoded.size());
        assertTrue(decoded.get(0) instanceof Frame.Ping);

        // A reader given the whole buffer would hit the padding as an unknown frame type.
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(padded, 0, padded.length));
    }

    @Test
    void emptyBufferDecodesToNoFrames() throws Exception {
        assertEquals(0, FrameCodec.decode(new byte[0], 0, 0).size());
    }

    @Test
    void ackWithNoRangesIsRejectedOnEncode() {
        OutBuffer out = new OutBuffer();
        assertThrows(IllegalArgumentException.class,
                () -> FrameCodec.encode(List.of(new Frame.Ack(0, List.of())), out));
    }

    @Test
    void invalidAckRangeIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new Frame.AckRange(10, 5));
        assertThrows(IllegalArgumentException.class, () -> new Frame.AckRange(-1, 5));
    }
}
