package io.hyperconduit.mod.mixin.client;

import io.hyperconduit.mod.bridge.ConnectionAttemptScope;
import io.hyperconduit.mod.client.TransportPreflightScreen;
import io.hyperconduit.mod.config.ConfigErrorText;
import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public abstract class ConnectScreenTransportMixin {

    private static final System.Logger HYPERCONDUIT_LOGGER =
            System.getLogger("io.hyperconduit.HyperConduit");

    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void hyperconduit$chooseTransport(Screen parent, Minecraft minecraft, ServerAddress address,
                                                     ServerData data, boolean quickPlay, TransferState transfer,
                                                     CallbackInfo ci) {
        if (ConnectionAttemptScope.snapshot() != null || minecraft.screen instanceof ConnectScreen) return;
        HyperConduitConfig config;
        try {
            config = ConfigHolder.getForConnection();
        } catch (RuntimeException failure) {
            String message = ConfigErrorText.describe(failure);
            HYPERCONDUIT_LOGGER.log(System.Logger.Level.WARNING, message);
            Screen back = parent != null ? parent : minecraft.screen;
            minecraft.setScreen(new DisconnectedScreen(back, Component.literal("HyperConduit"),
                    Component.literal(message)));
            ci.cancel();
            return;
        }
        minecraft.setScreen(new TransportPreflightScreen(parent, address, data, quickPlay, transfer, config.copy()));
        ci.cancel();
    }

    @Redirect(method = "connect", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;start()V"))
    private void hyperconduit$connectorSnapshot(Thread connector) {
        ConnectionAttemptScope.startConnector(connector);
    }
}
