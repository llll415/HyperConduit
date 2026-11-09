package io.hyperconduit.mod.mixin.server;

import io.netty.bootstrap.ServerBootstrap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline bytecode fixtures for the two runtime layouts of the server bind call.
 *
 * <p>Vanilla NeoForge keeps {@code ServerBootstrap.bind()} inside
 * {@code startTcpServerListener}. Youer/Paper moves it into
 * {@code ServerConnectionListener.bind(SocketAddress)}. The mixin must cover both
 * call sites while installing exactly one hook.
 */
class ServerConnectionListenerMixinSelectorTest {

    private static final String BIND_TARGET =
            "Lio/netty/bootstrap/ServerBootstrap;bind()Lio/netty/channel/ChannelFuture;";
    private static final String VANILLA_METHOD = "startTcpServerListener(Ljava/net/InetAddress;I)V";
    private static final String YOUER_METHOD = "bind(Ljava/net/SocketAddress;)V";
    private static final String START_DESCRIPTOR = "(Ljava/net/InetAddress;I)V";
    private static final String BIND_DESCRIPTOR = "(Ljava/net/SocketAddress;)V";
    private static final String SERVER_BOOTSTRAP = "io/netty/bootstrap/ServerBootstrap";
    private static final String BIND_DESC = "()Lio/netty/channel/ChannelFuture;";
    private static final String SERVER_CONNECTION_LISTENER =
            "net/minecraft/server/network/ServerConnectionListener";

    @Test
    void groupedRedirectsCoverVanillaAndYouerCallSites() {
        Method vanilla = redirectFor(VANILLA_METHOD);
        Method youer = redirectFor(YOUER_METHOD);
        assertNotSame(vanilla, youer, "each runtime layout must have its own handler method");

        assertRedirect(vanilla, VANILLA_METHOD, true);
        assertRedirect(youer, YOUER_METHOD, false);

        Map<String, GroupValues> groups = groupAnnotations(ServerConnectionListenerMixin.class);
        assertGroup(groups.get(vanilla.getName()));
        assertGroup(groups.get(youer.getName()));

        long uniqueHelpers = Arrays.stream(ServerConnectionListenerMixin.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("hyperconduit$bindInternal"))
                .count();
        assertEquals(1, uniqueHelpers, "both handlers must delegate to one private helper");
    }

    @Test
    void vanillaFixtureHasCallSiteOnlyInStartTcpServerListener() {
        assertEquals(1, bindCalls(VanillaFixture.class, "startTcpServerListener", START_DESCRIPTOR));
        assertEquals(0, bindCalls(VanillaFixture.class, "bind", BIND_DESCRIPTOR));
    }

    @Test
    void youerFixtureMovesCallSiteIntoBindSocketAddress() {
        assertEquals(0, bindCalls(YouerFixture.class, "startTcpServerListener", START_DESCRIPTOR));
        assertEquals(1, bindCalls(YouerFixture.class, "bind", BIND_DESCRIPTOR));
        assertTrue(hasMethod(readClass(YouerFixture.class), "bind", BIND_DESCRIPTOR));
    }

    /**
     * Verifies the real Youer class when the path is supplied. Example:
     * {@code -Dhyperconduit.youerServerJar=D:\...\paper-remap.jar}.
     */
    @Test
    void actualYouerClassMatchesSelectorsWhenJarIsProvided() throws Exception {
        String location = System.getProperty("hyperconduit.youerServerJar");
        Assumptions.assumeTrue(location != null && !location.isBlank(),
                "set -Dhyperconduit.youerServerJar=<Youer paper-remap.jar> to inspect the real class");
        Path path = Path.of(location);
        Assumptions.assumeTrue(Files.isRegularFile(path), "missing " + path);

        byte[] bytes;
        if (location.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                ZipEntry entry = zip.getEntry(SERVER_CONNECTION_LISTENER + ".class");
                assertNotNull(entry, "ServerConnectionListener.class missing from " + path);
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
            }
        } else {
            bytes = Files.readAllBytes(path);
        }

