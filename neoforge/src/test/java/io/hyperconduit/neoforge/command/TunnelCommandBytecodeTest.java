package io.hyperconduit.neoforge.command;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the production command path is wired into the root command, keeps permission level
 * 2, and persists edits through {@link io.hyperconduit.mod.config.ConfigHolder#update}.
 */
class TunnelCommandBytecodeTest {

    private static final String EVENTS = "io/hyperconduit/neoforge/HyperConduitServerEvents";
    private static final String COMMANDS = "io/hyperconduit/neoforge/command/TunnelConfigCommands";
    private static final String PROCESS_COMMANDS = "io/hyperconduit/neoforge/command/TunnelProcessCommands";
    private static final String CONFIG_HOLDER = "io/hyperconduit/mod/config/ConfigHolder";
    private static final String SOURCE = "net/minecraft/commands/CommandSourceStack";

    @Test
    void rootCommandWiresTheTunnelSubtreeAndKeepsPermissionLevelTwo() {
        ClassInfo events = scan(EVENTS);

        assertTrue(hasCall(events.allCalls(), COMMANDS, "branch"),
                "the root command must add TunnelConfigCommands.branch()");
        assertTrue(hasCall(events.allCalls(), PROCESS_COMMANDS, "status"),
                "tunnel status must be added to the same subtree");
        assertTrue(hasCall(events.allCalls(), PROCESS_COMMANDS, "restart"),
                "tunnel restart must be added to the same subtree");

        boolean levelTwoGate = events.methods.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("lambda$registerCommands$"))
                .map(Map.Entry::getValue)
                .anyMatch(method -> hasCall(method.calls, SOURCE, "hasPermission")
                        && method.opcodes.contains(Opcodes.ICONST_2));
        assertTrue(levelTwoGate, "the root command must still require permission level 2");
    }

    @Test
    void editsUseTheReadModifyWriteHolderAndTheAdminHelper() {
        ClassInfo commands = scan(COMMANDS);

        assertTrue(hasCall(commands.allCalls(), CONFIG_HOLDER, "update"),
                "every edit must go through ConfigHolder.update");
        assertEquals(Integer.valueOf(2), commands.fieldValues.get("REQUIRED_PERMISSION"),
                "the shared admin helper must stay at permission level 2");

        MethodInfo ensureAdmin = method(commands, "ensureAdmin");
        assertTrue(hasCall(ensureAdmin.calls, SOURCE, "hasPermission"));
        assertTrue(hasCall(ensureAdmin.calls, SOURCE, "sendFailure"));
    }

    @Test
    void subtreeContainsConfigAndProxyProtocolBranches() {
        ClassInfo commands = scan(COMMANDS);
        Collection<String> strings = commands.allStrings();

        assertTrue(strings.contains("tunnel"), "branch() must build the tunnel literal");
        assertTrue(strings.contains("config"), "config subcommand missing");
        assertTrue(strings.contains("proxyprotocol"), "proxyprotocol subcommand missing");
        assertTrue(strings.contains("on") && strings.contains("off"),
                "proxyprotocol must accept on and off");
        assertTrue(strings.contains("key") && strings.contains("value"),
                "config must expose key and value arguments");
        assertTrue(methodNames(commands).contains("suggestKeys"), "key completion missing");
        assertTrue(methodNames(commands).contains("suggestValues"), "value completion missing");
    }

    private static Set<String> methodNames(ClassInfo info) {
        Set<String> names = new LinkedHashSet<>();
        for (String signature : info.methods.keySet()) {
            names.add(signature.substring(0, signature.indexOf('(')));
        }
        return names;
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
                if (value != null) {
                    info.fieldValues.put(name, value);
                }
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
                        method.calls.add(new Call(owner, calledName));
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        method.opcodes.add(opcode);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String string) {
                            method.strings.add(string);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return info;
    }

    private static byte[] readClass(String internalName) {
        String resource = internalName + ".class";
        try (InputStream in = TunnelCommandBytecodeTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "missing class resource " + resource);
            return in.readAllBytes();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class ClassInfo {
        private final Map<String, MethodInfo> methods = new LinkedHashMap<>();
        private final Map<String, Object> fieldValues = new LinkedHashMap<>();

        private Collection<Call> allCalls() {
            Collection<Call> calls = new ArrayList<>();
            for (MethodInfo method : methods.values()) {
                calls.addAll(method.calls);
            }
            return calls;
        }

        private Collection<String> allStrings() {
            Set<String> strings = new LinkedHashSet<>();
            for (MethodInfo method : methods.values()) {
                strings.addAll(method.strings);
            }
            return strings;
        }
    }

    private static final class MethodInfo {
        private final Set<Call> calls = new LinkedHashSet<>();
        private final Set<Integer> opcodes = new LinkedHashSet<>();
        private final Set<String> strings = new LinkedHashSet<>();
    }

    private record Call(String owner, String name) {
    }
}
