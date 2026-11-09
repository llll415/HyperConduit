package io.hyperconduit.mod.mixin.client;

import io.hyperconduit.mod.probe.StatusProbeScope;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.util.debugchart.LocalSampleLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.net.InetSocketAddress;

@Mixin(ServerStatusPinger.class)
public abstract class ServerStatusPingerMixin {
    @Redirect(method = "pingServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;connectToServer(Ljava/net/InetSocketAddress;ZLnet/minecraft/util/debugchart/LocalSampleLogger;)Lnet/minecraft/network/Connection;"))
    private Connection hyperconduit$vanillaStatus(InetSocketAddress address, boolean epoll, LocalSampleLogger logger) {
        try (StatusProbeScope ignored = StatusProbeScope.enter()) {
            return Connection.connectToServer(address, epoll, logger);
        }
    }

    /**
     * Vanilla only sets {@code UNREACHABLE} for synchronous failures; an asynchronous status
     * disconnect calls {@code onPingFailed} and leaves the entry in {@code PINGING} forever.
     * Exit that state here so a failed status/pong can never look like a permanent "检测中".
     */
    @Inject(method = "onPingFailed", at = @At("HEAD"))
    private void hyperconduit$exitPinging(Component reason, ServerData serverData, CallbackInfo ci) {
        try {
            if (serverData.state() == ServerData.State.PINGING) {
                serverData.setState(ServerData.State.UNREACHABLE);
            }
        } catch (RuntimeException ignored) {
            // Never let the advisory state fix abort vanilla's failure handling.
        }
    }
}
