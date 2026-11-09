package io.hyperconduit.neoforge.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Inspect the real mapped Minecraft connector, without creating a client or window. */
class TransportConnectorBytecodeTest {
    @Test void transportSnapshotRedirectStillMatchesOneRealConnectorStart() throws Exception {
        AtomicInteger matches = new AtomicInteger();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(
                "net/minecraft/client/gui/screens/ConnectScreen.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                            String signature, String[] exceptions) {
                    if (!name.equals("connect")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                               String descriptor, boolean isInterface) {
                            if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/lang/Thread")
                                    && name.equals("start") && descriptor.equals("()V")) matches.incrementAndGet();
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals(1, matches.get());
    }
}
