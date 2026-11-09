package io.hyperconduit.mod.mixin.client;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-ASM selector checks. This test never loads a mixin or Minecraft class, so it can run in
 * {@code mod-common} without a Minecraft test classpath.
 *
 * <p>The production failure was:
 * {@code @Shadow field val$serverData was not located ... No refMap loaded}. The captured fields
 * are synthetic and differ between dev ({@code val$serverData}) and the SRG client
 * ({@code val$data}); the mixin must capture constructor arguments instead.
 */
class StatusPingMixinSelectorTest {
    private static final String RESPONSE_MIXIN =
            "io/hyperconduit/mod/mixin/client/StatusResponseListenerMixin.class";
    private static final String PINGER_MIXIN =
            "io/hyperconduit/mod/mixin/client/ServerStatusPingerMixin.class";
    private static final String PACKET_MIXIN =
            "io/hyperconduit/mod/mixin/StatusPacketMixin.class";

    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String UNIQUE = "Lorg/spongepowered/asm/mixin/Unique;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";

    private static final String PINGER_INNER = "net/minecraft/client/multiplayer/ServerStatusPinger$1";
    private static final String PINGER_INNER_CLASS = PINGER_INNER + ".class";

    @Test
    void responseMixinNoLongerShadowsSyntheticCapturedFields() {
        ClassModel model = readResource(RESPONSE_MIXIN);
        for (Map.Entry<String, Map<String, Map<String, Object>>> field : model.fields.entrySet()) {
            assertFalse(field.getValue().containsKey(SHADOW),
                    "must not shadow synthetic captured field " + field.getKey());
        }
        for (String unique : List.of("hyperconduit$serverData", "hyperconduit$serverAddress",
                "hyperconduit$generation")) {
            Map<String, Map<String, Object>> annotations = model.fields.get(unique);
            assertNotNull(annotations, "missing unique field " + unique);
            assertTrue(annotations.containsKey(UNIQUE), unique + " must be @Unique");
        }
    }

    @Test
    void constructorCaptureMatchesTheAnonymousClassDescriptor() {
        ClassModel model = readResource(RESPONSE_MIXIN);
        MethodModel capture = model.method("hyperconduit$capture");
        assertNotNull(capture, "missing constructor capture handler");

        Map<String, Object> inject = capture.annotation(INJECT);
        assertNotNull(inject, "capture must be @Inject");
        assertEquals(List.of("<init>"), inject.get("method"));
        assertEquals("RETURN", firstAt(inject).get("value"));

        assertTrue(capture.descriptor.startsWith(
                        "(Lnet/minecraft/client/multiplayer/ServerStatusPinger;"
                                + "Lnet/minecraft/network/Connection;"
                                + "Lnet/minecraft/client/multiplayer/ServerData;"
                                + "Ljava/lang/Runnable;Ljava/lang/Runnable;"
                                + "Ljava/net/InetSocketAddress;"
                                + "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;"),
                capture.descriptor);
        assertTrue(capture.descriptor.endsWith(CALLBACK + ")V"), capture.descriptor);
    }

    @Test
    void statusPacketRedirectsCoverBothNeoForgeWriteLayouts() {
        ClassModel model = readResource(PACKET_MIXIN);

        MethodModel read = model.method("hyperconduit$read");
        assertNotNull(read);
        assertEquals("Lnet/minecraft/network/FriendlyByteBuf;readJsonWithCodec(Lcom/mojang/serialization/Codec;)Ljava/lang/Object;",
                firstAt(read.annotation(REDIRECT)).get("target"));

        MethodModel write = model.method("hyperconduit$write");
        assertNotNull(write);
        assertEquals("Lnet/minecraft/network/FriendlyByteBuf;writeJsonWithCodec(Lcom/mojang/serialization/Codec;Ljava/lang/Object;)V",
                firstAt(write.annotation(REDIRECT)).get("target"));

        MethodModel cached = model.method("hyperconduit$cached");
        assertNotNull(cached);
        Map<String, Object> cachedRedirect = cached.annotation(REDIRECT);
        assertEquals("Lnet/minecraft/network/FriendlyByteBuf;writeUtf(Ljava/lang/String;)Lnet/minecraft/network/FriendlyByteBuf;",
                firstAt(cachedRedirect).get("target"));
        assertEquals(0, cachedRedirect.get("require"),
                "non-NeoForge layouts have no cached writeUtf call and must not fail the mixin");
    }

    @Test
    void pingFailureHookTargetsVanillaOnPingFailed() {
        ClassModel model = readResource(PINGER_MIXIN);
        MethodModel failure = model.method("hyperconduit$exitPinging");
        assertNotNull(failure);
        Map<String, Object> inject = failure.annotation(INJECT);
        assertNotNull(inject);
        assertEquals(List.of("onPingFailed"), inject.get("method"));
        assertEquals("HEAD", firstAt(inject).get("value"));
        assertEquals("(Lnet/minecraft/network/chat/Component;"
                        + "Lnet/minecraft/client/multiplayer/ServerData;"
                        + CALLBACK + ")V",
                failure.descriptor);
    }

