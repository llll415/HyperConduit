package io.hyperconduit.sidecar;

import io.netty.util.AttributeKey;
import java.net.InetSocketAddress;

/** Attributes are set locally after authenticated bridge metadata, never by MC payload. */
public final class TunnelAttributes {
    private TunnelAttributes() {}
    public static final AttributeKey<InetSocketAddress> PEER = AttributeKey.valueOf("hyperconduit.peer");
    public static final AttributeKey<Boolean> TUNNEL = AttributeKey.valueOf("hyperconduit.tunnel");
    public static final AttributeKey<Long> CONNECTION_ID = AttributeKey.valueOf("hyperconduit.connectionId");
    public static final AttributeKey<Long> STREAM_ID = AttributeKey.valueOf("hyperconduit.streamId");
}
