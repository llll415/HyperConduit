package io.hyperconduit.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-binary integration test: a managed server sidecar declares its capability, and a separate
 * probe process reads it without a config, token, trust store, identity, or backend dial.
 */
class LiveProbeTest {

    @TempDir Path directory;

    @Test
    void probeReadsCapabilityWithoutTrustOrBackend() throws Exception {
        Path probeWork = directory.resolve("probe-work");
        Files.createDirectories(probeWork);

        try (ServerSocket backend = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))) {
            backend.setSoTimeout(200);

            Map<String, Object> serverConfig = new HashMap<>();
            serverConfig.put("listen", "127.0.0.1:0");
            serverConfig.put("target", "127.0.0.1:" + backend.getLocalPort());
            serverConfig.put("stateDir", directory.resolve("server-state").toString());
            // Only the server's private config carries the token; the probe never sees it.
            serverConfig.put("token", "requires-token");

            ProbeProcess probe = null;
            try (SidecarProcess server = SidecarProcess.startConfigured(
                    directory.resolve("server-bin"), directory.resolve("server-launch"), "server",
                    serverConfig, directory, Duration.ofSeconds(15), Duration.ofSeconds(2))) {
                SidecarProcess.Ready ready = server.ready().get(15, TimeUnit.SECONDS);
                InetSocketAddress address = ready.listen();

                probe = ProbeProcess.startBundled(directory.resolve("probe-bin"), probeWork);
                probe.ready().get(10, TimeUnit.SECONDS);
                long pid = probe.pid();

                assertEquals("enabled", probe.probe(address).get(10, TimeUnit.SECONDS));
                ProbeProcess.Details details = probe.probeDetails(address).get(10, TimeUnit.SECONDS);
                assertEquals("enabled", details.state());
                assertTrue(details.declared());
                assertEquals(ProbeProcess.CAPABILITY_PROTOCOL, details.capability().protocol());
                assertTrue(details.capability().enabled());
                // The sidecar itself has no public TCP listener; Java passes the snapshot.
                assertFalse(details.capability().tcpAllowed());
                assertFalse(details.capability().disableBrutal());
                // tokenRequired is a boolean; the token value never appears on the wire.
                assertTrue(details.capability().tokenRequired());
                assertEquals("enabled", probe.probe(address).get(10, TimeUnit.SECONDS));
                assertEquals(pid, probe.pid(), "the probe process must be reused");
                assertTrue(probe.isAlive());

                // Cancel a request that is still waiting for an answer, then prove the same
                // probe process still serves a normal request.
                try (DatagramSocket silent = new DatagramSocket(
                        new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))) {
                    InetSocketAddress noAnswer = new InetSocketAddress(
                            InetAddress.getByName("127.0.0.1"), silent.getLocalPort());
                    CompletableFuture<String> canceled = probe.probe(noAnswer);
                    // Let the writer put the request on the wire, so cancellation exercises the
                    // real cancel command rather than only dropping a still-queued request.
                    Thread.sleep(100);
                    assertTrue(canceled.cancel(true));
                }
                assertEquals("enabled", probe.probe(address).get(10, TimeUnit.SECONDS),
                        "a canceled request must not poison the next probe");

                // Capability discovery is unauthenticated and must never open a backend stream.
                assertThrows(SocketTimeoutException.class, backend::accept);
            } finally {
                if (probe != null) probe.close();
            }

            assertEquals(0, probe.exit().get(5, TimeUnit.SECONDS).intValue());
            assertFalse(probe.isAlive());

            // The probe has no config, so it must not create TOFU or identity state anywhere.
            assertNoTrustOrIdentityFiles(directory.resolve("probe-work"));
            assertNoTrustOrIdentityFiles(directory.resolve("probe-bin"));

            // The server does persist its own identity in its state directory.
            assertTrue(Files.isRegularFile(directory.resolve("server-state/server-identity.json")),
                    "server identity should be persisted in the server state directory");
        }
    }

    private static void assertNoTrustOrIdentityFiles(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("known-servers"), () -> "unexpected trust file: " + path);
                assertFalse(name.contains("identity"), () -> "unexpected identity file: " + path);
            }
        }
    }
}
