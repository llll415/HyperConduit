package io.hyperconduit.neoforge.client;

import io.hyperconduit.mod.bridge.ConnectionAttemptScope;
import io.hyperconduit.mod.bridge.ConnectionIdentity;
import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import io.hyperconduit.mod.mixin.ConnectionAddressMixin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class TemporaryTunnelHandshakeTest {
    @Test void temporaryTunnelPreservesOriginalSrvIdentityWhenGlobalSwitchIsOff() throws Exception {
        HyperConduitConfig previous = ConfigHolder.get();
        HyperConduitConfig global = new HyperConduitConfig();
        global.enabled = false;
        ConfigHolder.install(global);
        try {
            ConnectionAddressMixin connection = new ConnectionAddressMixin() {};
            ((ConnectionIdentity) connection).hyperconduit$endpoint("play.example.test:25565");
            HyperConduitConfig attempt = global.copy();
            attempt.enabled = true;
            try (var ignored = ConnectionAttemptScope.enter(attempt)) {
                assertEquals("play.example.test", invoke(connection, "hyperconduit$handshakeHost", String.class, "192.0.2.1"));
                assertEquals(25565, invoke(connection, "hyperconduit$handshakePort", int.class, 25570));
            }
            assertFalse(global.enabled);
            assertEquals("192.0.2.1", invoke(connection, "hyperconduit$handshakeHost", String.class, "192.0.2.1"));
        } finally { ConfigHolder.install(previous); }
    }

    @Test void temporaryTcpDoesNotUseTunnelHandshakeRewriteWhenGlobalSwitchIsOn() throws Exception {
        HyperConduitConfig previous = ConfigHolder.get();
        HyperConduitConfig global = new HyperConduitConfig();
        global.enabled = true;
        ConfigHolder.install(global);
        try {
            ConnectionAddressMixin connection = new ConnectionAddressMixin() {};
            connection.hyperconduit$endpoint("[2001:db8::1]:25565");
            HyperConduitConfig attempt = global.copy();
            attempt.enabled = false;
            try (var ignored = ConnectionAttemptScope.enter(attempt)) {
                assertEquals("resolved.example.test", invoke(connection, "hyperconduit$handshakeHost", String.class, "resolved.example.test"));
                assertEquals(25570, invoke(connection, "hyperconduit$handshakePort", int.class, 25570));
            }
            assertTrue(global.enabled);
        } finally { ConfigHolder.install(previous); }
    }

    private static Object invoke(ConnectionAddressMixin instance, String name, Class<?> type, Object value) throws Exception {
        Method method = ConnectionAddressMixin.class.getDeclaredMethod(name, type);
        method.setAccessible(true);
        return method.invoke(instance, value);
    }
}
