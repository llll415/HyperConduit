package io.hyperconduit.mod.bridge;

import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import static org.junit.jupiter.api.Assertions.*;

class ResolvedEndpointTest {
    @Test void preservesUserNameAndConsumesOnlyOnce() {
        InetSocketAddress resolved = new InetSocketAddress("127.0.0.1",25565);
        ResolvedEndpoint.record("mc.example:25565",resolved);
        assertEquals("mc.example:25565",ResolvedEndpoint.consume(resolved));
        assertNull(ResolvedEndpoint.consume(resolved));
    }
    @Test void unrelatedTargetDoesNotReuseIdentity() {
        ResolvedEndpoint.record("mc.example:25565",new InetSocketAddress("127.0.0.1",25565));
        assertNull(ResolvedEndpoint.consume(new InetSocketAddress("127.0.0.1",25566)));
        assertNull(ResolvedEndpoint.consume(new InetSocketAddress("127.0.0.1",25565)));
    }
}
