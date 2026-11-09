package io.hyperconduit.mod.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static checks for user-facing text and the vanilla-style preflight layout; no client is started. */
class UserFacingTextTest {

    @Test
    void serverBadgeTooltipDropsDiscoveryDisclaimer() throws Exception {
        Set<String> constants = constants("io/hyperconduit/mod/mixin/client/ServerEntryMixin");
        assertFalse(constants.contains("仅供发现；身份将在连接时验证"),
                () -> "removed discovery disclaimer is still present: " + constants);
    }

    @Test
    void preflightDrawsNoTitleAndUsesVanillaDisconnectedScreenLayout() throws Exception {
        Set<String> constants = constants("io/hyperconduit/mod/client/TransportPreflightScreen");
        List<String> calls = calls("io/hyperconduit/mod/client/TransportPreflightScreen");

        assertFalse(constants.contains("HyperConduit 连接检查"),
                () -> "old preflight header is still present: " + constants);
        assertFalse(calls.contains("net/minecraft/client/gui/GuiGraphics.drawCenteredString"),
                "preflight must not draw its own title");
        assertFalse(calls.contains("net/minecraft/client/gui/GuiGraphics.drawString"),
                "preflight must not draw custom text outside widgets");
        assertFalse(calls.contains("net/minecraft/client/gui/components/StringWidget.<init>"),
                "preflight must not add a title widget");

        assertTrue(calls.contains("net/minecraft/client/gui/layouts/LinearLayout.vertical"));
        assertTrue(calls.contains("net/minecraft/client/gui/layouts/FrameLayout.centerInRectangle"));
        assertTrue(calls.contains("net/minecraft/client/gui/components/MultiLineTextWidget.<init>"));
        assertTrue(calls.contains("net/minecraft/client/gui/components/MultiLineTextWidget.setCentered"));
        assertTrue(calls.contains("net/minecraft/client/gui/components/Button$Builder.width"));
    }

    @Test
    void preflightKeepsShortConsentTextAndBothChoices() throws Exception {
        Set<String> constants = constants("io/hyperconduit/mod/client/TransportPreflightScreen");

        assertTrue(constants.contains("[HyperConduit] 正在检查服务器…"),
                () -> "missing short waiting text: " + constants);
        assertTrue(constants.stream().anyMatch(text -> text.contains("本次不使用隧道保护和服务器身份校验")),
                () -> "missing TCP risk reminder: " + constants);
        assertTrue(constants.stream().anyMatch(text -> text.contains("本次启用 HyperConduit 隧道连接")),
                () -> "missing tunnel confirmation: " + constants);
        assertTrue(constants.contains("本次使用原版 TCP"));
        assertTrue(constants.stream().anyMatch(text -> text.contains("是否改用原版 TCP？")),
                () -> "TCP prompt should ask, not state it is already done: " + constants);
        assertTrue(constants.stream().anyMatch(text -> text.contains("是否启用 HyperConduit 隧道连接？")),
                () -> "tunnel prompt should ask, not state it is already done: " + constants);
        assertFalse(constants.stream().anyMatch(text -> text.contains("仅本次生效")
                        || text.contains("不修改全局设置") || text.contains("也不记忆")),
                () -> "scope explanation belongs in README, not in the prompt: " + constants);
        assertFalse(constants.stream().anyMatch(text -> text.contains("声明未经")
                        || text.contains("可能过期") || text.contains("已有信任记录也不会校验")),
                () -> "long discovery/TOFU explanation should stay out of the prompt: " + constants);
    }

    @Test
    void preflightSelectionWiringIsUnchanged() throws Exception {
        List<String> calls = calls("io/hyperconduit/mod/client/TransportPreflightScreen");
        assertTrue(calls.contains("io/hyperconduit/mod/bridge/ConnectionAttemptScope.enter"));
        assertEquals(1, calls.stream().filter(call ->
                call.equals("net/minecraft/client/gui/screens/ConnectScreen.startConnecting")).count());
        assertTrue(calls.contains("net/minecraft/client/gui/Font.width"),
                "confirm button width must fit its label");
        assertFalse(calls.stream().anyMatch(call ->
                call.contains("ConfigHolder.save") || call.contains("KnownServers")));
    }

    private static Set<String> constants(String resource) throws Exception {
        Set<String> result = new HashSet<>();
        read(resource, new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitLdcInsn(Object value) {
                        if (value instanceof String text) result.add(text);
                    }

                    @Override public void visitInvokeDynamicInsn(String name, String descriptor,
                                                                  Handle bootstrapMethodHandle,
                                                                  Object... bootstrapMethodArguments) {
                        if (!"java/lang/invoke/StringConcatFactory".equals(bootstrapMethodHandle.getOwner())
                                || !"makeConcatWithConstants".equals(name)) {
                            return;
                        }
                        // Recipe first, then any \u0002 literal constants used by the recipe.
                        for (Object argument : bootstrapMethodArguments) {
                            if (argument instanceof String text) result.add(text);
                        }
                    }
                };
            }
        });
        return result;
    }

    private static List<String> calls(String resource) throws Exception {
        List<String> result = new ArrayList<>();
        read(resource, new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                          String descriptor, boolean isInterface) {
                        result.add(owner + "." + name);
                    }
                };
            }
        });
        return result;
    }

    private static void read(String resource, ClassVisitor visitor) throws Exception {
        try (InputStream input = UserFacingTextTest.class.getClassLoader()
                .getResourceAsStream(resource + ".class")) {
            assertNotNull(input, "missing class resource: " + resource);
            new ClassReader(input).accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
    }
}
