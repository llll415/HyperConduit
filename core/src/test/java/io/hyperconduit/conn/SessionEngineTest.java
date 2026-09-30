package io.hyperconduit.conn;

import io.hyperconduit.frame.PacketCodec;
import io.hyperconduit.sim.ManualClock;
import io.hyperconduit.sim.SimulatedLink;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end session tests over {@link SimulatedLink}: a deterministic clock and a simulated lossy,
 * reordered, delayed link. These are the tests that decide whether the transport actually works,
 * because they exercise handshake retransmission, loss recovery, ordering and flow control together
 * rather than one component at a time.
 */
class SessionEngineTest {

    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;
    private static final byte[] PSK = "session-engine-test-psk".getBytes(StandardCharsets.UTF_8);
    private static final int CONNECTION_ID = 0x0BADF00D;
    /** 100 Mbit/s, a plausible residential uplink. */
    private static final long BPS = 100_000_000L / 8;

    private static SessionConfig clientConfig() {
        return SessionConfig.client(PSK)
                .connectionId(CONNECTION_ID)
                .congestionController(SessionConfig.brutal(BPS));
    }

    private static SessionConfig serverConfig() {
        return SessionConfig.server(PSK)
                .congestionController(SessionConfig.brutal(BPS));
    }

    private static SimulatedLink link() {
        return new SimulatedLink(clientConfig(), serverConfig());
    }

    private static byte[] payload(int size) {
        byte[] data = new byte[size];
        new Random(0xBEEFL).nextBytes(data);
        return data;
    }

    // --- handshake ---------------------------------------------------------------

    @Test
    void handshakeCompletesOverACleanLink() {
        SimulatedLink link = link();
        assertTrue(link.runUntil(link::bothEstablished, 2 * SECOND), "handshake did not complete");
        assertNull(link.clientError);
        assertNull(link.serverError);
        // Three messages, plus whatever the client's first data-less packets elicit.
        assertTrue(link.delivered >= 3, "expected at least the three handshake messages, got " + link.delivered);
    }

    @Test
    void handshakeSurvivesHeavyLoss() {
        // Deterministic loss: every second datagram dies, so retransmission is certainly exercised.
        // A random rate cannot guarantee that for a handshake of only a few messages.
        SimulatedLink link = link().lossEveryNth(2);
        assertTrue(link.runUntil(link::bothEstablished, 20 * SECOND),
                "handshake must survive losing every other datagram");
        assertTrue(link.dropped > 0, "the test must actually have dropped something");
        assertNull(link.clientError);
        assertNull(link.serverError);
    }

    @Test
    void handshakeFailsWhenThePskDoesNotMatch() {
        SessionConfig wrongPskClient = SessionConfig.client("not-the-psk".getBytes(StandardCharsets.UTF_8))
                .connectionId(CONNECTION_ID)
                .congestionController(SessionConfig.brutal(BPS));
        SimulatedLink link = new SimulatedLink(wrongPskClient, serverConfig());

        // The PSK derives the header mask key, so the server cannot even parse message 1 and drops
        // it silently. That is the desired behaviour: no oracle, no work performed. The client
        // therefore fails by timeout rather than by an explicit rejection.
        link.run(20 * SECOND);
        assertFalse(link.bothEstablished());
        assertEquals(SessionEngine.State.CLOSED, link.client.state());
        assertNotNull(link.clientError, "the client must report why it gave up");
        assertNotEquals(SessionEngine.State.ESTABLISHED, link.server.state(),
                "the server must never establish a session with the wrong PSK");
    }

    @Test
    void verifyInitiatorAcceptsOnlyTheCorrectPsk() throws Exception {
        ManualClock clock = new ManualClock(1_000L * SECOND);
        SessionEngine client = SessionEngine.client(clientConfig(), clock, null);
        byte[] datagram = client.nextDatagram();
        assertNotNull(datagram);

        PacketCodec.Decoded decoded = PacketCodec
                .forHandshake(HandshakePayload.maskKey(PSK))
                .decode(datagram, 0, datagram.length);
        assertEquals(PacketCodec.FLAG_HANDSHAKE, decoded.flags());
        byte[] message1 = decoded.plaintext();

        assertTrue(SessionEngine.verifyInitiator(PSK, message1),
                "the correct PSK must validate the authenticator");
        assertFalse(SessionEngine.verifyInitiator("wrong".getBytes(StandardCharsets.UTF_8), message1));
        assertFalse(SessionEngine.verifyInitiator(PSK, Arrays.copyOf(message1, 40)),
                "a truncated message must be rejected, not throw");
    }

