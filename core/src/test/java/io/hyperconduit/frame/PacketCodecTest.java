package io.hyperconduit.frame;

import io.hyperconduit.ProtocolException;
import io.hyperconduit.crypto.Hkdf;
import io.hyperconduit.crypto.NoiseXx;
import io.hyperconduit.crypto.TransportKeys;
import io.hyperconduit.crypto.X25519;
import io.hyperconduit.util.OutBuffer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketCodecTest {

    private static final byte[] PSK = "packet-codec-test-psk".getBytes(StandardCharsets.UTF_8);

    /** Stand-in for the PSK-derived handshake mask key and the Split-derived transport mask key. */
    private static byte[] maskKey(byte[] material) {
        return Hkdf.derive(new byte[32], material, 1)[0];
    }

    private static byte[] transportKey(byte seed) {
        byte[] key = new byte[32];
        Arrays.fill(key, seed);
        return key;
    }

    private static byte[] frames() {
        OutBuffer out = new OutBuffer();
        FrameCodec.encode(List.of(
                new Frame.StreamData(7, "minecraft-bytes".getBytes(StandardCharsets.UTF_8)),
                new Frame.Ping()), out);
        return out.toByteArray();
    }

    @Test
    void transportPacketRoundTrips() throws Exception {
        byte[] key = transportKey((byte) 1);
        byte[] mask = maskKey(PSK);
        PacketCodec sender = PacketCodec.forTransport(key, mask);
        PacketCodec receiver = PacketCodec.forTransport(key, mask);

        byte[] plaintext = frames();
        byte[] wire = sender.encode(PacketCodec.FLAG_DATA, 0x12345678, 987654321L, plaintext, 0, plaintext.length);

        assertEquals(PacketCodec.HEADER_LEN + plaintext.length + 16, wire.length);
        PacketCodec.Decoded decoded = receiver.decode(wire, 0, wire.length);

        assertEquals(PacketCodec.FLAG_DATA, decoded.flags());
        assertEquals(0x12345678, decoded.connectionId());
        assertEquals(987654321L, decoded.packetNumber());
        assertArrayEquals(plaintext, decoded.plaintext());
    }

    @Test
    void flagsAndPacketNumberAreMaskedButTheConnectionIdIsNot() {
        PacketCodec codec = PacketCodec.forTransport(transportKey((byte) 2), maskKey(PSK));
        byte[] plaintext = frames();
        byte[] wire = codec.encode(PacketCodec.FLAG_DATA, 0x01020304, 0x05060708L,
                plaintext, 0, plaintext.length);

        // The id is intentionally clear: the server must demultiplex before it knows any session key.
        assertEquals(0x01020304, PacketCodec.peekConnectionId(wire, 0, wire.length));

        byte[] masked = Arrays.copyOfRange(wire, PacketCodec.MASKED_OFFSET, PacketCodec.HEADER_LEN);
        assertFalse(Arrays.equals(
                        new byte[]{PacketCodec.FLAG_DATA, 0x05, 0x06, 0x07, 0x08}, masked),
                "flags and packet number must not appear in the clear");
    }

    @Test
    void consecutivePacketNumbersProduceUncorrelatedHeaders() {
        PacketCodec codec = PacketCodec.forTransport(transportKey((byte) 3), maskKey(PSK));
        byte[] plaintext = frames();
        byte[] first = codec.encode(PacketCodec.FLAG_DATA, 42, 1L, plaintext, 0, plaintext.length);
        byte[] second = codec.encode(PacketCodec.FLAG_DATA, 42, 2L, plaintext, 0, plaintext.length);

        // A bare incrementing counter is a trivially recognisable signature; masking must hide it.
        assertFalse(Arrays.equals(
                Arrays.copyOfRange(first, PacketCodec.MASKED_OFFSET, PacketCodec.HEADER_LEN),
                Arrays.copyOfRange(second, PacketCodec.MASKED_OFFSET, PacketCodec.HEADER_LEN)));
    }

    @Test
    void peekConnectionIdRejectsShortDatagrams() {
        assertThrows(IllegalArgumentException.class,
                () -> PacketCodec.peekConnectionId(new byte[PacketCodec.HEADER_LEN - 1], 0,
                        PacketCodec.HEADER_LEN - 1));
    }

    @Test
    void handshakeCodecCarriesPlaintextBody() throws Exception {
        byte[] mask = maskKey(PSK);
        PacketCodec sender = PacketCodec.forHandshake(mask);
        PacketCodec receiver = PacketCodec.forHandshake(mask);
        assertFalse(sender.encryptsBody());

        byte[] noiseMessage = new byte[32 + 48 + 16];
        new Random(11).nextBytes(noiseMessage);

        byte[] wire = sender.encode(PacketCodec.FLAG_HANDSHAKE, 0xAABBCCDD, 0,
                noiseMessage, 0, noiseMessage.length);
        assertEquals(PacketCodec.HEADER_LEN + noiseMessage.length, wire.length);

        PacketCodec.Decoded decoded = receiver.decode(wire, 0, wire.length);
        assertEquals(PacketCodec.FLAG_HANDSHAKE, decoded.flags());
        assertEquals(0xAABBCCDD, decoded.connectionId());
        assertEquals(0, decoded.packetNumber());
        assertArrayEquals(noiseMessage, decoded.plaintext());
        // The body is plaintext but the flags and packet number must still be masked.
        assertEquals(0xAABBCCDD, PacketCodec.peekConnectionId(wire, 0, wire.length));
        assertFalse(Arrays.equals(new byte[]{PacketCodec.FLAG_HANDSHAKE, 0, 0, 0, 0},
                Arrays.copyOfRange(wire, PacketCodec.MASKED_OFFSET, PacketCodec.HEADER_LEN)));
    }

    @Test
    void tamperedBodyFailsAuthentication() {
        PacketCodec sender = PacketCodec.forTransport(transportKey((byte) 4), maskKey(PSK));
        PacketCodec receiver = PacketCodec.forTransport(transportKey((byte) 4), maskKey(PSK));
        byte[] plaintext = frames();
        byte[] wire = sender.encode(PacketCodec.FLAG_DATA, 1, 1, plaintext, 0, plaintext.length);

        wire[wire.length - 1] ^= 0x01;
        assertThrows(ProtocolException.class, () -> receiver.decode(wire, 0, wire.length));
    }

    @Test
    void tamperedHeaderFailsAuthentication() {
        PacketCodec sender = PacketCodec.forTransport(transportKey((byte) 5), maskKey(PSK));
        PacketCodec receiver = PacketCodec.forTransport(transportKey((byte) 5), maskKey(PSK));
        byte[] plaintext = frames();
        byte[] wire = sender.encode(PacketCodec.FLAG_DATA, 1, 1, plaintext, 0, plaintext.length);

        // The header is AAD, so flipping a masked header byte must break the tag check.
        wire[0] ^= 0x01;
        assertThrows(ProtocolException.class, () -> receiver.decode(wire, 0, wire.length));
    }

    @Test
    void wrongKeyCannotDecode() {
        PacketCodec sender = PacketCodec.forTransport(transportKey((byte) 6), maskKey(PSK));
        PacketCodec receiver = PacketCodec.forTransport(transportKey((byte) 7), maskKey(PSK));
        byte[] plaintext = frames();
        byte[] wire = sender.encode(PacketCodec.FLAG_DATA, 1, 1, plaintext, 0, plaintext.length);
        assertThrows(ProtocolException.class, () -> receiver.decode(wire, 0, wire.length));
    }

    @Test
    void wrongMaskKeyCannotDecode() {
        PacketCodec sender = PacketCodec.forTransport(transportKey((byte) 8), maskKey(PSK));
        PacketCodec receiver = PacketCodec.forTransport(transportKey((byte) 8), maskKey("other-psk".getBytes()));
        byte[] plaintext = frames();
        byte[] wire = sender.encode(PacketCodec.FLAG_DATA, 1, 1, plaintext, 0, plaintext.length);
        assertThrows(ProtocolException.class, () -> receiver.decode(wire, 0, wire.length));
    }

    @Test
    void shortDatagramsAreRejected() {
        PacketCodec codec = PacketCodec.forTransport(transportKey((byte) 9), maskKey(PSK));
        assertThrows(ProtocolException.class, () -> codec.decode(new byte[PacketCodec.HEADER_LEN], 0,
                PacketCodec.HEADER_LEN));
        assertThrows(ProtocolException.class, () -> codec.decode(new byte[0], 0, 0));
    }

    @Test
    void decodeHonoursOffsetWithinALargerBuffer() throws Exception {
        PacketCodec sender = PacketCodec.forTransport(transportKey((byte) 10), maskKey(PSK));
        PacketCodec receiver = PacketCodec.forTransport(transportKey((byte) 10), maskKey(PSK));
        byte[] plaintext = frames();
        byte[] wire = sender.encode(PacketCodec.FLAG_DATA, 0x0F0F0F0F, 12345L, plaintext, 0, plaintext.length);

        // Simulates reading into a reusable UDP buffer that carries leading slack.
        byte[] padded = new byte[wire.length + 500];
        System.arraycopy(wire, 0, padded, 500, wire.length);

        PacketCodec.Decoded decoded = receiver.decode(padded, 500, wire.length);
        assertEquals(0x0F0F0F0F, decoded.connectionId());
        assertEquals(12345L, decoded.packetNumber());
        assertArrayEquals(plaintext, decoded.plaintext());
    }

    @Test
    void encodeReadsOnlyTheGivenSliceOfPlaintext() throws Exception {
        PacketCodec sender = PacketCodec.forTransport(transportKey((byte) 11), maskKey(PSK));
        PacketCodec receiver = PacketCodec.forTransport(transportKey((byte) 11), maskKey(PSK));

        byte[] plaintext = frames();
        byte[] padded = new byte[plaintext.length + 40];
        System.arraycopy(plaintext, 0, padded, 20, plaintext.length);

        byte[] wire = sender.encode(PacketCodec.FLAG_DATA, 1, 1, padded, 20, plaintext.length);
        assertArrayEquals(plaintext, receiver.decode(wire, 0, wire.length).plaintext());
    }

    @Test
    void fullPacketNumberRangeRoundTrips() throws Exception {
        PacketCodec sender = PacketCodec.forTransport(transportKey((byte) 12), maskKey(PSK));
        PacketCodec receiver = PacketCodec.forTransport(transportKey((byte) 12), maskKey(PSK));
        byte[] plaintext = new byte[]{1};

        long[] packetNumbers = {0, 1, 255, 65535, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL};
        for (long pn : packetNumbers) {
            byte[] wire = sender.encode(PacketCodec.FLAG_DATA, 1, pn, plaintext, 0, 1);
            assertEquals(pn, receiver.decode(wire, 0, wire.length).packetNumber(),
                    "packet number " + pn + " must survive the unsigned 32-bit round trip");
        }
    }

    @Test
    void rejectPacketCarriesItsReasonInPlaintext() throws Exception {
        PacketCodec sender = PacketCodec.forHandshake(maskKey(PSK));
        PacketCodec receiver = PacketCodec.forHandshake(maskKey(PSK));

        byte[] wire = sender.encode(PacketCodec.FLAG_REJECT, 1, 0, new byte[]{2}, 0, 1);
        PacketCodec.Decoded decoded = receiver.decode(wire, 0, wire.length);
        assertEquals(PacketCodec.FLAG_REJECT, decoded.flags());
        assertArrayEquals(new byte[]{2}, decoded.plaintext());
    }

    @Test
    void transportCodecRejectsAHandshakeDatagram() {
        byte[] mask = maskKey(PSK);
        PacketCodec handshake = PacketCodec.forHandshake(mask);
        PacketCodec transport = PacketCodec.forTransport(transportKey((byte) 13), mask);
        assertFalse(handshake.encryptsBody());
        assertTrue(transport.encryptsBody());

        byte[] noiseMessage = new byte[96];
        byte[] wire = handshake.encode(PacketCodec.FLAG_HANDSHAKE, 1, 0, noiseMessage, 0, noiseMessage.length);
        // Same mask key, but the transport codec expects an AEAD body and must not accept it.
        assertThrows(ProtocolException.class, () -> transport.decode(wire, 0, wire.length));
    }

    @Test
    void noiseTransportKeysDriveAWorkingCodecPair() throws Exception {
        // End-to-end check that the keys Noise produces actually plug into the packet codec.
        NoiseXx initiator = NoiseXx.initiator(X25519.generate(), PSK);
        NoiseXx responder = NoiseXx.responder(X25519.generate(), PSK);

        responder.readMessage(initiator.writeMessage(new byte[0]));
        initiator.readMessage(responder.writeMessage(new byte[0]));
        responder.readMessage(initiator.writeMessage(new byte[0]));

        TransportKeys clientKeys = initiator.complete();
        TransportKeys serverKeys = responder.complete();
        PacketCodec send = PacketCodec.forTransport(clientKeys.sendKey(), clientKeys.hpKey());
        PacketCodec recv = PacketCodec.forTransport(serverKeys.recvKey(), serverKeys.hpKey());

        byte[] plaintext = frames();
        byte[] wire = send.encode(PacketCodec.FLAG_DATA, 0xC0FFEE, 1, plaintext, 0, plaintext.length);
        assertArrayEquals(plaintext, recv.decode(wire, 0, wire.length).plaintext());
    }
}
