package io.hyperconduit.sidecar;

import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the private bridge preface gate with {@link EmbeddedChannel}.
 *
 * <p>Netty semantics that matter here: {@link EmbeddedChannel} registers and activates itself in
 * its constructor ({@code doRegister()} flips the state to ACTIVE and {@code register0} then fires
 * {@code channelActive}). The gate deliberately swallows that first {@code channelActive} and only
 * replays it after the preface is trusted, so the Minecraft initializer must observe exactly one
 * {@code channelActive}, after its attributes are already installed. No production code is changed
 * to accommodate these tests.</p>
 */
class BridgeInitializerTest {

    private static final String TOKEN = "0123456789abcdef".repeat(4);
    private static final byte[] MAGIC = "HCMETA01".getBytes(StandardCharsets.US_ASCII);

    @Test void rejectsAmbiguousOrNonLiteralMetadata() {
        for (String extra : new String[]{",\"streamId\":2",",\"unknown\":\"x\""}) {
            RecordingMinecraftInitializer recorder = new RecordingMinecraftInitializer();
            EmbeddedChannel channel = new EmbeddedChannel(new BridgeInitializer(TOKEN,recorder));
            String body="{\"token\":\""+TOKEN+"\",\"peer\":\"203.0.113.1:1\",\"connectionId\":1,\"streamId\":0"+extra+"}";
            byte[] json=body.getBytes(StandardCharsets.UTF_8);
            ByteBuf frame=Unpooled.buffer().writeBytes(MAGIC).writeInt(json.length).writeBytes(json);
            channel.writeOneInbound(frame);
            assertFalse(channel.isOpen());
            assertFalse(recorder.initialized());
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void splitPrefaceAndCoalescedPayloadDeliverOnlyMinecraftBytes() {
        RecordingMinecraftInitializer recorder = new RecordingMinecraftInitializer();
        EmbeddedChannel channel = new EmbeddedChannel(new BridgeInitializer(TOKEN, recorder));
        byte[] preface = preface(TOKEN, "203.0.113.9:25565", 42L, 7L);
        byte[] minecraftBytes = new byte[] {
                0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x7f
        };
        int jsonMidpoint = 12 + (preface.length - 12) / 2;

        try {
            // Split the private preface across several reads. None of these bytes may reach the
            // downstream Minecraft pipeline, even though the JSON contains the credential.
            channel.writeInbound(slice(preface, 0, 3));
            channel.writeInbound(slice(preface, 3, 8));
            channel.writeInbound(slice(preface, 8, 12));
            channel.writeInbound(slice(preface, 12, jsonMidpoint));

            assertNull(channel.readInbound());
            assertFalse(recorder.initialized());
            assertEquals(0, recorder.activeCount());
            assertTrue(channel.isOpen());

            // Finish the preface and coalesce Minecraft bytes into the same read. Only the payload
            // after the validated preface may be forwarded.
            byte[] tail = concat(sliceBytes(preface, jsonMidpoint, preface.length), minecraftBytes);
            channel.writeInbound(Unpooled.wrappedBuffer(tail));

            assertTrue(recorder.initialized());
            assertEquals(1, recorder.activeCount());
            assertArrayEquals(minecraftBytes, recorder.payload());
            assertFalse(new String(recorder.payload(), StandardCharsets.ISO_8859_1).contains(TOKEN));
            assertNull(channel.readInbound());

            assertEquals(new InetSocketAddress("203.0.113.9", 25565),
                    channel.attr(TunnelAttributes.PEER).get());
            assertEquals(Boolean.TRUE, channel.attr(TunnelAttributes.TUNNEL).get());
            assertEquals(42L, channel.attr(TunnelAttributes.CONNECTION_ID).get().longValue());
            assertEquals(7L, channel.attr(TunnelAttributes.STREAM_ID).get().longValue());

            // Accepting the preface must cancel the gate timeout.
            channel.advanceTimeBy(6, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            assertTrue(channel.isOpen());
        } finally {
            channel.close();
        }
    }

    @Test
    void deliversChannelActiveExactlyOnceAfterTrust() {
        RecordingMinecraftInitializer recorder = new RecordingMinecraftInitializer();
        EmbeddedChannel channel = new EmbeddedChannel(new BridgeInitializer(TOKEN, recorder));

        try {
            // EmbeddedChannel already fired channelActive during construction; the gate swallowed it.
            assertEquals(0, recorder.activeCount());
            assertFalse(recorder.initialized());

            channel.writeInbound(Unpooled.wrappedBuffer(
                    preface(TOKEN, "198.51.100.7:25565", 11L, 0L)));

            assertTrue(recorder.initialized());
            // Attributes must already exist when the Minecraft initializer runs, before activation.
            assertEquals(0, recorder.activeCountAtInit());
            assertNotNull(recorder.peerAtInit());
            assertNotNull(recorder.connectionIdAtInit());
            assertNotNull(recorder.streamIdAtInit());
            assertEquals(new InetSocketAddress("198.51.100.7", 25565), recorder.peerAtInit());
            assertEquals(Boolean.TRUE, recorder.tunnelAtInit());
            assertEquals(11L, recorder.connectionIdAtInit().longValue());
            assertEquals(0L, recorder.streamIdAtInit().longValue());

            assertEquals(1, recorder.activeCount());

            // Further traffic must not replay channelActive.
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {1, 2, 3}));
            assertEquals(1, recorder.activeCount());
            assertArrayEquals(new byte[] {1, 2, 3}, recorder.payload());
        } finally {
            channel.close();
        }
    }

    @Test
    void closesPlainTcpBytesWithoutMagic() {
        RecordingMinecraftInitializer recorder = new RecordingMinecraftInitializer();
        EmbeddedChannel channel = new EmbeddedChannel(new BridgeInitializer(TOKEN, recorder));
        byte[] minecraftHandshake = new byte[] {
                0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x01
        };

        try {
            ChannelFuture write = channel.writeOneInbound(Unpooled.wrappedBuffer(minecraftHandshake));

            assertTrue(write.isSuccess());
            assertFalse(channel.isOpen());
            assertNull(channel.readInbound());
            assertFalse(recorder.initialized());
            assertEquals(0, recorder.activeCount());
        } finally {
            channel.close();
        }
    }

    @Test
    void closesOnWrongTokenWithoutLeakingTrailingBytes() {
        RecordingMinecraftInitializer recorder = new RecordingMinecraftInitializer();
        EmbeddedChannel channel = new EmbeddedChannel(new BridgeInitializer(TOKEN, recorder));
        byte[] wrongTokenPreface = preface("f".repeat(64), "203.0.113.9:25565", 1L, 0L);
        byte[] withTrailingPayload = concat(wrongTokenPreface, new byte[] {0x10, 0x00, 0x01});

        try {
            ChannelFuture write = channel.writeOneInbound(Unpooled.wrappedBuffer(withTrailingPayload));

            assertTrue(write.isSuccess());
            assertFalse(channel.isOpen());
            assertNull(channel.readInbound());
            assertFalse(recorder.initialized());
            assertEquals(0, recorder.activeCount());
        } finally {
            channel.close();
        }
    }

    @Test
    void closesWhenJsonLengthExceedsLimit() {
        RecordingMinecraftInitializer recorder = new RecordingMinecraftInitializer();
        EmbeddedChannel channel = new EmbeddedChannel(new BridgeInitializer(TOKEN, recorder));
        ByteBuf preface = Unpooled.buffer(12);
        preface.writeBytes(MAGIC);
        preface.writeInt(1025);

        try {
            ChannelFuture write = channel.writeOneInbound(preface);

            assertTrue(write.isSuccess());
            assertFalse(channel.isOpen());
            assertNull(channel.readInbound());
            assertFalse(recorder.initialized());
        } finally {
            channel.close();
        }
    }

    @Test
    void closesOnMalformedPeer() {
        // Missing port separator.
        assertRejected(preface(TOKEN, "127.0.0.1", 1L, 0L));
        // Host is not a literal IP address.
        assertRejected(preface(TOKEN, "not-an-ip:25565", 1L, 0L));
    }

    @Test
    void closesOnNonPositiveConnectionId() {
        assertRejected(preface(TOKEN, "203.0.113.9:25565", 0L, 0L));
        assertRejected(preface(TOKEN, "203.0.113.9:25565", -1L, 0L));
    }

    @Test
    void closesOnNegativeStreamId() {
        assertRejected(preface(TOKEN, "203.0.113.9:25565", 1L, -1L));
    }

    @Test
    void acceptsIpv6Peer() throws Exception {
        RecordingMinecraftInitializer recorder = new RecordingMinecraftInitializer();
        EmbeddedChannel channel = new EmbeddedChannel(new BridgeInitializer(TOKEN, recorder));

        try {
            channel.writeInbound(Unpooled.wrappedBuffer(
                    preface(TOKEN, "[2001:db8::1]:25565", 5L, 2L)));

            assertTrue(channel.isOpen());
            assertEquals(1, recorder.activeCount());

            InetSocketAddress expected = new InetSocketAddress(
                    InetAddress.getByName("2001:db8::1"), 25565);
            assertEquals(expected, recorder.peerAtInit());
            assertEquals(expected, channel.attr(TunnelAttributes.PEER).get());
            assertEquals(Boolean.TRUE, recorder.tunnelAtInit());
            assertEquals(5L, recorder.connectionIdAtInit().longValue());
            assertEquals(2L, recorder.streamIdAtInit().longValue());
        } finally {
            channel.close();
        }
    }

    @Test
    void closesGateWhenPrefaceDoesNotArriveInTime() {
        RecordingMinecraftInitializer recorder = new RecordingMinecraftInitializer();
        EmbeddedChannel channel = new EmbeddedChannel(new BridgeInitializer(TOKEN, recorder));

        try {
            assertTrue(channel.isOpen());
            assertEquals(0, recorder.activeCount());

            channel.advanceTimeBy(6, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();

            assertFalse(channel.isOpen());
            assertFalse(recorder.initialized());
            assertEquals(0, recorder.activeCount());
            assertNull(channel.readInbound());
        } finally {
            channel.close();
        }
    }

    private static void assertRejected(byte[] preface) {
        RecordingMinecraftInitializer recorder = new RecordingMinecraftInitializer();
        EmbeddedChannel channel = new EmbeddedChannel(new BridgeInitializer(TOKEN, recorder));
        try {
            ChannelFuture write = channel.writeOneInbound(Unpooled.wrappedBuffer(preface));

            assertTrue(write.isSuccess());
            assertFalse(channel.isOpen());
            assertNull(channel.readInbound());
            assertFalse(recorder.initialized());
            assertEquals(0, recorder.activeCount());
        } finally {
            channel.close();
        }
    }

    private static byte[] preface(String token, String peer, long connectionId, long streamId) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("token", token);
        metadata.addProperty("peer", peer);
        metadata.addProperty("connectionId", connectionId);
        metadata.addProperty("streamId", streamId);
        byte[] json = metadata.toString().getBytes(StandardCharsets.UTF_8);

        byte[] preface = new byte[12 + json.length];
        System.arraycopy(MAGIC, 0, preface, 0, MAGIC.length);
        preface[8] = (byte) (json.length >>> 24);
        preface[9] = (byte) (json.length >>> 16);
        preface[10] = (byte) (json.length >>> 8);
        preface[11] = (byte) json.length;
        System.arraycopy(json, 0, preface, 12, json.length);
        return preface;
    }

    private static ByteBuf slice(byte[] bytes, int from, int to) {
        return Unpooled.copiedBuffer(bytes, from, to - from);
    }

    private static byte[] sliceBytes(byte[] bytes, int from, int to) {
        byte[] copy = new byte[to - from];
        System.arraycopy(bytes, from, copy, 0, copy.length);
        return copy;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    /** Stand-in for Minecraft's initializer; records everything the trusted pipeline observes. */
    private static final class RecordingMinecraftInitializer extends ChannelInitializer<Channel> {
        private final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        private final AtomicInteger activeCount = new AtomicInteger();
        private volatile boolean initialized;
        private volatile int activeCountAtInit = -1;
        private volatile InetSocketAddress peerAtInit;
        private volatile Boolean tunnelAtInit;
        private volatile Long connectionIdAtInit;
        private volatile Long streamIdAtInit;

        @Override
        protected void initChannel(Channel channel) {
            peerAtInit = channel.attr(TunnelAttributes.PEER).get();
            tunnelAtInit = channel.attr(TunnelAttributes.TUNNEL).get();
            connectionIdAtInit = channel.attr(TunnelAttributes.CONNECTION_ID).get();
            streamIdAtInit = channel.attr(TunnelAttributes.STREAM_ID).get();
            activeCountAtInit = activeCount.get();

            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) {
                    activeCount.incrementAndGet();
                    ctx.fireChannelActive();
                }

                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    if (msg instanceof ByteBuf buffer) {
                        byte[] bytes = ByteBufUtil.getBytes(buffer);
                        synchronized (payload) {
                            payload.write(bytes, 0, bytes.length);
                        }
                        ReferenceCountUtil.release(buffer);
                    } else {
                        ReferenceCountUtil.release(msg);
                    }
                }
            });
            initialized = true;
        }

        boolean initialized() {
            return initialized;
        }

        int activeCount() {
            return activeCount.get();
        }

        int activeCountAtInit() {
            return activeCountAtInit;
        }

        InetSocketAddress peerAtInit() {
            return peerAtInit;
        }

        Boolean tunnelAtInit() {
            return tunnelAtInit;
        }

        Long connectionIdAtInit() {
            return connectionIdAtInit;
        }

        Long streamIdAtInit() {
            return streamIdAtInit;
        }

        byte[] payload() {
            synchronized (payload) {
                return payload.toByteArray();
            }
        }
    }
}
