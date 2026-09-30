package io.hyperconduit.sim;

import io.hyperconduit.conn.SessionConfig;
import io.hyperconduit.conn.SessionEngine;
import io.hyperconduit.conn.SessionListener;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * Two {@link SessionEngine}s joined by a simulated link with configurable latency, loss and
 * reordering, driven by a {@link ManualClock}.
 *
 * <p>This is how the reliability logic is validated and how congestion controllers are compared:
 * fully deterministic, no sockets, and it reproduces in milliseconds the peak-hour conditions the
 * transport exists for. Because it drives the exact same engine code that a real UDP driver drives,
 * results here carry over to the network — only the loss model is synthetic.
 *
 * <p>Fields are public because this is a test and measurement utility, not a production API; terse
 * access at the call site matters more than encapsulation here.
 */
public final class SimulatedLink {

    public static final long MS = 1_000_000L;
    public static final long SECOND = 1_000_000_000L;

    /** Start away from zero so no timer or sampling slot can accidentally coincide with "unset". */
    private static final long CLOCK_START = 1_000L * SECOND;

    public final ManualClock clock = new ManualClock(CLOCK_START);
    public final SessionEngine client;
    public final SessionEngine server;

    public long dropped;
    public long delivered;
    public long ticks;
    public Throwable clientError;
    public Throwable serverError;
    public int clientClosedWith = -1;
    public int serverClosedWith = -1;

    private final List<Datagram> inFlight = new ArrayList<>();
    private final ByteArrayOutputStream clientReceived = new ByteArrayOutputStream();
    private final ByteArrayOutputStream serverReceived = new ByteArrayOutputStream();
    private final Random rng = new Random(DEFAULT_SEED);

    /** Seed used unless {@link #seed(long)} says otherwise. */
    public static final long DEFAULT_SEED = 0x5EEDL;

    private double lossRate;
    private double reorderRate;
    private int lossEveryNth;
    private long clientEmitCounter;
    private long serverEmitCounter;
    private long latencyNanos = 20 * MS;
    private long stepNanos = MS;
    private int maxDatagramsPerTick = 4096;
    private boolean autoDrain = true;

    public SimulatedLink(SessionConfig clientConfig, SessionConfig serverConfig) {
        this.client = SessionEngine.client(clientConfig, clock, new Listener(true));
        this.server = SessionEngine.server(serverConfig, clientConfig.connectionId(), clock,
                new Listener(false));
    }

    public SimulatedLink lossRate(double lossRate) {
        this.lossRate = lossRate;
        return this;
    }

    /**
     * Drops every n-th datagram per direction instead of using a random rate. Guarantees that a run
     * actually exercises retransmission even when only a handful of datagrams are sent, which a
     * probabilistic rate cannot promise for a three-message handshake.
     */
    public SimulatedLink lossEveryNth(int everyNth) {
        this.lossEveryNth = everyNth;
        return this;
    }

    public SimulatedLink reorderRate(double reorderRate) {
        this.reorderRate = reorderRate;
        return this;
    }

    public SimulatedLink latencyNanos(long latencyNanos) {
        this.latencyNanos = latencyNanos;
        return this;
    }

    public SimulatedLink stepNanos(long stepNanos) {
        this.stepNanos = stepNanos;
        return this;
    }

    /**
     * With auto-drain off, received bytes stay in the reassembly buffer instead of being handed to
     * the application, which is how flow-control backpressure is exercised.
     */
    public SimulatedLink autoDrain(boolean autoDrain) {
        this.autoDrain = autoDrain;
        return this;
    }

    /**
     * Sets the loss/reordering seed. A single run's numbers are noisy — where in a transfer the
     * losses land changes the result a lot — so measurements should be repeated across seeds and
     * averaged rather than read off one run.
     */
    public SimulatedLink seed(long seed) {
        this.rng.setSeed(seed);
        return this;
    }

    /** Advances the simulated clock until {@code condition} holds or {@code maxNanos} elapses. */
    public boolean runUntil(BooleanSupplier condition, long maxNanos) {
        long deadline = clock.nanoTime() + maxNanos;
        while (clock.nanoTime() < deadline) {
            tick();
            if (condition.getAsBoolean()) {
                return true;
            }
            clock.advance(stepNanos);
            ticks++;
        }
        return condition.getAsBoolean();
    }

