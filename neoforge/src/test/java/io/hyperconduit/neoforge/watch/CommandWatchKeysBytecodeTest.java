package io.hyperconduit.neoforge.watch;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the production command path really uses the stable key/registry helpers, and that
 * the MC-facing helper reads the real {@code CommandSourceStack}/{@code ServerPlayer} APIs. This
 * keeps the pure registry tests from being disconnected "HashMap tests".
 */
class CommandWatchKeysBytecodeTest {

    private static final String EVENTS = "io/hyperconduit/neoforge/HyperConduitServerEvents";
    private static final String KEYS = "io/hyperconduit/neoforge/watch/CommandWatchKeys";
    private static final String REGISTRY = "io/hyperconduit/neoforge/watch/WatchRegistry";
    private static final String WATCH_KEY = "io/hyperconduit/neoforge/watch/WatchKey";
    private static final String CLEANUP = "io/hyperconduit/neoforge/watch/WatchCleanup";

    @Test
    void commandHandlersUseRegistryAndStableKeyFactory() {
        ClassInfo events = scan(EVENTS);

        assertEquals("L" + REGISTRY + ";", events.fields.get("WATCHERS"),
                "WATCHERS must not be a per-invocation CommandSourceStack set");

        Collection<Call> calls = events.allCalls();
        assertTrue(hasCall(calls, REGISTRY, "put"), "watch must replace the stack by stable key");
        assertTrue(hasCall(calls, REGISTRY, "remove"), "stop must remove by stable key");
        assertTrue(hasCall(calls, REGISTRY, "clear"), "server stop must clear subscriptions");
        assertTrue(hasCall(calls, REGISTRY, "removeIf"), "tick must prune invalid subscriptions");
        assertTrue(hasCall(calls, REGISTRY, "snapshot"), "tick must iterate a snapshot");

        long keyCalls = calls.stream()
                .filter(call -> call.owner.equals(KEYS) && call.name.equals("of"))
                .count();
        assertEquals(2, keyCalls, "both watch and watch stop must resolve the stable key");
    }

    @Test
    void stableKeyFactoryUsesPlayerUuidOrUnderlyingSourceIdentity() {
        ClassInfo keys = scan(KEYS);
        MethodInfo of = method(keys, "of");

        assertTrue(of.fields.stream().anyMatch(field -> field.owner.equals("net/minecraft/commands/CommandSourceStack")
                        && field.name.equals("source")),
                "identity must come from the real CommandSource, not the execute-as entity");
        assertTrue(of.typeChecks.contains("net/minecraft/server/level/ServerPlayer"),
                "only a ServerPlayer CommandSource is a player subscription");
        assertTrue(of.calls.stream().anyMatch(call -> call.name.equals("getUUID")
                        && call.desc.equals("()Ljava/util/UUID;")),
                "player identity must use UUID");
        assertFalse(hasCall(of.calls, "net/minecraft/commands/CommandSourceStack", "getPlayer"),
                "execute-as entity must not decide the subscription identity");
        assertTrue(hasCall(of.calls, WATCH_KEY, "player"));
        assertTrue(hasCall(of.calls, WATCH_KEY, "source"));
    }

