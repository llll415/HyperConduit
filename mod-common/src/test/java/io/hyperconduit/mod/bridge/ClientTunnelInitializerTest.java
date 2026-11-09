package io.hyperconduit.mod.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import io.hyperconduit.sidecar.SidecarException;
import io.hyperconduit.sidecar.SidecarProcess;
import io.hyperconduit.sidecar.TunnelAttributes;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.DatagramSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises {@link ClientTunnelInitializer} against the real bundled Go sidecar.
 *
 * <p>The tests never replace the sidecar with a mock: a standalone server process is started from
 * the same packaged binary that production uses, and a real NIO {@link Bootstrap} connects through
 * a managed client process. The only Java-side stand-in is Minecraft's initializer, which records
 * the bytes it receives so the test can prove that the original handshake is forwarded unchanged.
 */
class ClientTunnelInitializerTest {

    private static final String ORIGINAL_ENDPOINT = "mc.original.example:25565";
    private static final Duration SERVER_STARTUP = Duration.ofSeconds(15);
    private static final Duration SERVER_STOP = Duration.ofSeconds(2);

    /**
     * A hand-written Minecraft-like handshake packet (length, packet id 0, protocol 763,
     * server address, port, next state). It is deliberately not generated from a client helper:
     * the assertion below proves the exact bytes survive the tunnel.
     */
    private static final byte[] MINECRAFT_HANDSHAKE = new byte[] {
            0x1A, 0x00, (byte) 0xFB, 0x05, 0x13,
            'm', 'c', '.', 'o', 'r', 'i', 'g', 'i', 'n', 'a', 'l', '.', 'e', 'x', 'a', 'm', 'p', 'l', 'e',
            0x63, (byte) 0xDD, 0x02
    };

    @TempDir
    Path directory;

    @Test void closingDuringHandshakeCancelsWithoutTrust() throws Exception {
        HyperConduitConfig config = new HyperConduitConfig();
        config.enabled = true;
        config.connectTimeoutMs = 30_000;
        ConfigHolder.install(config, directory);
        try (DatagramSocket silent = new DatagramSocket(new InetSocketAddress("127.0.0.1",0))) {
            EventLoopGroup group = new NioEventLoopGroup(1);
            ChannelFuture future = new Bootstrap().group(group).channel(NioSocketChannel.class)
                    .handler(new ClientTunnelInitializer(new RecordingInitializer(0),config,ORIGINAL_ENDPOINT))
                    .connect(new InetSocketAddress("127.0.0.1",silent.getLocalPort()));
            try {
                future.cancel(true);
                future.channel().close().sync();
                assertFalse(future.channel().isOpen());
                assertFalse(Files.exists(directory.resolve("hyperconduit/known-servers.json")));
            } finally {
                future.channel().close().sync();
                group.shutdownGracefully(0,5,TimeUnit.SECONDS).sync();
            }
        }
    }

    @Test
    void unresolvedTargetFailsBeforeStartingASidecarAndKeepsMinecraftHandlers() throws Exception {
        HyperConduitConfig config = new HyperConduitConfig();
        config.enabled = true;

        // A plain handler is enough here: the initializer must keep it installed after wrapping.
        ChannelHandler minecraft = new ChannelInboundHandlerAdapter();
        EmbeddedChannel channel = new EmbeddedChannel(
                new ClientTunnelInitializer(minecraft, config, ORIGINAL_ENDPOINT));

        try {
            assertNotNull(channel.pipeline().context(minecraft));
            assertNotNull(channel.pipeline().context("hyperconduit-dial"));

            InetSocketAddress unresolved = InetSocketAddress.createUnresolved("mc.invalid", 25565);
            ChannelFuture connect = channel.connect(unresolved);
            assertTrue(connect.await(2, TimeUnit.SECONDS));

            assertFalse(connect.isSuccess());
            assertInstanceOf(SidecarException.class, connect.cause());
            assertEquals("address_unresolved", ((SidecarException) connect.cause()).code());
            assertTrue(((SidecarException) connect.cause()).getMessage().startsWith("[HyperConduit] "));
            assertNull(connect.cause().getCause());
            assertFalse(channel.isOpen());

            // The unresolved path must not reach ConfigHolder/TunnelService: no config directory is
            // installed in this test, so an attempted sidecar launch would surface as a different
            // failure than the deterministic address check above.
        } finally {
            channel.close();
        }
    }

