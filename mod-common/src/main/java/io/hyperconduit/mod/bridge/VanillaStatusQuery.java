package io.hyperconduit.mod.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** One bounded vanilla status request. Never sends a login, token, or tunnel payload. */
public final class VanillaStatusQuery {
    private static final int MAX_PACKET = 1024 * 1024;
    private VanillaStatusQuery() {}

    public static String query(Socket socket, InetSocketAddress target, String host, int port) throws IOException {
        socket.connect(target, 1200);
        socket.setSoTimeout(1200);
        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        if (hostBytes.length > 1020) throw new IOException("Invalid status host");
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        writeVarInt(handshake, 0);
        writeVarInt(handshake, 767); // Minecraft 1.21.1
        writeVarInt(handshake, hostBytes.length);
        handshake.write(hostBytes);
        new DataOutputStream(handshake).writeShort(port);
        writeVarInt(handshake, 1); // STATUS, never LOGIN
        OutputStream out = socket.getOutputStream();
        writeVarInt(out, handshake.size());
        handshake.writeTo(out);
        out.write(new byte[] {1, 0}); // one-byte packet: status request
        out.flush();
        return readResponse(socket.getInputStream());
    }

    static String readResponse(InputStream in) throws IOException {
        int length = readVarInt(in);
        if (length < 2 || length > MAX_PACKET) throw new IOException("Invalid status packet size");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        ByteArrayInputStream packet = new ByteArrayInputStream(bytes);
        if (readVarInt(packet) != 0) throw new IOException("Unexpected status packet");
        int textLength = readVarInt(packet);
        if (textLength < 0 || textLength != packet.available()) throw new IOException("Invalid status JSON size");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(packet.readAllBytes())).toString();
    }

    static int readVarInt(InputStream in) throws IOException {
        int result = 0;
        for (int i = 0; i < 5; i++) {
            int value = in.read();
            if (value < 0) throw new EOFException();
            if (i == 4 && (value & 0xF0) != 0) throw new IOException("Invalid VarInt");
            result |= (value & 0x7F) << (7 * i);
            if ((value & 0x80) == 0) return result;
        }
        throw new IOException("Invalid VarInt");
    }

    static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
