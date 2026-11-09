package io.hyperconduit.neoforge.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static bytecode check for the F3 status colors; it does not start a client. */
class HyperConduitClientEventsBytecodeTest {

    @Test
    void disabledStatusUsesReadableLightGray() throws IOException {
        Set<String> constants = stringConstants(
                "io/hyperconduit/neoforge/client/HyperConduitClientEvents");

        assertTrue(constants.contains("§e[未使用隧道]§r"), () -> "missing enabled status: " + constants);
        assertTrue(constants.contains("§7[已禁用]§r"), () -> "missing light gray status: " + constants);
        assertFalse(constants.contains("§8[已禁用]§r"), () -> "dark gray status still present: " + constants);
    }

    @Test
    void tunnelStatusOmitsTechnicalRateNote() throws IOException {
        Set<String> constants = stringConstants(
                "io/hyperconduit/neoforge/client/HyperConduitClientEvents");

        assertTrue(hasFragment(constants, "§a[隧道已连接]§r"),
                () -> "missing tunnel status: " + constants);
        assertTrue(hasFragment(constants, "§f[传输]§r "),
                () -> "missing transport status: " + constants);
        assertFalse(hasFragment(constants, "§7速率含 QUIC 重传与协议开销，非游戏有效载荷§r"),
                () -> "technical rate note still present: " + constants);
    }

    @Test
    void serverStatusOmitsApplyTimingAndRateNote() throws IOException {
        Set<String> constants = stringConstants(
                "io/hyperconduit/neoforge/HyperConduitServerEvents");

        assertTrue(hasFragment(constants, "[HyperConduit] 服务端配置 | "),
                () -> "missing server config status: " + constants);
        assertFalse(hasFragment(constants, "服务端配置（下次监听应用）"),
                () -> "apply-timing note still present: " + constants);
        assertFalse(hasFragment(constants, "含协议开销与重传"),
                () -> "server rate note still present: " + constants);
    }

    /** String literals may be in LDC or in a StringConcatFactory recipe bootstrap argument. */
    private static boolean hasFragment(Set<String> constants, String fragment) {
        return constants.stream().anyMatch(text -> text.contains(fragment));
    }

    private static Set<String> stringConstants(String className) throws IOException {
        String resource = className + ".class";
        try (InputStream input = HyperConduitClientEventsBytecodeTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(input, "missing class resource: " + resource);
            Set<String> constants = new HashSet<>();
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof String text) {
                                constants.add(text);
                            }
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String name, String descriptor,
                                                           Handle bootstrapMethodHandle,
                                                           Object... bootstrapMethodArguments) {
                            if (!"java/lang/invoke/StringConcatFactory".equals(bootstrapMethodHandle.getOwner())
                                    || !"makeConcatWithConstants".equals(name)) {
                                return;
                            }
                            // Recipe first, then any \u0002 literal constants used by the recipe.
                            for (Object argument : bootstrapMethodArguments) {
                                if (argument instanceof String text) {
                                    constants.add(text);
                                }
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return constants;
        }
    }
}