    @Test
    void handshakeTimesOutWhenThePeerIsSilent() {
        ManualClock clock = new ManualClock(1_000L * SECOND);
        AtomicReference<Throwable> error = new AtomicReference<>();
        SessionEngine client = SessionEngine.client(clientConfig(), clock, new SessionListener() {
            @Override
            public void onError(Throwable cause) {
                error.set(cause);
            }
        });

        long start = clock.nanoTime();
        while (clock.nanoTime() - start < 30 * SECOND) {
            client.nextDatagram();
            long wait = client.nanosUntilNextEvent(clock.nanoTime());
            clock.advance(wait == Long.MAX_VALUE ? MS : Math.max(wait, MS));
        }
        assertEquals(SessionEngine.State.CLOSED, client.state());
        assertNotNull(error.get(), "a silent peer must eventually surface as an error");
    }

    // --- data transfer -----------------------------------------------------------

    @Test
    void smallMessageRoundTrips() {
        SimulatedLink link = link();
        assertTrue(link.runUntil(link::bothEstablished, 2 * SECOND));

        byte[] message = "hello minecraft".getBytes(StandardCharsets.UTF_8);
        link.clientWrites(message);
        assertTrue(link.runUntil(() -> link.serverReceivedCount() == message.length, 2 * SECOND));
        assertArrayEquals(message, link.serverReceived());
    }

    @Test
    void largeStreamIsDeliveredInOrderWithoutLoss() {
        int size = 512 * 1024;
        byte[] data = payload(size);
        SimulatedLink link = link();
        assertTrue(link.runUntil(link::bothEstablished, 2 * SECOND));

        link.clientWrites(data);
        assertTrue(link.runUntil(() -> link.serverReceivedCount() == size, 20 * SECOND),
                "only " + link.serverReceivedCount() + " of " + size + " bytes arrived");
        assertArrayEquals(data, link.serverReceived());
    }

    @Test
    void largeStreamIsDeliveredInOrderDespiteHeavyLoss() {
        int size = 256 * 1024;
        byte[] data = payload(size);
        SimulatedLink link = link().lossRate(0.2);
        assertTrue(link.runUntil(link::bothEstablished, 10 * SECOND));

        link.clientWrites(data);
        assertTrue(link.runUntil(() -> link.serverReceivedCount() == size, 60 * SECOND),
                "only " + link.serverReceivedCount() + " of " + size + " bytes arrived under 20% loss");

        assertArrayEquals(data, link.serverReceived(),
                "retransmission must restore exact byte order, not merely the byte count");
        SessionStats stats = link.client.stats();
        assertTrue(stats.packetsLost() > 0, "the test must have caused real loss");
        assertTrue(stats.packetsRetransmitted() > 0, "lost frames must have been re-sent");
    }

    @Test
    void reorderingDoesNotCorruptTheStream() {
        int size = 128 * 1024;
        byte[] data = payload(size);
        SimulatedLink link = link().reorderRate(0.5).lossRate(0.05);
        assertTrue(link.runUntil(link::bothEstablished, 10 * SECOND));

        link.clientWrites(data);
        assertTrue(link.runUntil(() -> link.serverReceivedCount() == size, 60 * SECOND),
                "only " + link.serverReceivedCount() + " of " + size + " bytes arrived");
        assertArrayEquals(data, link.serverReceived());
    }

    @Test
    void bidirectionalTransferWorks() {
        int size = 64 * 1024;
        byte[] upstream = payload(size);
        byte[] downstream = payload(size);
        SimulatedLink link = link().lossRate(0.05);
        assertTrue(link.runUntil(link::bothEstablished, 10 * SECOND));

        link.clientWrites(upstream);
        link.serverWrites(downstream);

        assertTrue(link.runUntil(() -> link.serverReceivedCount() == size
                && link.clientReceivedCount() == size, 30 * SECOND));
        assertArrayEquals(upstream, link.serverReceived());
        assertArrayEquals(downstream, link.clientReceived());
    }

    // --- flow control ------------------------------------------------------------

    @Test
    void senderStallsWhenTheReceiverDoesNotDrain() {
        int window = 8 * 1024;
        SessionConfig sendingClient = SessionConfig.client(PSK)
                .connectionId(CONNECTION_ID)
                .congestionController(SessionConfig.brutal(BPS))
                .sendBufferBytes(16 * 1024);
        SessionConfig receivingServer = SessionConfig.server(PSK)
                .congestionController(SessionConfig.brutal(BPS))
                .receiveWindowBytes(window);
        SimulatedLink link = new SimulatedLink(sendingClient, receivingServer).autoDrain(false);
        assertTrue(link.runUntil(link::bothEstablished, 2 * SECOND));

        link.clientWrites(payload(100 * 1024));
        link.run(3 * SECOND);

        assertTrue(link.server.readableBytes() <= window + 1500,
                "the receiver must never buffer more than its window plus one datagram, got "
                        + link.server.readableBytes());
        assertTrue(link.client.queuedBytes() > 0, "the sender must stall rather than overrun the peer");
        assertFalse(link.client.isWritable(), "and must report itself unwritable to the application");
    }