    @Test
    void realBootstrapCarriesMinecraftBytesAndPinsOriginalEndpoint() throws Exception {
        HyperConduitConfig config = new HyperConduitConfig();
        config.enabled = true;
        config.connectTimeoutMs = 10_000;
        ConfigHolder.install(config, directory);

        try (ServerSocket backend = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Void> echo = echoOnce(backend, MINECRAFT_HANDSHAKE.length);
            try (SidecarProcess server = startServer("server", backend.getLocalPort(), null)) {
                SidecarProcess.Ready ready = server.ready().get(15, TimeUnit.SECONDS);
                assertTrue(ready.fingerprint().startsWith("SHA256:"));
                InetSocketAddress destination = ready.listen();

                RecordingInitializer minecraft = new RecordingInitializer(MINECRAFT_HANDSHAKE.length);
                EventLoopGroup group = new NioEventLoopGroup(1);
                Channel channel = null;
                try {
                    Bootstrap bootstrap = new Bootstrap()
                            .group(group)
                            .channel(NioSocketChannel.class)
                            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15_000)
                            .handler(new ClientTunnelInitializer(minecraft, config, ORIGINAL_ENDPOINT));

                    ChannelFuture connected = bootstrap.connect(destination);
                    assertTrue(connected.await(20, TimeUnit.SECONDS), "tunnel connect did not complete");
                    assertTrue(connected.isSuccess(), () -> "tunnel connect failed: " + connected.cause());
                    channel = connected.channel();
                    assertTrue(minecraft.initialized());
                    assertEquals(1, minecraft.activeCount());

                    assertEquals(destination, channel.attr(TunnelAttributes.PEER).get());
                    assertEquals(Boolean.TRUE, channel.attr(TunnelAttributes.TUNNEL).get());

                    channel.writeAndFlush(Unpooled.wrappedBuffer(MINECRAFT_HANDSHAKE)).sync();
                    assertArrayEquals(MINECRAFT_HANDSHAKE, minecraft.payload().get(10, TimeUnit.SECONDS));
                    echo.get(5, TimeUnit.SECONDS);

                    assertPinnedOriginalEndpoint(destination);

                    InetSocketAddress clientListen = (InetSocketAddress) channel.remoteAddress();
                    channel.close().sync();
                    channel = null;

                    // No public child handle is exposed. Closing the listener is observable and
                    // proves that the managed client process did not outlive the Netty channel.
                    awaitCondition(() -> !canConnect(clientListen), Duration.ofSeconds(5));
                } finally {
                    if (channel != null) channel.close().sync();
                    group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
                }
            }
        }
    }

    @Test
    void badTokenFailsTheConnectPromiseWithoutTcpFallback() throws Exception {
        HyperConduitConfig config = new HyperConduitConfig();
        config.enabled = true;
        config.connectTimeoutMs = 10_000;
        config.clientToken = "wrong-client-token";
        ConfigHolder.install(config, directory);

        try (SidecarProcess server = startServer("bad-server", 25565, "correct-server-token")) {
            InetSocketAddress destination = server.ready().get(15, TimeUnit.SECONDS).listen();

            // A TCP listener on the same numeric port is the trap: if the initializer ever fell
            // back to a direct connection it would connect here instead of failing the promise.
            try (ServerSocket trap = new ServerSocket(destination.getPort(), 1,
                    InetAddress.getByName("127.0.0.1"))) {
                CompletableFuture<Socket> accepted = new CompletableFuture<>();
                Thread.ofVirtual().name("hyperconduit-fallback-trap").start(() -> {
                    try {
                        accepted.complete(trap.accept());
                    } catch (Throwable failure) {
                        accepted.completeExceptionally(failure);
                    }
                });

                EventLoopGroup group = new NioEventLoopGroup(1);
                Channel channel = null;
                try {
                    Bootstrap bootstrap = new Bootstrap()
                            .group(group)
                            .channel(NioSocketChannel.class)
                            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15_000)
                            .handler(new ClientTunnelInitializer(new RecordingInitializer(0), config, ORIGINAL_ENDPOINT));

                    ChannelFuture failed = bootstrap.connect(destination);
                    assertTrue(failed.await(20, TimeUnit.SECONDS), "bad-token connect did not complete");
                    assertTrue(failed.isDone());
                    assertFalse(failed.isSuccess());
                    assertInstanceOf(SidecarException.class, failed.cause());
                    assertEquals("authentication", ((SidecarException) failed.cause()).code());
                    channel = failed.channel();
                    assertFalse(channel.isOpen());

                    Socket fallback = null;
                    try {
                        fallback = accepted.get(500, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException expectedNoConnection) {
                        // No direct-TCP fallback was attempted.
                    }
                    assertNull(fallback,
                            "client initializer must not fall back to a direct TCP connection");
                } finally {
                    if (channel != null) channel.close().sync();
                    group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
                }
            }
        }
    }

    private SidecarProcess startServer(String name, int backendPort, String token) throws IOException {
        Map<String, Object> values = new HashMap<>();
        values.put("listen", "127.0.0.1:0");
        values.put("target", "127.0.0.1:" + backendPort);
        values.put("stateDir", directory.resolve(name + "-state").toString());
        if (token != null) {
            values.put("token", token);
        }
        return SidecarProcess.startConfigured(
                directory.resolve(name + "-bin"),
                directory.resolve(name + "-launch"),
                "server",
                values,
                directory,
                SERVER_STARTUP,
                SERVER_STOP);
    }

    private void assertPinnedOriginalEndpoint(InetSocketAddress destination) throws Exception {
        Path knownServers = directory.resolve("hyperconduit").resolve("known-servers.json");
        awaitCondition(() -> Files.isRegularFile(knownServers), Duration.ofSeconds(5));

        JsonObject root = JsonParser.parseString(Files.readString(knownServers)).getAsJsonObject();
        JsonObject pins = root.getAsJsonObject("pins");
        assertNotNull(pins);
        assertTrue(pins.has(ORIGINAL_ENDPOINT), () -> "missing original endpoint pin: " + pins);
        assertFalse(pins.has("127.0.0.1:" + destination.getPort()),
                () -> "resolved address must not be pinned: " + pins);
    }

    private static CompletableFuture<Void> echoOnce(ServerSocket server, int length) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread.ofVirtual().name("hyperconduit-test-echo").start(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(10_000);
                byte[] payload = socket.getInputStream().readNBytes(length);
                if (payload.length != length) {
                    throw new IOException("short backend read");
                }
                socket.getOutputStream().write(payload);
                socket.getOutputStream().flush();
                done.complete(null);
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        });
        return done;
    }

    private static boolean canConnect(InetSocketAddress address) {
        try (Socket probe = new Socket()) {
            probe.connect(address, 100);
            return true;
        } catch (IOException expected) {
            return false;
        }
    }

    private static void awaitCondition(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        fail("condition was not met within " + timeout);
    }

    /** Stand-in for Minecraft's network initializer; records the exact bytes it receives. */
    private static final class RecordingInitializer extends ChannelInitializer<Channel> {
        private final int expectedBytes;
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> payload = new CompletableFuture<>();
        private final AtomicInteger activeCount = new AtomicInteger();
        private volatile boolean initialized;

        RecordingInitializer(int expectedBytes) {
            this.expectedBytes = expectedBytes;
        }

        @Override
        protected void initChannel(Channel channel) {
            initialized = true;
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
                        synchronized (received) {
                            received.write(bytes, 0, bytes.length);
                            if (received.size() >= expectedBytes && !payload.isDone()) {
                                payload.complete(received.toByteArray());
                            }
                        }
                        ReferenceCountUtil.release(buffer);
                    } else {
                        ReferenceCountUtil.release(msg);
                    }
                }
            });
        }

        boolean initialized() {
            return initialized;
        }

        int activeCount() {
            return activeCount.get();
        }

        CompletableFuture<byte[]> payload() {
            return payload;
        }
    }
}
