package io.hyperconduit.demo;

import io.hyperconduit.conn.SessionEngine;
import io.hyperconduit.net.UdpServerEndpoint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Benchmark server: accepts tunnel sessions and speaks {@link BenchProtocol}.
 *
 * <p>Deploy this on the far end of the link under test (an overseas box, a domestic cross-carrier
 * box) and point {@link BenchClient} at it. One process serves many concurrent sessions on a single
 * UDP socket, which is what the interactive-latency-under-load measurement needs.
 */
public final class BenchServer {

    private static final int CHUNK = 64 * 1024;
    private static final byte[] OK_REPLY = (BenchProtocol.OK + "\n").getBytes(StandardCharsets.UTF_8);

    private final Map<SessionEngine, SessionState> states = new ConcurrentHashMap<>();
    private final CountDownLatch stopped = new CountDownLatch(1);

    private UdpServerEndpoint endpoint;
    private volatile long sessionsAccepted;
    private volatile long bytesEchoed;
    private volatile long bytesSunk;
    private volatile long bytesGenerated;

    private static final class SessionState {
        final ByteArrayOutputStream header = new ByteArrayOutputStream();
        boolean parsed;
        String command = "";
        long argument;
        long received;
        boolean replied;
        long sendIndex;
        long remainingToSend;
    }

    public void run(BenchOptions options) throws IOException {
        endpoint = new UdpServerEndpoint(options.address(), options.serverConfig(),
                new UdpServerEndpoint.SessionHandler() {
                    @Override
                    public void onSessionEstablished(SessionEngine session, SocketAddress peer) {
                        states.put(session, new SessionState());
                        sessionsAccepted++;
                        System.out.printf("[server] session %08x from %s (total %,d)%n",
                                session.connectionId(), peer, sessionsAccepted);
                    }

                    @Override
                    public void onSessionReadable(SessionEngine session) {
                        handleReadable(session);
                    }

                    @Override
                    public void onSessionWritable(SessionEngine session) {
                        SessionState state = states.get(session);
                        if (state != null && state.remainingToSend > 0) {
                            pumpGenerated(session, state);
                        }
                    }

                    @Override
                    public void onSessionClosed(SessionEngine session, int reasonCode, String message) {
                        states.remove(session);
                        System.out.printf("[server] session %08x closed (%d: %s)%n",
                                session.connectionId(), reasonCode, message);
                    }

                    @Override
                    public void onSessionError(SessionEngine session, Throwable cause) {
                        states.remove(session);
                        System.out.printf("[server] session %08x error: %s%n",
                                session.connectionId(), cause);
                    }
                });
        endpoint.start();

        System.out.printf("[server] listening on %s%n", endpoint.localAddress());
        System.out.printf("[server] congestion control = %s, target send rate = %d Mbit/s%n",
                options.controller(), options.mbps());
        System.out.printf("[server] receive window = %,d bytes%n%n", options.windowBytes());
        System.out.println("[server] commands: ECHO | SINK <bytes> | SEND <bytes>");
        System.out.println("[server] press Ctrl-C to stop");

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "bench-server-shutdown"));
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        if (endpoint != null) {
            endpoint.close();
        }
        System.out.printf("%n[server] sessions=%,d echoed=%,d B sunk=%,d B sent=%,d B%n",
                sessionsAccepted, bytesEchoed, bytesSunk, bytesGenerated);
        stopped.countDown();
    }

    public int sessionCount() {
        return endpoint == null ? 0 : endpoint.sessionCount();
    }

    private void handleReadable(SessionEngine session) {
        SessionState state = states.get(session);
        if (state == null) {
            return;
        }
        byte[] buffer = new byte[CHUNK];
        while (true) {
            int n = session.read(buffer, 0, buffer.length);
            if (n <= 0) {
                return;
            }
            if (!state.parsed) {
                state.header.write(buffer, 0, n);
                byte[] accumulated = state.header.toByteArray();
                BenchProtocol.Request request = BenchProtocol.parseRequest(accumulated, accumulated.length);
                if (request.headerLength() == 0) {
                    if (accumulated.length > 256) {
                        System.err.println("[server] no request line within 256 bytes, closing");
                        session.close(SessionEngine.CLOSE_REASON_ERROR, "malformed request");
                    }
                    continue;
                }
                state.parsed = true;
                state.command = request.command();
                state.argument = request.argument();
                startCommand(session, state);
                int bodyOffset = request.headerLength();
                if (bodyOffset < accumulated.length) {
                    handleBody(session, state, accumulated, bodyOffset, accumulated.length - bodyOffset);
                }
            } else {
                handleBody(session, state, buffer, 0, n);
            }
        }
    }

    private void startCommand(SessionEngine session, SessionState state) {
        switch (state.command) {
            case BenchProtocol.ECHO, BenchProtocol.SINK -> {
            }
            case BenchProtocol.SEND -> {
                state.remainingToSend = state.argument;
                state.sendIndex = 0;
                pumpGenerated(session, state);
            }
            default -> {
                System.err.printf("[server] unknown command %s, closing%n", state.command);
                session.close(SessionEngine.CLOSE_REASON_ERROR, "unknown command");
            }
        }
    }

    private void handleBody(SessionEngine session, SessionState state, byte[] data, int off, int len) {
        switch (state.command) {
            case BenchProtocol.ECHO -> {
                session.write(data, off, len);
                bytesEchoed += len;
            }
            case BenchProtocol.SINK -> {
                state.received += len;
                bytesSunk += len;
                if (!state.replied && state.received >= state.argument) {
                    state.replied = true;
                    session.write(OK_REPLY, 0, OK_REPLY.length);
                }
            }
            // SEND ignores anything further the client writes.
            default -> {
            }
        }
    }

    /** Generates payload while the tunnel will take it, resuming from {@code onSessionWritable}. */
    private void pumpGenerated(SessionEngine session, SessionState state) {
        while (state.remainingToSend > 0 && session.isWritable()) {
            int chunk = (int) Math.min(CHUNK, state.remainingToSend);
            session.write(BenchProtocol.payloadChunk(state.sendIndex, chunk), 0, chunk);
            state.sendIndex += chunk;
            state.remainingToSend -= chunk;
            bytesGenerated += chunk;
        }
    }
}