    @Test
    void senderResumesOnceTheReceiverDrains() {
        int size = 100 * 1024;
        int window = 8 * 1024;
        SessionConfig receivingServer = SessionConfig.server(PSK)
                .congestionController(SessionConfig.brutal(BPS))
                .receiveWindowBytes(window);
        SimulatedLink link = new SimulatedLink(clientConfig(), receivingServer).autoDrain(false);
        assertTrue(link.runUntil(link::bothEstablished, 2 * SECOND));

        byte[] data = payload(size);
        link.clientWrites(data);
        link.run(2 * SECOND);
        assertTrue(link.client.queuedBytes() > 0, "expected the sender to be stalled first");

        link.autoDrain(true);
        for (int i = 0; i < 400 && link.serverReceivedCount() < size; i++) {
            link.drainServer();
            link.run(25 * MS);
        }
        assertEquals(size, link.serverReceivedCount(), "credit updates must let the transfer finish");
        assertArrayEquals(data, link.serverReceived());
    }

    @Test
    void transferLargerThanTheWindowCompletes() {
        int window = 16 * 1024;
        int size = 200 * 1024;
        SessionConfig receivingServer = SessionConfig.server(PSK)
                .congestionController(SessionConfig.brutal(BPS))
                .receiveWindowBytes(window);
        SimulatedLink link = new SimulatedLink(clientConfig(), receivingServer);
        assertTrue(link.runUntil(link::bothEstablished, 2 * SECOND));

        byte[] data = payload(size);
        link.clientWrites(data);
        assertTrue(link.runUntil(() -> link.serverReceivedCount() == size, 60 * SECOND),
                "credit must be replenished repeatedly to move more than one window");
        assertArrayEquals(data, link.serverReceived());
    }

    // --- shutdown and stats ------------------------------------------------------

    @Test
    void closeReachesThePeer() {
        SimulatedLink link = link();
        assertTrue(link.runUntil(link::bothEstablished, 2 * SECOND));

        link.client.close(SessionEngine.CLOSE_REASON_LOCAL, "shutting down");
        assertTrue(link.runUntil(() -> link.serverClosedWith == SessionEngine.CLOSE_REASON_LOCAL, 5 * SECOND),
                "the CLOSE frame must reach the peer with its reason code intact");
        link.run(SECOND);
        assertEquals(SessionEngine.State.CLOSED, link.client.state(),
                "the closing side must finish closing even without an acknowledgement");
    }

    @Test
    void closeImmediatelyAfterLocalEstablishmentStillReachesThePeer() {
        SimulatedLink link = link();
        // Wait only for OUR side of the handshake, then close at once. The peer may not have
        // processed message 3 yet, and it discards data packets that arrive before its handshake
        // completes -- so the CLOSE has to wait for confirmation rather than being dropped.
        assertTrue(link.runUntil(() -> link.client.state() == SessionEngine.State.ESTABLISHED,
                2 * SECOND));
        link.client.close(SessionEngine.CLOSE_REASON_LOCAL, "bye");

        assertTrue(link.runUntil(() -> link.serverClosedWith == SessionEngine.CLOSE_REASON_LOCAL,
                        10 * SECOND),
                "the peer must still learn the session was closed");
        link.run(2 * SECOND);
        assertEquals(SessionEngine.State.CLOSED, link.client.state());
    }