    @Test
    void cleanupChecksLiveConnectionPermissionAndReconnect() {
        ClassInfo keys = scan(KEYS);
        MethodInfo drop = method(keys, "shouldDrop");

        assertTrue(drop.fields.stream().anyMatch(field -> field.owner.equals("net/minecraft/commands/CommandSourceStack")
                        && field.name.equals("source")),
                "cleanup must resolve the real player from CommandSourceStack.source");
        assertTrue(drop.typeChecks.contains("net/minecraft/server/level/ServerPlayer"),
                "player cleanup must check the CommandSource type");
        assertFalse(hasCall(drop.calls, "net/minecraft/commands/CommandSourceStack", "getPlayer"),
                "cleanup must not use the execute-as entity");
        assertTrue(drop.fields.stream().anyMatch(field -> field.name.equals("connection")
                        && field.owner.equals("net/minecraft/server/level/ServerPlayer")),
                "connection state must be read from the live ServerPlayer");
        assertTrue(hasCall(drop.calls, "net/minecraft/server/network/ServerGamePacketListenerImpl", "isAcceptingMessages"));
        assertTrue(drop.calls.stream().anyMatch(call -> call.name.equals("hasPermissions")
                        && call.desc.equals("(I)Z")),
                "permission loss must be checked against the live player");
        assertTrue(hasCall(drop.calls, "net/minecraft/server/MinecraftServer", "getPlayerList"));
        assertTrue(hasCall(drop.calls, "net/minecraft/server/players/PlayerList", "getPlayer"));
        assertTrue(drop.jumps.contains(Opcodes.IF_ACMPNE),
                "reconnect cleanup must compare the UUID lookup with the stored ServerPlayer");
        assertTrue(hasCall(drop.calls, CLEANUP, "shouldDrop"));

        MethodInfo invalidSource = method(keys, "isInvalidNonPlayerSource");
        assertTrue(hasCall(invalidSource.calls, "net/minecraft/world/level/BaseCommandBlock", "isValid"),
                "removed command blocks must not keep a subscription");
        assertTrue(hasCall(invalidSource.calls, "net/minecraft/world/entity/Entity", "isRemoved"));
    }

    @Test
    void serverStopAndTickUseTheRegistryLifecycle() {
        ClassInfo events = scan(EVENTS);

        MethodInfo stopping = method(events, "onServerStopping");
        assertTrue(hasCall(stopping.calls, REGISTRY, "clear"));

        MethodInfo tick = method(events, "updateWatchers");
        assertTrue(hasCall(tick.calls, REGISTRY, "removeIf"));
        assertTrue(hasCall(tick.calls, REGISTRY, "snapshot"));
    }

    private static MethodInfo method(ClassInfo info, String name) {
        return info.methods.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(name + "("))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing method " + name));
    }

    private static boolean hasCall(Collection<Call> calls, String owner, String name) {
        return calls.stream().anyMatch(call -> call.owner.equals(owner) && call.name.equals(name));
    }

    private static ClassInfo scan(String internalName) {
        byte[] bytes = readClass(internalName);
        ClassInfo info = new ClassInfo();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                info.fields.put(name, descriptor);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodInfo method = new MethodInfo();
                info.methods.put(name + descriptor, method);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String calledName,
                                                String calledDescriptor, boolean isInterface) {
                        method.calls.add(new Call(owner, calledName, calledDescriptor));
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName,
                                               String fieldDescriptor) {
                        method.fields.add(new FieldAccess(owner, fieldName, fieldDescriptor));
                    }

                    @Override
                    public void visitJumpInsn(int opcode, Label label) {
                        method.jumps.add(opcode);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.INSTANCEOF) {
                            method.typeChecks.add(type);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return info;
    }

    private static byte[] readClass(String internalName) {
        String resource = internalName + ".class";
        try (InputStream in = CommandWatchKeysBytecodeTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "missing class resource " + resource);
            return in.readAllBytes();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class ClassInfo {
        private final Map<String, String> fields = new LinkedHashMap<>();
        private final Map<String, MethodInfo> methods = new LinkedHashMap<>();

        private Collection<Call> allCalls() {
            Collection<Call> calls = new ArrayList<>();
            for (MethodInfo method : this.methods.values()) {
                calls.addAll(method.calls);
            }
            return calls;
        }
    }

    private static final class MethodInfo {
        private final Set<Call> calls = new LinkedHashSet<>();
        private final Set<FieldAccess> fields = new LinkedHashSet<>();
        private final Set<Integer> jumps = new LinkedHashSet<>();
        private final Set<String> typeChecks = new LinkedHashSet<>();
    }

    private record Call(String owner, String name, String desc) {
    }

    private record FieldAccess(String owner, String name, String desc) {
    }
}
