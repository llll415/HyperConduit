package io.hyperconduit.mod.bridge;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class VanillaStatusQueryTest {
    @Test void sendsOnlyStatusAndReadsResponseFromRealSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var request = CompletableFuture.supplyAsync(() -> {
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(3000);
                    InputStream in = peer.getInputStream();
                    int size = VanillaStatusQuery.readVarInt(in);
                    var hello = new ByteArrayInputStream(in.readNBytes(size));
                    assertEquals(0, VanillaStatusQuery.readVarInt(hello));
                    assertEquals(767, VanillaStatusQuery.readVarInt(hello));
                    int hostLength = VanillaStatusQuery.readVarInt(hello);
                    assertEquals("example.test", new String(hello.readNBytes(hostLength), StandardCharsets.UTF_8));
                    assertEquals(25565, new DataInputStream(hello).readUnsignedShort());
                    assertEquals(1, VanillaStatusQuery.readVarInt(hello));
                    assertEquals(0, hello.available());
                    assertEquals(1, VanillaStatusQuery.readVarInt(in));
                    assertEquals(0, VanillaStatusQuery.readVarInt(in));
                    String body = "{\"description\":\"测试\"}";
                    peer.getOutputStream().write(response(body.getBytes(StandardCharsets.UTF_8)));
                    peer.getOutputStream().flush();
                    assertEquals(-1, in.read(), "caller must close without sending login or ping");
                    return body;
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
            String actual;
            try (Socket client = new Socket()) {
                actual = VanillaStatusQuery.query(client,
                        new InetSocketAddress(server.getInetAddress(), server.getLocalPort()), "example.test", 25565);
            }
            assertEquals(request.get(5, TimeUnit.SECONDS), actual);
        }
    }

    @Test void malformedPacketsAndUtf8AreRejected() throws Exception {
        assertThrows(IOException.class, () -> VanillaStatusQuery.readResponse(new ByteArrayInputStream(new byte[] {1, 0})));
        assertThrows(IOException.class, () -> VanillaStatusQuery.readResponse(new ByteArrayInputStream(new byte[] {2, 1, 0})));
        assertThrows(IOException.class, () -> VanillaStatusQuery.readResponse(new ByteArrayInputStream(response(new byte[] {(byte) 0xC0}))));
        ByteArrayOutputStream huge = new ByteArrayOutputStream();
        VanillaStatusQuery.writeVarInt(huge, 1024 * 1024 + 1);
        assertThrows(IOException.class, () -> VanillaStatusQuery.readResponse(new ByteArrayInputStream(huge.toByteArray())));
        assertThrows(IOException.class, () -> VanillaStatusQuery.readVarInt(new ByteArrayInputStream(
                new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80})));
    }

    private static byte[] response(byte[] json) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        VanillaStatusQuery.writeVarInt(body, 0);
        VanillaStatusQuery.writeVarInt(body, json.length);
        body.write(json);
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        VanillaStatusQuery.writeVarInt(packet, body.size());
        body.writeTo(packet);
        return packet.toByteArray();
    }
}
