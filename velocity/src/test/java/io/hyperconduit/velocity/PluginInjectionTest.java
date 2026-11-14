package io.hyperconduit.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginInjectionTest {
    @Test void entrypointUsesGuiceInjectionAnnotation() throws Exception {
        var constructor = HyperConduitVelocityPlugin.class.getConstructor(
                ProxyServer.class, Logger.class, Path.class);
        assertTrue(constructor.isAnnotationPresent(Inject.class));
    }
}