    /**
     * Optional real-client check. Run with
     * {@code -Dhyperconduit.clientJar=<client-1.21.1-...-srg.jar>}.
     */
    @Test
    void disconnectTailClosesTheSuccessfulNoPongHole() {
        ClassModel model = readResource(RESPONSE_MIXIN);
        MethodModel disconnect = model.method("hyperconduit$exitPingingOnDisconnect");
        assertNotNull(disconnect);
        Map<String, Object> inject = disconnect.annotation(INJECT);
        assertNotNull(inject);
        assertEquals(List.of("onDisconnect"), inject.get("method"));
        assertEquals("TAIL", firstAt(inject).get("value"));
        assertEquals("(Lnet/minecraft/network/DisconnectionDetails;" + CALLBACK + ")V",
                disconnect.descriptor);
    }

    @Test
    void actualProductionClientUsesDifferentSyntheticFieldNames() throws Exception {
        String location = System.getProperty("hyperconduit.clientJar");
        Assumptions.assumeTrue(location != null && !location.isBlank(),
                "set -Dhyperconduit.clientJar=<client-1.21.1-...-srg.jar> to inspect the real class");
        Path path = Path.of(location);
        Assumptions.assumeTrue(Files.isRegularFile(path), "missing " + path);

        byte[] bytes;
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry(PINGER_INNER_CLASS);
            assertNotNull(entry, PINGER_INNER_CLASS + " missing from " + path);
            try (InputStream in = zip.getInputStream(entry)) {
                bytes = in.readAllBytes();
            }
        }

        ClassModel layout = read(bytes);
        assertEquals("(Lnet/minecraft/client/multiplayer/ServerStatusPinger;"
                        + "Lnet/minecraft/network/Connection;"
                        + "Lnet/minecraft/client/multiplayer/ServerData;"
                        + "Ljava/lang/Runnable;Ljava/lang/Runnable;"
                        + "Ljava/net/InetSocketAddress;"
                        + "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)V",
                layout.constructorDescriptor,
                "constructor descriptor must match the mixin's captured arguments");
        assertTrue(layout.fields.containsKey("val$data"), layout.fields.keySet().toString());
        assertTrue(layout.fields.containsKey("val$rawAddress"), layout.fields.keySet().toString());
        assertFalse(layout.fields.containsKey("val$serverData"),
                "production uses val$data; shadowing val$serverData was the fatal selector");
        assertFalse(layout.fields.containsKey("val$serveraddress"),
                "production uses val$rawAddress; shadowing val$serveraddress was the fatal selector");
    }

    private static Map<String, Object> firstAt(Map<String, Object> annotation) {
        Object value = annotation == null ? null : annotation.get("at");
        if (value instanceof Map<?, ?> single) {
            @SuppressWarnings("unchecked")
            Map<String, Object> at = (Map<String, Object>) single;
            return at;
        }
        assertTrue(value instanceof List<?> list && !list.isEmpty(), "missing @At");
        Object first = ((List<?>) value).get(0);
        assertTrue(first instanceof Map<?, ?>, "malformed @At");
        @SuppressWarnings("unchecked")
        Map<String, Object> at = (Map<String, Object>) first;
        return at;
    }

    private static ClassModel readResource(String resource) {
        try (InputStream in = StatusPingMixinSelectorTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "missing class resource " + resource);
            return read(in.readAllBytes());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static ClassModel read(byte[] bytes) {
        ClassModel model = new ClassModel();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                model.fields.put(name, new LinkedHashMap<>());
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        return collector(annotationDescriptor, model.fields.get(name));
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodModel method = new MethodModel(name, descriptor);
                model.methods.put(name, method);
                if ("<init>".equals(name)) {
                    model.constructorDescriptor = descriptor;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        return collector(annotationDescriptor, method.annotations);
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return model;
    }

    private static AnnotationVisitor collector(String descriptor, Map<String, Map<String, Object>> target) {
        Map<String, Object> values = new LinkedHashMap<>();
        target.put(descriptor, values);
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                values.put(name, value);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                List<Object> list = new ArrayList<>();
                values.put(name, list);
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String ignored, Object value) {
                        list.add(value);
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String ignored, String annotationDescriptor) {
                        Map<String, Object> nested = new LinkedHashMap<>();
                        list.add(nested);
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String key, Object value) {
                                nested.put(key, value);
                            }
                        };
                    }
                };
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String annotationDescriptor) {
                Map<String, Object> nested = new LinkedHashMap<>();
                values.put(name, nested);
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String key, Object value) {
                        nested.put(key, value);
                    }
                };
            }
        };
    }

    private static final class ClassModel {
        final Map<String, Map<String, Map<String, Object>>> fields = new LinkedHashMap<>();
        final Map<String, MethodModel> methods = new LinkedHashMap<>();
        String constructorDescriptor;

        MethodModel method(String name) {
            return methods.get(name);
        }
    }

    private record MethodModel(String name, String descriptor,
                               Map<String, Map<String, Object>> annotations) {
        MethodModel(String name, String descriptor) {
            this(name, descriptor, new LinkedHashMap<>());
        }

        Map<String, Object> annotation(String descriptor) {
            return annotations.get(descriptor);
        }
    }
}
