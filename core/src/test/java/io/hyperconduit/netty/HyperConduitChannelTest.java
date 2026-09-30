package io.hyperconduit.netty;

import io.hyperconduit.conn.SessionConfig;
import io.hyperconduit.conn.SessionListener;
import io.hyperconduit.net.UdpServerEndpoint;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the Netty bridge: a HyperConduitChannel driven through a standard Netty
 * Bootstrap, with a normal ByteToMessageDecoder/encoder pipeline on top — exactly the shape MC's
 * Connection uses. Runs against the real {@link UdpServerEndpoint} echo server over loopback UDP.
 */
class HyperConduitChannelTest {

    private static final byte[] PSK = "netty-bridge-test".getBytes(StandardCharsets.UTF_8);
    private static final long BPS = 100_000_000L / 8;
    private static final int CHUNK = 4096;

    private EventLoopGroup group;
    private UdpServerEndpoint server;
    private Channel tcpServer;
    private Channel tunnelServer;

    @AfterEach
    void tearDown() {
        if (tcpServer != null) {
            tcpServer.close().syncUninterruptibly();
        }
        if (tunnelServer != null) {
            tunnelServer.close().syncUninterruptibly();
        }
        if (server != null) {
            server.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    private InetSocketAddress startEchoServer() throws Exception {
        Map<Integer, io.hyperconduit.conn.SessionEngine> sessions = new ConcurrentHashMap<>();
        SessionConfig config = SessionConfig.server(PSK)
                .congestionController(SessionConfig.brutal(BPS));
        server = new UdpServerEndpoint(new InetSocketAddress("127.0.0.1", 0), config,
                new UdpServerEndpoint.SessionHandler() {
                    @Override
                    public void onSessionEstablished(io.hyperconduit.conn.SessionEngine session,
                                                     SocketAddress peer) {
                        sessions.put(session.connectionId(), session);
                    }

                    @Override
                    public void onSessionReadable(io.hyperconduit.conn.SessionEngine session) {
                        byte[] buffer = new byte[64 * 1024];
                        while (true) {
                            int n = session.read(buffer, 0, buffer.length);
                            if (n <= 0) {
                                return;
                            }
                            session.write(buffer, 0, n);
                        }
                    }
                });
        server.start();
        return (InetSocketAddress) server.localAddress();
    }

    @Test
    void channelCarriesBytesThroughARealNettyPipeline() throws Exception {
        InetSocketAddress peer = startEchoServer();
        group = new NioEventLoopGroup(2);

        SessionConfig clientConfig = SessionConfig.client(PSK)
                .connectionId(new SecureRandom().nextInt())
                .congestionController(SessionConfig.brutal(BPS));

        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch echoReceived = new CountDownLatch(1);
        AtomicReference<byte[]> echo = new AtomicReference<>();
        AtomicInteger activeChannels = new AtomicInteger();

        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channelFactory(() -> new HyperConduitChannel(
                        new HyperConduitChannel.ChannelOptions(peer, clientConfig)))
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                activeChannels.incrementAndGet();
                                connected.countDown();
                                ctx.fireChannelActive();
                            }

                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                ByteBuf buf = (ByteBuf) msg;
                                byte[] bytes = new byte[buf.readableBytes()];
                                buf.readBytes(bytes);
                                buf.release();
                                echo.set(bytes);
                                echoReceived.countDown();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                echoReceived.countDown();
                                ctx.close();
                            }
                        });
                    }
                });

        ChannelFuture connectFuture = bootstrap.connect(peer);
        boolean connectedOk = connectFuture.await(15, TimeUnit.SECONDS);
        if (!connectFuture.isSuccess()) {
            throw new AssertionError("connect failed: " + connectFuture.cause(),
                    connectFuture.cause());
        }
        assertTrue(connectedOk, "connect future must complete");
        assertTrue(connected.await(10, TimeUnit.SECONDS),
                "channelActive must fire after the handshake");
        assertEquals(1, activeChannels.get());

        Channel channel = connectFuture.channel();
        assertTrue(channel instanceof HyperConduitChannel);
        assertTrue(((HyperConduitChannel) channel).isHandshaken());

        byte[] message = "hello minecraft via hyperconduit".getBytes(StandardCharsets.UTF_8);
        channel.writeAndFlush(Unpooled.wrappedBuffer(message)).sync();

        assertTrue(echoReceived.await(15, TimeUnit.SECONDS),
                "the echo server must answer");
        assertArrayEquals(message, echo.get(), "bytes must round-trip unchanged");

        channel.close().sync();
    }

    @Test
    void multipleMessagesRoundTripAsAByteStream() throws Exception {
        InetSocketAddress peer = startEchoServer();
        group = new NioEventLoopGroup(2);

        SessionConfig clientConfig = SessionConfig.client(PSK)
                .connectionId(new SecureRandom().nextInt())
                .congestionController(SessionConfig.brutal(BPS));

        // A byte stream does not preserve message boundaries, so we verify the concatenated
        // content rather than expecting four separate reads. Collect chunks thread-safely
        // because channelRead runs on the event loop while the assertion runs on the test thread.
        CountDownLatch allReceived = new CountDownLatch(1);
        java.util.concurrent.ConcurrentLinkedQueue<byte[]> chunks = new java.util.concurrent.ConcurrentLinkedQueue<>();

        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channelFactory(() -> new HyperConduitChannel(
                        new HyperConduitChannel.ChannelOptions(peer, clientConfig)))
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                ByteBuf buf = (ByteBuf) msg;
                                byte[] bytes = new byte[buf.readableBytes()];
                                buf.readBytes(bytes);
                                buf.release();
                                chunks.add(bytes);
                            }
                        });
                    }
                });

        ChannelFuture connectFuture = bootstrap.connect(peer);
        assertTrue(connectFuture.await(15, TimeUnit.SECONDS));
        Channel channel = connectFuture.channel();

        String expected = "";
        for (int i = 0; i < 4; i++) {
            expected += "msg" + i + ";";
        }
        for (String part : expected.split("(?<=;)")) {
            channel.writeAndFlush(Unpooled.wrappedBuffer(part.getBytes(StandardCharsets.UTF_8))).sync();
        }

        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        int total = 0;
        while (System.nanoTime() < deadline) {
            total = chunks.stream().mapToInt(c -> c.length).sum();
            if (total == expectedBytes.length) {
                break;
            }
            Thread.sleep(50);
        }

        java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            sink.write(chunk, 0, chunk.length);
        }
        byte[] actual = sink.toByteArray();
        assertArrayEquals(expectedBytes, actual,
                "the concatenated byte stream must round-trip unchanged");
        channel.close().sync();
    }

    @Test
    void tunnelAndVanillaTcpCanShareOnePort() throws Exception {
        group = new NioEventLoopGroup(2);
        SessionConfig serverConfig = SessionConfig.server(PSK)
                .congestionController(SessionConfig.brutal(BPS));
        ChannelInitializer<Channel> echoHandler = new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel channel) {
                channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object message) {
                        ctx.writeAndFlush(message);
                    }
                });
            }
        };

        tcpServer = new ServerBootstrap()
                .group(group, group)
                .channel(NioServerSocketChannel.class)
                .childHandler(echoHandler)
                .bind(new InetSocketAddress("127.0.0.1", 0))
                .sync()
                .channel();
        InetSocketAddress address = (InetSocketAddress) tcpServer.localAddress();
        tunnelServer = new ServerBootstrap()
                .group(group, group)
                .channelFactory(() -> new HyperConduitServerChannel(
                        new HyperConduitServerChannel.ServerOptions(null, serverConfig)))
                .childHandler(echoHandler)
                .bind(address)
                .sync()
                .channel();

        CountDownLatch vanillaEchoed = new CountDownLatch(1);
        Channel vanillaClient = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object message) {
                        ((ByteBuf) message).release();
                        vanillaEchoed.countDown();
                    }
                })
                .connect(address)
                .sync()
                .channel();
        vanillaClient.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{1})).sync();
        assertTrue(vanillaEchoed.await(10, TimeUnit.SECONDS));
        vanillaClient.close().sync();

        CountDownLatch echoed = new CountDownLatch(1);
        AtomicReference<byte[]> response = new AtomicReference<>();
        SessionConfig clientConfig = SessionConfig.client(PSK)
                .connectionId(new SecureRandom().nextInt())
                .congestionController(SessionConfig.brutal(BPS));
        Channel client = new Bootstrap()
                .group(group)
                .channelFactory(() -> new HyperConduitChannel(
                        new HyperConduitChannel.ChannelOptions(address, clientConfig)))
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object message) {
                        ByteBuf buffer = (ByteBuf) message;
                        byte[] bytes = new byte[buffer.readableBytes()];
                        buffer.readBytes(bytes);
                        buffer.release();
                        response.set(bytes);
                        echoed.countDown();
                    }
                })
                .connect(address)
                .sync()
                .channel();

        byte[] request = "both transports, one port".getBytes(StandardCharsets.UTF_8);
        client.writeAndFlush(Unpooled.wrappedBuffer(request)).sync();
        assertTrue(echoed.await(10, TimeUnit.SECONDS));
        assertArrayEquals(request, response.get());
        client.close().sync();
    }

    @Test
    void handshakeFailureIsReportedOnTheConnectFuture() throws Exception {
        // No server running: the handshake must fail rather than hang forever.
        group = new NioEventLoopGroup(2);
        SessionConfig clientConfig = SessionConfig.client(PSK)
                .connectionId(new SecureRandom().nextInt())
                .congestionController(SessionConfig.brutal(BPS))
                .handshakeTimeoutNanos(3_000_000_000L);

        InetSocketAddress nowhere = new InetSocketAddress("127.0.0.1", 1);
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channelFactory(() -> new HyperConduitChannel(
                        new HyperConduitChannel.ChannelOptions(nowhere, clientConfig)))
                .handler(new ChannelInboundHandlerAdapter() {
                });

        ChannelFuture future = bootstrap.connect(nowhere);
        assertTrue(future.await(15, TimeUnit.SECONDS), "the future must complete one way or another");
        assertTrue(!future.isSuccess(), "and it must be a failure, not a hang");
        assertTrue(future.cause() != null);
    }
}
