package io.hyperconduit.sidecar;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.io.StringReader;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Gates a dedicated private TCP listener before installing Minecraft's pipeline. */
public final class BridgeInitializer extends ChannelInitializer<Channel> {
    private final String token;
    private final ChannelHandler minecraftInitializer;

    public BridgeInitializer(String token, ChannelHandler minecraftInitializer) {
        if (token == null || !token.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid bridge credential");
        this.token = token;
        this.minecraftInitializer = minecraftInitializer;
    }

    @Override protected void initChannel(Channel channel) {
        channel.config().setAutoRead(true);
        channel.pipeline().addLast(new Gate());
    }

    private final class Gate extends ByteToMessageDecoder {
        private ScheduledFuture<?> timeout;
        private boolean accepted;

        @Override public void channelActive(ChannelHandlerContext ctx) {
            // Do not deliver channelActive to Minecraft until the preface is trusted.
            timeout = ctx.executor().schedule(() -> ctx.close(), 5, TimeUnit.SECONDS);
        }

        @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (accepted || in.readableBytes() < 12) return;
            int offset = in.readerIndex();
            if (!in.toString(offset, 8, StandardCharsets.US_ASCII).equals("HCMETA01")) { ctx.close(); return; }
            long size = in.getUnsignedInt(offset + 8);
            if (size < 1 || size > 1024) { ctx.close(); return; }
            if (in.readableBytes() < size + 12) return;
            try {
                JsonObject meta = new JsonObject();
                JsonReader reader = new JsonReader(new StringReader(in.toString(offset + 12, (int) size, StandardCharsets.UTF_8)));
                reader.setLenient(false);
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (meta.has(key) || !Set.of("token", "peer", "connectionId", "streamId").contains(key)) { ctx.close(); return; }
                    if (key.equals("connectionId") || key.equals("streamId")) {
                        if (reader.peek() != JsonToken.NUMBER) { ctx.close(); return; }
                        meta.addProperty(key, Long.parseLong(reader.nextString()));
                    } else {
                        if (reader.peek() != JsonToken.STRING) { ctx.close(); return; }
                        meta.addProperty(key, reader.nextString());
                    }
                }
                reader.endObject();
                if (reader.peek() != JsonToken.END_DOCUMENT || meta.size() != 4) { ctx.close(); return; }
                String supplied = meta.get("token").getAsString();
                if (!supplied.matches("[0-9a-f]{64}") || !MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII))) {
                    ctx.close(); return;
                }
                String peer = meta.get("peer").getAsString();
                int colon = peer.lastIndexOf(':');
                if (colon < 1) { ctx.close(); return; }
                String host = peer.substring(0, colon);
                if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
                if (!host.matches("[0-9a-fA-F:.]+")) { ctx.close(); return; }
                if (!host.contains(":")) {
                    String[] parts = host.split("\\.", -1);
                    if (parts.length != 4) { ctx.close(); return; }
                    for (String part : parts) {
                        if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) { ctx.close(); return; }
                    }
                }
                InetAddress ip = InetAddress.getByName(host);
                if (ip.isAnyLocalAddress()) { ctx.close(); return; }
                int port = Integer.parseInt(peer.substring(colon + 1));
                long connectionId = meta.get("connectionId").getAsLong();
                long streamId = meta.get("streamId").getAsLong();
                if (port < 1 || port > 65535 || connectionId <= 0 || streamId < 0) { ctx.close(); return; }
                ctx.channel().attr(TunnelAttributes.PEER).set(new InetSocketAddress(ip, port));
                ctx.channel().attr(TunnelAttributes.TUNNEL).set(true);
                ctx.channel().attr(TunnelAttributes.CONNECTION_ID).set(connectionId);
                ctx.channel().attr(TunnelAttributes.STREAM_ID).set(streamId);
                in.skipBytes((int) size + 12);
                accepted = true;
                if (timeout != null) timeout.cancel(false);
                ctx.pipeline().addLast(minecraftInitializer);
                ctx.fireChannelActive();
                ctx.pipeline().remove(this); // ByteToMessageDecoder forwards any coalesced MC bytes.
            } catch (Exception invalid) { ctx.close(); }
        }

        @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (timeout != null) timeout.cancel(false);
            super.channelInactive(ctx);
        }

        @Override public void handlerRemoved0(ChannelHandlerContext ctx) {
            if (timeout != null) timeout.cancel(false);
        }
    }
}
