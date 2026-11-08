package io.hyperconduit.sidecar;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the real bundled Go binary, not a protocol mock. */
class LiveSidecarTest {
    @TempDir Path directory;

    @Test void extractedServerAndClientCarryBytesAndStop() throws Exception {
        Path binary = new BinaryExtractor().extract(directory.resolve("bin"));
        try (ServerSocket backend = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))) {
            var echo = new Thread(() -> {
                try (Socket socket = backend.accept()) {
                    socket.setSoTimeout(5000);
                    byte[] bytes = socket.getInputStream().readNBytes(13);
                    socket.getOutputStream().write(bytes);
                    socket.getOutputStream().flush();
                } catch (Exception ignored) {}
            });
            echo.setDaemon(true);
            echo.start();
            Path serverConfig = write("server.json", Map.of(
                    "listen", "127.0.0.1:0", "target", "127.0.0.1:" + backend.getLocalPort(),
                    "stateDir", directory.resolve("state").toString()));
            try (SidecarProcess server = SidecarProcess.start(binary, "server", serverConfig, directory,
                    Duration.ofSeconds(8), Duration.ofSeconds(2))) {
                var ready = server.ready().get(10, TimeUnit.SECONDS);
                assertTrue(ready.fingerprint().startsWith("SHA256:"));
                Path clientConfig = write("client.json", Map.of(
                        "server", "127.0.0.1:" + ready.listen().getPort(),
                        "stateDir", directory.resolve("state").toString()));
                try (SidecarProcess client = SidecarProcess.start(binary, "client", clientConfig, directory,
                        Duration.ofSeconds(8), Duration.ofSeconds(2))) {
                    var local = client.ready().get(10, TimeUnit.SECONDS);
                    try (Socket socket = new Socket()) {
                        socket.connect(local.listen(), 3000);
                        socket.setSoTimeout(5000);
                        byte[] payload = "managed-bytes".getBytes(StandardCharsets.UTF_8);
                        socket.getOutputStream().write(payload);
                        socket.getOutputStream().flush();
                        assertArrayEquals(payload, socket.getInputStream().readNBytes(payload.length));
                    }
                    client.close();
                    assertEquals(0, client.exit().get(4, TimeUnit.SECONDS));
                    assertFalse(client.isAlive());
                }
                server.close();
                assertEquals(0, server.exit().get(4, TimeUnit.SECONDS));
            }
            echo.join(5000);
        }
        assertTrue(Files.exists(directory.resolve("state/known-servers.json")));
    }

    private Path write(String name, Map<String, String> values) throws Exception {
        Path path = directory.resolve(name);
        Files.writeString(path, new Gson().toJson(values));
        return path;
    }

    @Test void occupiedUdpPortFailsWithoutReady() throws Exception {
        Path binary = new BinaryExtractor().extract(directory.resolve("bin"));
        try (DatagramSocket occupied = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            Path config = write("occupied.json", Map.of("listen", "127.0.0.1:" + occupied.getLocalPort(),
                    "target", "127.0.0.1:25565", "stateDir", directory.resolve("state").toString()));
            try (SidecarProcess process = SidecarProcess.start(binary, "server", config, directory,
                    Duration.ofSeconds(8), Duration.ofSeconds(2))) {
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> process.ready().get(10, TimeUnit.SECONDS));
                assertInstanceOf(SidecarException.class, failure.getCause());
                assertEquals("transport", ((SidecarException) failure.getCause()).code());
                process.exit().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test void privateConfigurationIsRemovedAfterExit() throws Exception {
        Path configurations = directory.resolve("launch");
        SidecarProcess child = SidecarProcess.startConfigured(directory.resolve("bin"), configurations, "server",
                Map.of("target", "127.0.0.1:25565", "stateDir", directory.resolve("state").toString(), "token", "temporary-secret"),
                directory, Duration.ofSeconds(8), Duration.ofSeconds(2));
        try {
            child.ready().get(10, TimeUnit.SECONDS);
            try (var paths = Files.list(configurations)) { assertEquals(1, paths.count()); }
        } finally { child.close(); }
        child.exit().get(5, TimeUnit.SECONDS);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < until) {
            try (var paths = Files.list(configurations)) { if (paths.findAny().isEmpty()) return; }
            Thread.sleep(10);
        }
        fail("Private launch configuration remained after exit");
    }
}
