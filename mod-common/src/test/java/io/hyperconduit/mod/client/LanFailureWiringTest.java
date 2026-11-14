package io.hyperconduit.mod.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.hyperconduit.sidecar.SidecarException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Static wiring checks for the LAN failure message, plus pure-Java checks for the carrier's
 * one-attempt/thread-local semantics. No Minecraft or mixin class is loaded.
 */
class LanFailureWiringTest {

    private static final String CLIENT_MIXIN =
            "io/hyperconduit/mod/mixin/client/IntegratedServerLanFailureMixin.class";
    private static final String SERVER_MIXIN =
            "io/hyperconduit/mod/mixin/server/ServerConnectionListenerMixin.class";
    private static final String CARRIER = "io/hyperconduit/mod/client/LanStartupFailure.class";
    private static final String ATTEMPT = "io/hyperconduit/mod/client/LanStartupFailure$Attempt.class";

    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT_ANNOTATION = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String CALLBACK =
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
    private static final String PUBLISH_SERVER =
            "publishServer(Lnet/minecraft/world/level/GameType;ZI)Z";

    private static final String EXPECTED_AUTH =
            "[HyperConduit] 隧道程序启动失败。\n原因：认证失败，请检查客户端与服务器口令是否一致。";
    private static final String EXPECTED_LISTENER =
            "[HyperConduit] 隧道程序启动失败。\n原因：无法启动服务器隧道监听，请检查端口与服务器配置。";

    @AfterEach
    void clearAttempt() {
        LanStartupFailure.clear();
    }

    @Test
    void formatsOneUnifiedMessageAndDeduplicatesWrapper() {
        assertEquals(EXPECTED_LISTENER,
                LanStartupFailure.format(new SidecarException("listener_failed").getMessage()));
        assertEquals(EXPECTED_AUTH,
                LanStartupFailure.format(new SidecarException("authentication").getMessage()));
        assertEquals(EXPECTED_LISTENER, LanStartupFailure.format(null));
    }

    @Test
    void attemptIsThreadLocalAndRemovedAtFinish() throws InterruptedException {
        LanStartupFailure.begin();
        LanStartupFailure.record(new SidecarException("authentication"));

        AtomicReference<String> otherThread = new AtomicReference<>("unset");
        Thread worker = new Thread(() -> otherThread.set(LanStartupFailure.finish()));
        worker.start();
        worker.join();

        assertNull(otherThread.get(), "another thread must not see this attempt");
        assertEquals(EXPECTED_AUTH, LanStartupFailure.finish());
        assertNull(LanStartupFailure.finish(), "finish must remove the attempt state");
    }

    @Test
    void beginDiscardsAStaleAttemptAndRecordOutsideAnAttemptIsIgnored() {
        LanStartupFailure.begin();
        LanStartupFailure.record(new SidecarException("authentication"));
        LanStartupFailure.begin();
        assertNull(LanStartupFailure.finish(), "a new attempt must discard the stale failure");

        LanStartupFailure.record(new SidecarException("authentication"));
        assertNull(LanStartupFailure.finish(), "recording without an attempt must be a no-op");
    }

    @Test
    void firstRecordedFailureWins() {
        LanStartupFailure.begin();
        LanStartupFailure.record(new SidecarException("authentication"));
        LanStartupFailure.record(new SidecarException("timeout"));
        assertEquals(EXPECTED_AUTH, LanStartupFailure.finish());
    }

    @Test
    void mixinJsonRegistersTheClientHook() throws Exception {
        try (InputStream input = resource("hyperconduit.mixins.json")) {
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray client = root.getAsJsonArray("client");
            assertNotNull(client, "missing client mixin list");
            List<String> names = new ArrayList<>();
            client.forEach(element -> names.add(element.getAsString()));
            assertTrue(names.contains("client.IntegratedServerLanFailureMixin"), names.toString());
        }
    }

    @Test
    void clientHookTargetsTheRealPublishServerSignature() {
        ClassModel model = read(CLIENT_MIXIN);
        assertTrue(model.mixinTargets.contains("net.minecraft.client.server.IntegratedServer"),
                () -> "unexpected mixin targets: " + model.mixinTargets);

        List<MethodModel> injectors = model.methods.values().stream()
                .filter(method -> method.annotations.containsKey(INJECT_ANNOTATION))
                .toList();
        assertEquals(2, injectors.size(), "expected a begin and a report injector");

        Set<String> atValues = new HashSet<>();
        for (MethodModel injector : injectors) {
            Map<String, Object> inject = injector.annotations.get(INJECT_ANNOTATION);
            assertEquals(List.of(PUBLISH_SERVER), inject.get("method"),
                    "must use the cached ShareToLanScreen -> publishServer signature");
            atValues.add(String.valueOf(firstAt(inject).get("value")));
            assertEquals("(Lnet/minecraft/world/level/GameType;ZI" + CALLBACK + ")V",
                    injector.descriptor);
        }
        assertEquals(Set.of("HEAD", "RETURN"), atValues);
    }