    public void run(long durationNanos) {
        runUntil(() -> false, durationNanos);
    }

    public boolean bothEstablished() {
        return client.state() == SessionEngine.State.ESTABLISHED
                && server.state() == SessionEngine.State.ESTABLISHED;
    }

    public void clientWrites(byte[] data) {
        client.write(data, 0, data.length);
    }

    public void serverWrites(byte[] data) {
        server.write(data, 0, data.length);
    }

    public byte[] clientReceived() {
        return clientReceived.toByteArray();
    }

    public byte[] serverReceived() {
        return serverReceived.toByteArray();
    }

    public int clientReceivedCount() {
        return clientReceived.size();
    }

    public int serverReceivedCount() {
        return serverReceived.size();
    }

    /** Hands buffered bytes to the application side, as an auto-draining listener would. */
    public void drainClient() {
        drain(client, clientReceived);
    }

    public void drainServer() {
        drain(server, serverReceived);
    }

    public int inFlightCount() {
        return inFlight.size();
    }

    /** Simulated elapsed time since the link was created. */
    public long elapsedNanos() {
        return clock.nanoTime() - CLOCK_START;
    }

    private void tick() {
        deliverDue();
        emit(client, true);
        emit(server, false);
        deliverDue();
    }

    private void emit(SessionEngine from, boolean towardsServer) {
        for (int i = 0; i < maxDatagramsPerTick; i++) {
            byte[] datagram = from.nextDatagram();
            if (datagram == null) {
                return;
            }
            if (lossEveryNth > 0) {
                // Counted per direction: a single shared counter would drop every datagram of
                // whichever side happens to emit on even counts, which no real link does.
                long n = towardsServer ? ++clientEmitCounter : ++serverEmitCounter;
                if (n % lossEveryNth == 0) {
                    dropped++;
                    continue;
                }
            } else if (rng.nextDouble() < lossRate) {
                dropped++;
                continue;
            }
            long arriveAt = clock.nanoTime() + latencyNanos;
            if (reorderRate > 0 && rng.nextDouble() < reorderRate) {
                arriveAt += (rng.nextLong() & 0xFFFF) * 100_000L; // up to ~6.5ms of extra jitter
            }
            inFlight.add(new Datagram(arriveAt, datagram, towardsServer));
        }
    }

    private void deliverDue() {
        if (inFlight.isEmpty()) {
            return;
        }
        inFlight.sort(Comparator.comparingLong(Datagram::arriveAtNanos));
        long now = clock.nanoTime();
        for (int i = 0; i < inFlight.size(); ) {
            Datagram datagram = inFlight.get(i);
            if (datagram.arriveAtNanos() > now) {
                break;
            }
            inFlight.remove(i);
            delivered++;
            SessionEngine target = datagram.towardsServer() ? server : client;
            target.onDatagramReceived(datagram.data(), 0, datagram.data().length);
        }
    }

    private void drain(SessionEngine from, ByteArrayOutputStream into) {
        byte[] buffer = new byte[64 * 1024];
        while (true) {
            int n = from.read(buffer, 0, buffer.length);
            if (n <= 0) {
                return;
            }
            into.write(buffer, 0, n);
        }
    }

    private record Datagram(long arriveAtNanos, byte[] data, boolean towardsServer) {
    }

    private final class Listener implements SessionListener {

        private final boolean isClient;

        Listener(boolean isClient) {
            this.isClient = isClient;
        }

        @Override
        public void onReadable() {
            if (autoDrain) {
                drain(isClient ? client : server, isClient ? clientReceived : serverReceived);
            }
        }

        @Override
        public void onError(Throwable cause) {
            if (isClient) {
                clientError = cause;
            } else {
                serverError = cause;
            }
        }

        @Override
        public void onClosed(int reasonCode, String message) {
            if (isClient) {
                clientClosedWith = reasonCode;
            } else {
                serverClosedWith = reasonCode;
            }
        }
    }
}
