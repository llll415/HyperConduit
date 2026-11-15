package io.hyperconduit.velocity.watch;

import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchServiceTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void registryDeduplicatesPlayerUuidAndConsoleKeys() {
        WatchRegistry<String> registry = new WatchRegistry<>();
        assertTrue(registry.put(WatchRegistry.Key.player(ALICE), "first"));
        assertFalse(registry.put(WatchRegistry.Key.player(ALICE), "second"));
        assertEquals(1, registry.size());
        assertEquals(List.of("second"), registry.snapshot());

        assertTrue(registry.put(WatchRegistry.Key.consoleKey(), "console"));
        assertEquals(2, registry.size());
        assertTrue(registry.remove(WatchRegistry.Key.player(ALICE)));
        assertFalse(registry.isEmpty());
        registry.clear();
        assertTrue(registry.isEmpty());
    }

    @Test
    void repeatedAddSendsOneCopyAndInvalidTargetsAreRemoved() {
        WatchService service = new WatchService(null, null, () -> List.of("one", "two"));
        List<String> sent = new ArrayList<>();
        FakeTarget target = new FakeTarget(sent);
        WatchRegistry.Key key = WatchRegistry.Key.player(ALICE);

        assertTrue(service.add(key, target));
        assertFalse(service.add(key, target));
        service.tick();
        assertEquals(List.of("one", "two"), sent);
        assertEquals(1, service.size());

        target.valid = false;
        service.tick();
        assertEquals(0, service.size());
        assertEquals(List.of("one", "two"), sent, "invalid target received another tick");
    }

    @Test
    void emptyRegistryDoesNotCollectStatusLines() {
        AtomicBoolean called = new AtomicBoolean();
        WatchService service = new WatchService(null, null, () -> {
            called.set(true);
            return List.of("line");
        });
        service.tick();
        assertFalse(called.get());
    }

    @Test
    void failedSendRemovesOnlyThatSubscription() {
        WatchService service = new WatchService(null, null, () -> List.of("line"));
        List<String> sent = new ArrayList<>();
        FakeTarget good = new FakeTarget(sent);
        FakeTarget failing = new FakeTarget(sent);
        failing.fail = true;
        service.add(WatchRegistry.Key.player(ALICE), good);
        service.add(WatchRegistry.Key.consoleKey(), failing);

        service.tick();

        assertEquals(1, service.size());
        assertEquals(List.of("line"), sent);
    }

    @Test
    void playerLeavesWhenDisconnectedOrPermissionIsLost() {
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean permitted = new AtomicBoolean(true);
        List<String> messages = new ArrayList<>();
        Player player = playerProxy(ALICE, "Alice", active, permitted, messages);
        ProxyServer server = serverProxy(new AtomicBoolean(), true, player);
        WatchService service = new WatchService(server, null, () -> List.of("status"));

        service.add(player);
        service.tick();
        assertEquals(List.of("status"), messages);
        assertEquals(1, service.size());

        permitted.set(false);
        service.tick();
        assertEquals(0, service.size());

        permitted.set(true);
        active.set(false);
        service.add(player);
        service.tick();
        assertEquals(0, service.size());
    }

    @Test
    void playerLeavesWhenProxyNoLongerListsThem() {
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean permitted = new AtomicBoolean(true);
        List<String> messages = new ArrayList<>();
        Player player = playerProxy(ALICE, "Alice", active, permitted, messages);
        ProxyServer server = serverProxy(new AtomicBoolean(), false, player);
        WatchService service = new WatchService(server, null, () -> List.of("status"));

        service.add(player);
        service.tick();

        assertEquals(0, service.size());
        assertTrue(messages.isEmpty());
    }

    @Test
    void proxyShutdownAndCloseClearSubscriptions() {
        AtomicBoolean shuttingDown = new AtomicBoolean();
        List<String> messages = new ArrayList<>();
        ConsoleCommandSource console = consoleProxy(messages);
        ProxyServer server = serverProxy(shuttingDown, true, null);
        WatchService service = new WatchService(server, null, () -> List.of("status"));

        service.add(console);
        service.tick();
        assertEquals(List.of("status"), messages);

        shuttingDown.set(true);
        service.tick();
        assertEquals(0, service.size());

        shuttingDown.set(false);
        service.add(console);
        service.close();
        assertEquals(0, service.size());
        service.tick();
        assertEquals(List.of("status"), messages);
    }

    private static final class FakeTarget implements WatchService.Target {
        private final List<String> sent;
        boolean valid = true;
        boolean fail;

        FakeTarget(List<String> sent) {
            this.sent = sent;
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public void send(String line) {
            if (fail) {
                throw new IllegalStateException("send failed");
            }
            sent.add(line);
        }
    }

    private static Player playerProxy(UUID uuid, String name, AtomicBoolean active,
                                      AtomicBoolean permitted, List<String> messages) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getUsername" -> name;
                    case "isActive" -> active.get();
                    case "hasPermission" -> permitted.get();
                    case "sendPlainMessage" -> {
                        messages.add((String) args[0]);
                        yield null;
                    }
                    case "toString" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static ConsoleCommandSource consoleProxy(List<String> messages) {
        return (ConsoleCommandSource) Proxy.newProxyInstance(
                ConsoleCommandSource.class.getClassLoader(),
                new Class<?>[] {ConsoleCommandSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "sendPlainMessage" -> {
                        messages.add((String) args[0]);
                        yield null;
                    }
                    case "hasPermission" -> true;
                    case "toString" -> "console";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static ProxyServer serverProxy(AtomicBoolean shuttingDown, boolean present,
                                           Player player) {
        return (ProxyServer) Proxy.newProxyInstance(ProxyServer.class.getClassLoader(),
                new Class<?>[] {ProxyServer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isShuttingDown" -> shuttingDown.get();
                    case "getPlayer" -> present ? Optional.ofNullable(player) : Optional.empty();
                    case "getAllPlayers" -> present && player != null ? List.of(player) : List.of();
                    case "toString" -> "proxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}