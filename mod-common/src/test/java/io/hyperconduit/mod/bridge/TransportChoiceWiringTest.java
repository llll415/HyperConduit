package io.hyperconduit.mod.bridge;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Checks compiled production wiring without bootstrapping a Minecraft client. */
class TransportChoiceWiringTest {
    @Test void selectedSnapshotIsPassedIntoVanillaConnectorAndUsedAtDial() throws Exception {
        List<String> connector = calls("io/hyperconduit/mod/mixin/client/ConnectScreenTransportMixin");
        assertTrue(connector.contains("io/hyperconduit/mod/bridge/ConnectionAttemptScope.startConnector"));
        assertTrue(connector.contains("io/hyperconduit/mod/bridge/ConnectionAttemptScope.snapshot"));
        List<String> dial = calls("io/hyperconduit/mod/mixin/client/ConnectionClientMixin");
        int snapshot = dial.indexOf("io/hyperconduit/mod/bridge/ConnectionAttemptScope.snapshot");
        int global = dial.indexOf("io/hyperconduit/mod/config/ConfigHolder.get");
        assertTrue(snapshot >= 0 && global >= 0 && snapshot < global);
        assertTrue(dial.contains("io/hyperconduit/mod/probe/StatusProbeScope.active"),
                "status pings must still bypass the login tunnel");
        List<String> handshake = calls("io/hyperconduit/mod/mixin/ConnectionAddressMixin");
        assertEquals(2, handshake.stream().filter(call -> call.equals(
                "io/hyperconduit/mod/bridge/ConnectionAttemptScope.snapshot")).count(),
                "both handshake host and port must use the per-attempt setting");
    }

    @Test void consentDoesNotSaveConfigurationOrTouchTrustAndHasNoFailureFallback() throws Exception {
        List<String> screen = calls("io/hyperconduit/mod/client/TransportPreflightScreen");
        assertTrue(screen.contains("io/hyperconduit/mod/bridge/ConnectionAttemptScope.enter"));
        assertEquals(1, screen.stream().filter(call -> call.equals(
                "net/minecraft/client/gui/screens/ConnectScreen.startConnecting")).count());
        assertFalse(screen.stream().anyMatch(call -> call.contains("ConfigHolder.save") || call.contains("KnownServers")));
        List<String> dial = calls("io/hyperconduit/mod/bridge/ClientTunnelInitializer$Dial");
        assertFalse(dial.contains("net/minecraft/client/gui/screens/ConnectScreen.startConnecting"));
        assertFalse(dial.contains("io/hyperconduit/mod/client/TransportPreflightScreen.<init>"));
    }

    @Test void connectionDiscoveryIsReadOnlyAndHasNoTunnelOrTrustOperations() throws Exception {
        List<String> calls = calls("io/hyperconduit/mod/client/TransportDiscovery");
        assertTrue(calls.contains("io/hyperconduit/mod/bridge/VanillaStatusQuery.query"));
        assertTrue(calls.contains("io/hyperconduit/sidecar/ProbeProcess.probeDetails"));
        assertFalse(calls.stream().anyMatch(call -> call.contains("TrustService") || call.contains("TunnelService")
                || call.contains("SidecarProcess") || call.contains("ConfigHolder.save")));
    }

    private static List<String> calls(String resource) throws Exception {
        List<String> result = new ArrayList<>();
        try (InputStream in = TransportChoiceWiringTest.class.getClassLoader().getResourceAsStream(resource + ".class")) {
            assertNotNull(in);
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                            String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                               String descriptor, boolean isInterface) {
                            result.add(owner + "." + name);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return result;
    }
}