        assertTrue(hasMethod(bytes, "bind", BIND_DESCRIPTOR), "Youer bind(SocketAddress) is missing");
        assertEquals(0, bindCalls(bytes, "startTcpServerListener", START_DESCRIPTOR),
                "Youer startTcpServerListener must delegate instead of binding directly");
        assertEquals(1, bindCalls(bytes, "bind", BIND_DESCRIPTOR),
                "Youer bind(SocketAddress) must contain the actual bootstrap bind call");
        assertTrue(callsMethod(bytes, "startTcpServerListener", START_DESCRIPTOR,
                        SERVER_CONNECTION_LISTENER, "bind", BIND_DESCRIPTOR),
                "Youer startTcpServerListener must call bind(SocketAddress)");
    }

    private static Method redirectFor(String selector) {
        return Arrays.stream(ServerConnectionListenerMixin.class.getDeclaredMethods())
                .filter(method -> {
                    Redirect redirect = method.getAnnotation(Redirect.class);
                    return redirect != null && Arrays.asList(redirect.method()).contains(selector);
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing @Redirect for " + selector));
    }

    private static void assertRedirect(Method method, String selector, boolean remap) {
        Redirect redirect = method.getAnnotation(Redirect.class);
        assertNotNull(redirect);
        assertArrayEquals(new String[] {selector}, redirect.method());
        assertEquals(0, redirect.require(), "require=0 tolerates the other runtime's missing method");
        assertEquals(remap, redirect.remap(), "Youer-only method must not be remapped");
        At at = redirect.at();
        assertEquals("INVOKE", at.value());
        assertEquals(BIND_TARGET, at.target());
    }

    private static void assertGroup(GroupValues group) {
        assertNotNull(group, "handler is missing @Group");
        assertEquals("hyperconduit_bind", group.name());
        assertEquals(1, group.min());
        assertEquals(1, group.max());
    }

    private static Map<String, GroupValues> groupAnnotations(Class<?> type) {
        byte[] bytes = readClass(type);
        Map<String, GroupValues> groups = new LinkedHashMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (!annotationDescriptor.equals("Lorg/spongepowered/asm/mixin/injection/Group;")) {
                            return null;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            private String groupName;
                            private int min = -1;
                            private int max = -1;

                            @Override
                            public void visit(String key, Object value) {
                                switch (key) {
                                    case "name" -> groupName = (String) value;
                                    case "min" -> min = (Integer) value;
                                    case "max" -> max = (Integer) value;
                                    default -> {
                                        // No other @Group members are used.
                                    }
                                }
                            }

                            @Override
                            public void visitEnd() {
                                groups.put(name, new GroupValues(groupName, min, max));
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return groups;
    }

    private static byte[] readClass(Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "missing class resource " + resource);
            return in.readAllBytes();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static int bindCalls(Class<?> owner, String methodName, String descriptor) {
        return bindCalls(readClass(owner), methodName, descriptor);
    }

    private static int bindCalls(byte[] bytes, String methodName, String descriptor) {
        int[] count = {0};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                if (!name.equals(methodName) || !desc.equals(descriptor)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String calledName,
                                                String calledDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(SERVER_BOOTSTRAP)
                                && calledName.equals("bind")
                                && calledDescriptor.equals(BIND_DESC)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static boolean hasMethod(byte[] bytes, String methodName, String descriptor) {
        boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                if (name.equals(methodName) && desc.equals(descriptor)) {
                    found[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean callsMethod(byte[] bytes, String methodName, String descriptor,
                                       String owner, String calledName, String calledDescriptor) {
        boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                if (!name.equals(methodName) || !desc.equals(descriptor)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String calledOwner, String name,
                                                String descriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && calledOwner.equals(owner)
                                && name.equals(calledName)
                                && descriptor.equals(calledDescriptor)) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private record GroupValues(String name, int min, int max) {
    }

    /** Mirrors vanilla NeoForge: the bind call is directly in startTcpServerListener. */
    static final class VanillaFixture {
        private final ServerBootstrap bootstrap = new ServerBootstrap();

        void startTcpServerListener(InetAddress address, int port) {
            bootstrap.bind();
        }
    }

    /** Mirrors Youer/Paper: startTcpServerListener delegates to bind(SocketAddress). */
    static final class YouerFixture {
        private final ServerBootstrap bootstrap = new ServerBootstrap();

        void startTcpServerListener(InetAddress address, int port) {
            bind(new InetSocketAddress(address, port));
        }

        void bind(SocketAddress address) {
            bootstrap.bind();
        }
    }
}
