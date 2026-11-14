package io.hyperconduit.mod.mixin.client;

import io.hyperconduit.mod.client.LanStartupFailure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.Executor;

/**
 * Surfaces the safe Chinese sidecar failure recorded by {@code ServerConnectionListenerMixin}
 * when a LAN publish attempt fails.
 *
 * <p>Vanilla {@code ShareToLanScreen} calls {@code IntegratedServer.publishServer}; that method
 * catches the {@code IOException} produced by the listener and returns {@code false}, so the
 * detailed reason would otherwise be lost behind {@code commands.publish.failed}.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerLanFailureMixin {

    @Inject(method = "publishServer(Lnet/minecraft/world/level/GameType;ZI)Z", at = @At("HEAD"))
    private void hyperconduit$beginLanPublish(GameType gameMode, boolean cheats, int port,
                                              CallbackInfoReturnable<Boolean> ci) {
        LanStartupFailure.begin();
    }

    @Inject(method = "publishServer(Lnet/minecraft/world/level/GameType;ZI)Z", at = @At("RETURN"))
    private void hyperconduit$reportLanFailure(GameType gameMode, boolean cheats, int port,
                                               CallbackInfoReturnable<Boolean> ci) {
        String message = LanStartupFailure.finish();
        if (message == null || ci.getReturnValueZ()) return;
        Minecraft minecraft = Minecraft.getInstance();
        try {
            // Minecraft implements Executor through BlockableEventLoop; using the JDK interface
            // avoids an inherited-member remap and keeps the chat update on the client thread.
            Executor clientExecutor = minecraft;
            clientExecutor.execute(() -> minecraft.gui.getChat().addMessage(Component.literal(message)));
        } catch (RuntimeException ignored) {
            // The vanilla failure message is still shown; chat delivery must not break publishing.
        }
    }
}