    @Test
    void retransmittedMessage1DoesNotInvalidateTheHandshake() {
        // Regression: restarting the responder on a retransmitted message 1 generated a fresh
        // ephemeral key, so the message 3 the initiator was already computing could never decrypt.
        // The server killed the session and the client never learned why.
        ManualClock clock = new ManualClock(1_000L * SECOND);
        SessionEngine client = SessionEngine.client(clientConfig(), clock, null);
        SessionEngine server = SessionEngine.server(serverConfig(), CONNECTION_ID, clock, null);

        byte[] message1 = client.nextDatagram();
        assertNotNull(message1);
        server.onDatagramReceived(message1, 0, message1.length);
        byte[] message2 = server.nextDatagram();
        assertNotNull(message2);

        // Message 2 is lost, so the initiator's retry timer fires and it repeats message 1.
        clock.advance(300 * MS);
        byte[] message1Again = client.nextDatagram();
        assertArrayEquals(message1, message1Again);
        server.onDatagramReceived(message1Again, 0, message1Again.length);
        byte[] message2Again = server.nextDatagram();

        assertArrayEquals(message2, message2Again,
                "the responder must answer from cache, keeping one handshake in flight");
        assertEquals(SessionEngine.State.HANDSHAKE, server.state(), "and must not tear the session down");

        // Either copy of message 2 completes the initiator's side.
        client.onDatagramReceived(message2, 0, message2.length);
        assertEquals(SessionEngine.State.ESTABLISHED, client.state());

        byte[] message3 = client.nextDatagram();
        assertNotNull(message3, "message 3 must be sent immediately, not after a backoff delay");
        server.onDatagramReceived(message3, 0, message3.length);
        assertEquals(SessionEngine.State.ESTABLISHED, server.state());

        // And the session must actually carry data afterwards.
        byte[] payload = "after the awkward handshake".getBytes(StandardCharsets.UTF_8);
        client.write(payload, 0, payload.length);
        byte[] received = new byte[payload.length];
        for (int i = 0; i < 200; i++) {
            byte[] datagram = client.nextDatagram();
            if (datagram != null) {
                server.onDatagramReceived(datagram, 0, datagram.length);
            }
            byte[] reply = server.nextDatagram();
            if (reply != null) {
                client.onDatagramReceived(reply, 0, reply.length);
            }
            int n = server.read(received, 0, received.length);
            if (n == received.length) {
                break;
            }
            clock.advance(MS);
        }
        assertArrayEquals(payload, received);
    }

    @Test
    void statsDescribeTheTransfer() {
        int size = 32 * 1024;
        SimulatedLink link = link();
        assertTrue(link.runUntil(link::bothEstablished, 2 * SECOND));

        link.clientWrites(payload(size));
        assertTrue(link.runUntil(() -> link.serverReceivedCount() == size, 10 * SECOND));

        SessionStats client = link.client.stats();
        SessionStats server = link.server.stats();
        assertEquals(SessionEngine.State.ESTABLISHED, client.state());
        assertTrue(client.packetsSent() > 0);
        assertTrue(client.bytesSent() >= size);
        assertTrue(server.packetsReceived() > 0);
        assertEquals(0, client.bytesDelivered(), "the sender receives no stream data of its own");
        assertEquals(size, server.bytesDelivered());
        assertTrue(client.smoothedRttNanos() > 0, "an RTT must have been measured");
        assertEquals(0, client.queuedToSendBytes(), "everything queued must have been sent");
    }

    @Test
    void idleSessionGoesQuiet() {
        SimulatedLink link = link();
        assertTrue(link.runUntil(link::bothEstablished, 2 * SECOND));
        link.run(SECOND);

        long sentBefore = link.client.stats().packetsSent();
        link.run(2 * SECOND);
        long sentAfter = link.client.stats().packetsSent();

        assertTrue(sentAfter - sentBefore <= 2,
                "an idle session must not keep transmitting, sent " + (sentAfter - sentBefore) + " packets");
        assertEquals(Long.MAX_VALUE, link.client.nanosUntilNextEvent(link.clock.nanoTime()),
                "and must report that it has nothing scheduled");
    }

    @Test
    void writingAfterCloseIsRejected() {
        SimulatedLink link = link();
        assertTrue(link.runUntil(link::bothEstablished, 2 * SECOND));
        link.client.close(SessionEngine.CLOSE_REASON_LOCAL, "done");
        link.run(2 * SECOND);
        assertEquals(SessionEngine.State.CLOSED, link.client.state());

        byte[] data = new byte[16];
        assertThrows(IllegalStateException.class, () -> link.client.write(data, 0, data.length));
    }

    @Test
    void garbageDatagramsDoNotDisturbAnEstablishedSession() {
        SimulatedLink link = link();
        assertTrue(link.runUntil(link::bothEstablished, 2 * SECOND));

        int size = 1024;
        link.clientWrites(payload(size));
        assertTrue(link.runUntil(() -> link.serverReceivedCount() == size, 5 * SECOND));

        // Undecodable, wrong-length, and foreign-connection-id datagrams must all be dropped
        // silently rather than tearing the session down: on a hostile link stray UDP is normal.
        Random rng = new Random(99);
        for (int i = 0; i < 200; i++) {
            byte[] junk = new byte[rng.nextInt(1400)];
            rng.nextBytes(junk);
            link.server.onDatagramReceived(junk, 0, junk.length);
        }
        assertEquals(SessionEngine.State.ESTABLISHED, link.server.state());
        assertNull(link.serverError);

        // The session must still work afterwards.
        link.clientWrites(payload(size));
        assertTrue(link.runUntil(() -> link.serverReceivedCount() == 2 * size, 5 * SECOND));
    }
}