    @Test
    void serverListenerRecordsTheSafeSidecarFailure() {
        ClassModel model = read(SERVER_MIXIN);
        MethodModel bind = model.method("hyperconduit$bindInternal");
        MethodModel helper = model.method("hyperconduit$failure");
        assertNotNull(bind, "missing bind helper");
        assertTrue(recordsFailure(bind) || (helper != null && recordsFailure(helper)),
                "the server listener must forward the safe SidecarException to the carrier");
    }

    @Test
    void carrierKeepsOnlySafeStringState() {
        ClassModel carrier = read(CARRIER);
        for (FieldModel field : carrier.fields.values()) {
            assertFalse(field.descriptor().contains("Throwable"), field.name());
            assertFalse(field.descriptor().contains("Exception"), field.name());
            assertFalse(field.name().toLowerCase(Locale.ROOT).contains("cause"), field.name());
            assertFalse(field.name().toLowerCase(Locale.ROOT).contains("token"), field.name());
            assertFalse(field.name().toLowerCase(Locale.ROOT).contains("path"), field.name());
        }

        ClassModel attempt = read(ATTEMPT);
        assertEquals(Set.of("message"), attempt.fields.keySet());
        assertEquals("Ljava/lang/String;", attempt.fields.get("message").descriptor());
    }

    private static boolean recordsFailure(MethodModel method) {
        return method.calls.contains(
                "io/hyperconduit/mod/client/LanStartupFailure.record"
                        + "(Lio/hyperconduit/sidecar/SidecarException;)V");
    }

    private static Map<String, Object> firstAt(Map<String, Object> annotation) {
        Object value = annotation.get("at");
        if (value instanceof Map<?, ?> single) {
            return castMap(single);
        }
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            return castMap(first);
        }
        fail("missing @At");
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static InputStream resource(String name) {
        InputStream input = LanFailureWiringTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(input, "missing resource: " + name);
        return input;
    }

    private static ClassModel read(String name) {
        try (InputStream input = resource(name)) {
            return read(input.readAllBytes());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static ClassModel read(byte[] bytes) {
        ClassModel model = new ClassModel();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!descriptor.equals(MIXIN_ANNOTATION)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        addTarget(model, value);
                    }

                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String ignored, Object value) {
                                addTarget(model, value);
                            }
                        };
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                model.fields.put(name, new FieldModel(name, descriptor));
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodModel method = new MethodModel(name, descriptor);
                model.methods.put(name, method);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor,
                                                             boolean visible) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        method.annotations.put(annotationDescriptor, values);
                        return collector(values);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String calledName,
                                                String calledDescriptor, boolean isInterface) {
                        method.calls.add(owner + "." + calledName + calledDescriptor);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return model;
    }

    private static void addTarget(ClassModel model, Object value) {
        if (value instanceof Type type) model.mixinTargets.add(type.getClassName());
        else if (value instanceof String text) model.mixinTargets.add(text);
    }

    private static AnnotationVisitor collector(Map<String, Object> values) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                values.put(name, value);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                Map<String, Object> nested = new LinkedHashMap<>();
                values.put(name, nested);
                return collector(nested);
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
                    public AnnotationVisitor visitAnnotation(String ignored, String descriptor) {
                        Map<String, Object> nested = new LinkedHashMap<>();
                        list.add(nested);
                        return collector(nested);
                    }
                };
            }
        };
    }

    private static final class ClassModel {
        final Set<String> mixinTargets = new HashSet<>();
        final Map<String, FieldModel> fields = new LinkedHashMap<>();
        final Map<String, MethodModel> methods = new LinkedHashMap<>();

        MethodModel method(String name) {
            return methods.get(name);
        }
    }

    private record FieldModel(String name, String descriptor) {
    }

    private static final class MethodModel {
        final String name;
        final String descriptor;
        final Map<String, Map<String, Object>> annotations = new LinkedHashMap<>();
        final Set<String> calls = new HashSet<>();

        MethodModel(String name, String descriptor) {
            this.name = name;
            this.descriptor = descriptor;
        }
    }
}
