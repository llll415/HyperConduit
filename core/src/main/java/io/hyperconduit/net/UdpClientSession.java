package io.hyperconduit.net;

import io.hyperconduit.conn.SessionConfig;
import io.hyperconduit.conn.SessionEngine;
import io.hyperconduit.conn.SessionListener;
import io.hyperconduit.util.Clock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Drives one {@link SessionEngine} over a {@link DatagramChannel} on a dedicated thread.
 *
 * <p>This is the reference driver: select for readability with a timeout taken from
 * {@link SessionEngine#nanosUntilNextEvent(long)}, feed arrivals in, then drain
 * {@link SessionEngine#nextDatagram()} out. The Netty integration replaces this class, not the
 * engine — Netty's event loop plays exactly the same role.
 *
 * <p>Pacing granularity is bounded below by the 1ms resolution of {@link Selector#select(long)}.
 * That is consistent with the design: the pacer's own minimum delay and burst allowance are also
 * expressed in milliseconds, so a burst emitted per wakeup stays within what Brutal intends.
 */
public final class UdpClientSession implements AutoCloseable {

    /**
     * Socket buffer size. Brutal deliberately bursts, and the default OS buffer (often 64-256 KB)
     * overflows well below the configured rate — the receiver then drops datagrams the sender never
     * had a chance to pace around, which shows up as self-inflicted loss and needless retransmission.
     * Sized to hold a few burst maxima; the OS may clamp it lower.
     */
    private static final int SOCKET_BUFFER_BYTES = 4 * 1024 * 1024;

    private final DatagramChannel channel;
    private final Selector selector;
    private final SessionEngine engine;
    private final SocketAddress remote;
    private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(64 * 1024);
    private final CountDownLatch established = new CountDownLatch(1);
    private final CountDownLatch closed = new CountDownLatch(1);
    private final SessionListener listener;

    private volatile boolean running = true;
    private Thread thread;

    public UdpClientSession(InetSocketAddress remote, SessionConfig config, SessionListener listener)
            throws IOException {
        this(remote, config, listener, Clock.SYSTEM);
    }

    public UdpClientSession(InetSocketAddress remote, SessionConfig config, SessionListener listener,
                            Clock clock) throws IOException {
        this.remote = remote;
        this.listener = listener == null ? new SessionListener() {
        } : listener;
        this.channel = DatagramChannel.open();
        this.channel.configureBlocking(false);
        SocketBuffers.apply(this.channel, SOCKET_BUFFER_BYTES, "client");
        this.selector = Selector.open();
        this.channel.register(selector, SelectionKey.OP_READ);
        this.engine = SessionEngine.client(config, clock, new SessionListener() {
            @Override
            public void onPeerConfirmed() {
                established.countDown();
                UdpClientSession.this.listener.onEstablished();
            }

            @Override
            public void onReadable() {
                UdpClientSession.this.listener.onReadable();
            }

            @Override
            public void onWritable() {
                UdpClientSession.this.listener.onWritable();
            }

            @Override
            public void onClosed(int reasonCode, String message) {
                closed.countDown();
                UdpClientSession.this.listener.onClosed(reasonCode, message);
            }

            @Override
            public void onError(Throwable cause) {
                UdpClientSession.this.listener.onError(cause);
            }
        });
        // An application write from another thread must break us out of select immediately.
        this.engine.setWakeupHook(selector::wakeup);
    }

    public SessionEngine engine() {
        return engine;
    }

    public void start() {
        if (thread != null) {
            throw new IllegalStateException("already started");
        }
        thread = new Thread(this::loop, "hyperconduit-client");
        thread.setDaemon(true);
        thread.start();
    }

    public boolean awaitEstablished(long timeoutNanos) throws InterruptedException {
        return established.await(timeoutNanos, TimeUnit.NANOSECONDS);
    }

    public boolean awaitClosed(long timeoutNanos) throws InterruptedException {
        return closed.await(timeoutNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void close() {
        running = false;
        selector.wakeup();
        try {
            if (thread != null) {
                thread.join(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            selector.close();
        } catch (IOException ignored) {
            // nothing useful to do while tearing down
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // nothing useful to do while tearing down
        }
    }

    private void loop() {
        try {
            while (running) {
                long waitNanos = engine.nanosUntilNextEvent(System.nanoTime());
                DriverWait.await(selector, waitNanos);
                selector.selectedKeys().clear();
                receive();
                transmit();

                if (engine.state() == SessionEngine.State.CLOSED) {
                    running = false;
                }
            }
        } catch (IOException e) {
            if (running) {
                listener.onError(e);
            }
        } finally {
            closed.countDown();
        }
    }

    private void receive() throws IOException {
        while (true) {
            readBuffer.clear();
            SocketAddress from = channel.receive(readBuffer);
            if (from == null) {
                return;
            }
            readBuffer.flip();
            byte[] datagram = new byte[readBuffer.remaining()];
            readBuffer.get(datagram);
            engine.onDatagramReceived(datagram, 0, datagram.length);
        }
    }

    private void transmit() throws IOException {
        while (true) {
            byte[] datagram = engine.nextDatagram();
            if (datagram == null) {
                return;
            }
            channel.send(ByteBuffer.wrap(datagram), remote);
        }
    }
}
