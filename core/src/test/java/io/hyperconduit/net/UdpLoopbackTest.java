package io.hyperconduit.net;

import io.hyperconduit.conn.SessionConfig;
import io.hyperconduit.conn.SessionEngine;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real UDP transport over loopback. Loss-free, so this validates the plumbing rather
 * than the reliability logic: the selector loops, connection-id demultiplexing, session lifecycle
 * and thread safety. {@code SimulatedLink} covers loss and reordering deterministically.
 */
class UdpLoopbackTest {

    private static final byte[] PSK = "loopback-test-psk".getBytes(StandardCharsets.UTF_8);
    private static final long BPS = 100_000_000L / 8;
    private static final int CHUNK = 64 * 1024;

    private UdpServerEndpoint server;
    private UdpClientSession client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private static UdpServerEndpoint.SessionHandler echoHandler(CountDownLatch established,
                                                                Map<Integer, SessionEngine> sessions) {
        return new UdpServerEndpoint.SessionHandler() {
            @Override
            public void onSessionEstablished(SessionEngine session, SocketAddress peerAddress) {
                sessions.put(session.connectionId(), session);
                established.countDown();
            }

            @Override
            public void onSessionReadable(SessionEngine session) {
                byte[] buffer = new byte[CHUNK];
                while (true) {
                    int n = session.read(buffer, 0, buffer.length);
                    if (n <= 0) {
                        return;
                    }
                    session.write(buffer, 0, n);
                }
            }
        };
    }

    private InetSocketAddress startEchoServer(byte[] psk, CountDownLatch established,
                                              Map<Integer, SessionEngine> sessions) throws Exception {
        SessionConfig config = SessionConfig.server(psk)
                .congestionController(SessionConfig.brutal(BPS));
        server = new UdpServerEndpoint(new InetSocketAddress("127.0.0.1", 0), config,
                echoHandler(established, sessions));
        server.start();
        return (InetSocketAddress) server.localAddress();
    }

    private UdpClientSession startClient(InetSocketAddress target, byte[] psk, int sendBuffer)
            throws Exception {
        client = newClient(target, psk, sendBuffer);
        return client;
    }

    /** Creates a client the caller owns, for tests that need more than one at a time. */
    private static UdpClientSession newClient(InetSocketAddress target, byte[] psk, int sendBuffer)
            throws Exception {
        SessionConfig config = SessionConfig.client(psk)
                .connectionId(new SecureRandom().nextInt())
                .congestionController(SessionConfig.brutal(BPS))
                .sendBufferBytes(sendBuffer);
        UdpClientSession session = new UdpClientSession(target, config, null);
        session.start();
        return session;
    }

    @Test
    void echoRoundTripOverRealUdp() throws Exception {
        CountDownLatch established = new CountDownLatch(1);
        Map<Integer, SessionEngine> sessions = new ConcurrentHashMap<>();
        InetSocketAddress bind = startEchoServer(PSK, established, sessions);

        int size = 512 * 1024;
        UdpClientSession session = startClient(bind, PSK, size + CHUNK);
        assertTrue(session.awaitEstablished(TimeUnit.SECONDS.toNanos(15)),
                "handshake must complete over loopback, state=" + session.engine().state());
        assertTrue(established.await(5, TimeUnit.SECONDS), "the server must report the session");
        assertEquals(1, server.sessionCount());

        byte[] payload = new byte[size];
        new SecureRandom().nextBytes(payload);
        session.engine().write(payload, 0, payload.length);

        byte[] echoed = new byte[size];
        int received = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (received < size && System.nanoTime() < deadline) {
            int n = session.engine().read(echoed, received, size - received);
            if (n > 0) {
                received += n;
            } else {
                Thread.sleep(2);
            }
        }
        assertEquals(size, received, "the whole payload must come back");
        assertArrayEquals(payload, echoed);
    }

    @Test
    void wrongPskNeverEstablishes() throws Exception {
        CountDownLatch established = new CountDownLatch(1);
        Map<Integer, SessionEngine> sessions = new ConcurrentHashMap<>();
        InetSocketAddress bind = startEchoServer(PSK, established, sessions);

        UdpClientSession session = startClient(bind, "not-the-psk".getBytes(StandardCharsets.UTF_8),
                CHUNK);
        assertFalse(session.awaitEstablished(TimeUnit.SECONDS.toNanos(3)),
                "a client without the PSK must not establish");
        assertEquals(0, server.sessionCount(),
                "and must not even get a session allocated on the server");
        assertFalse(established.await(1, TimeUnit.SECONDS));
    }

    @Test
    void twoClientsShareOneServerSocket() throws Exception {
        CountDownLatch established = new CountDownLatch(2);
        Map<Integer, SessionEngine> sessions = new ConcurrentHashMap<>();
        InetSocketAddress bind = startEchoServer(PSK, established, sessions);

        int size = 64 * 1024;
        byte[] first = new byte[size];
        byte[] second = new byte[size];
        new SecureRandom().nextBytes(first);
        new SecureRandom().nextBytes(second);

        UdpClientSession a = newClient(bind, PSK, size + CHUNK);
        UdpClientSession b = newClient(bind, PSK, size + CHUNK);
        try {
            assertTrue(a.awaitEstablished(TimeUnit.SECONDS.toNanos(15)));
            assertTrue(b.awaitEstablished(TimeUnit.SECONDS.toNanos(15)));
            assertTrue(established.await(10, TimeUnit.SECONDS));
            assertEquals(2, server.sessionCount(), "connection ids must keep the sessions distinct");

            a.engine().write(first, 0, first.length);
            b.engine().write(second, 0, second.length);

            assertArrayEquals(first, readFully(a, size));
            assertArrayEquals(second, readFully(b, size));
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    void closedSessionsAreEvictedFromTheRoutingTable() throws Exception {
        CountDownLatch established = new CountDownLatch(1);
        Map<Integer, SessionEngine> sessions = new ConcurrentHashMap<>();
        InetSocketAddress bind = startEchoServer(PSK, established, sessions);

        UdpClientSession session = startClient(bind, PSK, CHUNK);
        assertTrue(session.awaitEstablished(TimeUnit.SECONDS.toNanos(15)));
        assertEquals(1, server.sessionCount());

        session.engine().close(SessionEngine.CLOSE_REASON_LOCAL, "bye");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (server.sessionCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, server.sessionCount(), "a closed session must stop consuming routing state");
    }

    private static byte[] readFully(UdpClientSession session, int size) throws InterruptedException {
        byte[] out = new byte[size];
        int received = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (received < size && System.nanoTime() < deadline) {
            int n = session.engine().read(out, received, size - received);
            if (n > 0) {
                received += n;
            } else {
                Thread.sleep(2);
            }
        }
        assertEquals(size, received, "timed out waiting for the echo");
        return out;
    }
}
