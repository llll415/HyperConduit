package io.hyperconduit.sidecar;

import com.google.gson.Gson;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises the real bundled Go sidecar end to end: a server and a client
 * process bridge one TCP connection to a local echo backend while both expose
 * the opt-in managed stats stream. No Minecraft client, UI, or screenshot is
 * involved.
 *
 * <p>The two cases cover the two negotiation outcomes for the same configured
 * {@code reno} controller: {@code disableBrutal=true} keeps the configured
 * controller on both sides, while the default false lets the symmetric
 * {@code upMbps}/{@code downMbps} values select Brutal with a 1.25 MB/s target.
 */
class LiveTelemetryTest {

    private static final long TELEMETRY_TIMEOUT_MILLIS = 6_000;
    private static final long PROCESS_TIMEOUT_SECONDS = 10;
    private static final long EXIT_TIMEOUT_SECONDS = 4;
    private static final int PAYLOAD_BYTES = 1024 * 1024;

    @TempDir Path directory;

    static Stream<Arguments> congestionProfiles() {
        return Stream.of(
                Arguments.of("disableBrutal keeps configured reno", true, "reno", 0L),
                Arguments.of("bandwidth negotiation selects brutal", false, "brutal", 1_250_000L));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("congestionProfiles")
    void realServerAndClientExposeLiveTelemetry(String label, boolean disableBrutal,
                                                String expectedCongestion, long expectedTarget)
            throws Exception {
        Path binary = new BinaryExtractor().extract(directory.resolve("bin"));
        try (ServerSocket backend = new ServerSocket(
                0, 16, InetAddress.getByName("127.0.0.1"))) {
            Thread echo = startEchoLoop(backend);
            try {
                Path serverConfig = write("server.json", Map.of(
                        "listen", "127.0.0.1:0",
                        "target", "127.0.0.1:" + backend.getLocalPort(),
                        "stateDir", directory.resolve("server-state").toString(),
                        "congestion", "reno",
                        "disableBrutal", disableBrutal));
                try (SidecarProcess server = SidecarProcess.start(binary, "server", serverConfig,
                        directory, Duration.ofSeconds(8), Duration.ofSeconds(2))) {
                    SidecarProcess.Ready serverReady =
                            server.ready().get(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    assertTrue(serverReady.fingerprint().startsWith("SHA256:"));

                    Path clientConfig = write("client.json", Map.of(
                            "server", "127.0.0.1:" + serverReady.listen().getPort(),
                            "stateDir", directory.resolve("client-state").toString(),
                            "congestion", "reno",
                            "upMbps", 10,
                            "downMbps", 10));
                    try (SidecarProcess client = SidecarProcess.start(binary, "client", clientConfig,
                            directory, Duration.ofSeconds(8), Duration.ofSeconds(2))) {
                        SidecarProcess.Ready clientReady =
                                client.ready().get(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                        server.enableTelemetry();
                        client.enableTelemetry();

                        echoOneMiB(clientReady.listen());

                        TelemetrySnapshot serverSnapshot = awaitRatedConnection(
                                server, "server", TELEMETRY_TIMEOUT_MILLIS);
                        TelemetrySnapshot clientSnapshot = awaitRatedConnection(
                                client, "client", TELEMETRY_TIMEOUT_MILLIS);
                        assertConnection(serverSnapshot, "server", expectedCongestion, expectedTarget);
                        assertConnection(clientSnapshot, "client", expectedCongestion, expectedTarget);
                        // IDs are assigned independently on each side and must not be assumed equal.

                        long serverSequenceBeforeClose = serverSnapshot.sequence();
                        client.close();
                        assertNull(client.telemetry(),
                                "a closed client must not expose telemetry");
                        assertEquals(0, client.exit().get(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

                        TelemetrySnapshot removed = awaitConnectionRemoved(
                                server, serverSequenceBeforeClose, "server",
                                TELEMETRY_TIMEOUT_MILLIS);
                        assertTrue(removed.available(), "removal snapshot must be live");
                        assertTrue(removed.connections().isEmpty(),
                                "server must not retain a stale connection after the client exits");

                        server.close();
                        assertNull(server.telemetry(),
                                "a stopped server must not expose telemetry");
                        assertEquals(0, server.exit().get(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    }
                }
            } finally {
                backend.close();
                echo.join(5_000);
            }
        }
    }

    private Thread startEchoLoop(ServerSocket backend) {
        return Thread.ofPlatform().daemon().name("live-telemetry-echo").start(() -> {
            try (Socket socket = backend.accept()) {
                socket.setSoTimeout(15_000);
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    output.flush();
                }
            } catch (IOException ignored) {
                // A failed backend read surfaces as a socket timeout in echoOneMiB.
            }
        });
    }

    private static void echoOneMiB(InetSocketAddress listen) throws Exception {
        byte[] payload = new byte[PAYLOAD_BYTES];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31 + 7);
        try (Socket socket = new Socket()) {
            socket.connect(listen, 3_000);
            socket.setSoTimeout(15_000);
            Thread writer = Thread.ofVirtual().name("live-telemetry-writer").start(() -> {
                try {
                    socket.getOutputStream().write(payload);
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                    // The reader below observes the resulting timeout or short read.
                }
            });
            try {
                byte[] echoed = socket.getInputStream().readNBytes(payload.length);
                assertArrayEquals(payload, echoed, "echoed payload mismatch");
            } finally {
                writer.join(5_000);
            }
        }
    }

    private static TelemetrySnapshot awaitRatedConnection(SidecarProcess process, String role,
                                                          long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        TelemetrySnapshot last = null;
        while (System.nanoTime() < deadline) {
            TelemetrySnapshot snapshot = process.telemetry();
            if (snapshot != null && snapshot.available()) {
                last = snapshot;
                if (snapshot.connections().size() == 1) {
                    TelemetrySnapshot.Connection connection =
                            snapshot.connections().values().iterator().next();
                    if (connection.ratesAvailable()
                            && connection.bytesSent() > 0
                            && connection.bytesReceived() > 0
                            && connection.smoothedRttNanos() > 0) {
                        return snapshot;
                    }
                }
            }
            Thread.sleep(50);
        }
        fail(role + " telemetry did not expose one rated connection within "
                + timeoutMillis + " ms; last=" + last);
        return null;
    }

    private static TelemetrySnapshot awaitConnectionRemoved(SidecarProcess process,
                                                            long afterSequence, String role,
                                                            long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        TelemetrySnapshot last = null;
        while (System.nanoTime() < deadline) {
            TelemetrySnapshot snapshot = process.telemetry();
            if (snapshot != null && snapshot.available()) {
                last = snapshot;
                if (snapshot.sequence() > afterSequence && snapshot.connections().isEmpty()) {
                    return snapshot;
                }
            }
            Thread.sleep(50);
        }
        fail(role + " telemetry did not remove the closed connection within "
                + timeoutMillis + " ms; last=" + last);
        return null;
    }

    private static void assertConnection(TelemetrySnapshot snapshot, String role,
                                         String expectedCongestion, long expectedTarget) {
        assertNotNull(snapshot, role + " telemetry must be present");
        assertTrue(snapshot.available(), role + " snapshot must be live");
        assertFalse(snapshot.truncated(), role + " snapshot must not be truncated");
        assertEquals(1, snapshot.connections().size(),
                role + " must report exactly one QUIC connection");

        TelemetrySnapshot.Connection connection =
                snapshot.connections().values().iterator().next();
        assertTrue(connection.id() > 0, role + " connection id must be positive");
        assertEquals(expectedCongestion, connection.congestion(),
                role + " must report its actual local congestion controller");
        assertEquals(expectedTarget, connection.txTargetBytesPerSecond(),
                role + " must report the negotiated send target");
        assertTrue(connection.bytesSent() > 0, role + " bytesSent must become positive");
        assertTrue(connection.bytesReceived() > 0,
                role + " bytesReceived must become positive");
        assertTrue(connection.smoothedRttNanos() > 0,
                role + " smoothed RTT must become positive");
        assertTrue(connection.ratesAvailable(), role + " rates must become available");
        assertTrue(Double.isFinite(connection.sentBytesPerSecond()),
                role + " sent rate must be finite");
        assertTrue(Double.isFinite(connection.receivedBytesPerSecond()),
                role + " received rate must be finite");
        assertTrue(connection.sentBytesPerSecond() >= 0,
                role + " sent rate must not be negative");
        assertTrue(connection.receivedBytesPerSecond() >= 0,
                role + " received rate must not be negative");
    }

    private Path write(String name, Map<String, ?> values) throws Exception {
        Path path = directory.resolve(name);
        Files.writeString(path, new Gson().toJson(values));
        return path;
    }
}
