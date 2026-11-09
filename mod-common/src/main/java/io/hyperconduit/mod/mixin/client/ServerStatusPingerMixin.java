package io.hyperconduit.mod.mixin.client;

import io.hyperconduit.mod.probe.StatusProbeScope;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.Connection;
import net.minecraft.util.debugchart.LocalSampleLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
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
}
