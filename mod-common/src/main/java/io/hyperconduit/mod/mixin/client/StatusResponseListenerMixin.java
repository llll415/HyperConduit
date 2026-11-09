package io.hyperconduit.mod.mixin.client;

import io.hyperconduit.mod.probe.CapabilityEntry;
import io.hyperconduit.mod.probe.CapabilityStatus;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.multiplayer.ServerStatusPinger$1")
public abstract class StatusResponseListenerMixin {
    @Shadow @Final private ServerData val$serverData;
    @Shadow @Final private ServerAddress val$serveraddress;
    @Unique private long hyperconduit$generation;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void hyperconduit$captureGeneration(CallbackInfo ci) {
        hyperconduit$generation = ((CapabilityEntry) val$serverData).hyperconduit$generation();
    }

    @Inject(method = "handleStatusResponse", at = @At("HEAD"))
    private void hyperconduit$capability(ClientboundStatusResponsePacket packet, CallbackInfo ci) {
        var entry = (CapabilityEntry) val$serverData;
        var current = ServerAddress.parseString(val$serverData.ip);
        if (entry.hyperconduit$generation() == hyperconduit$generation
                && current.getHost().equals(val$serveraddress.getHost()) && current.getPort() == val$serveraddress.getPort()) {
            entry.hyperconduit$vanilla(((CapabilityStatus) (Object) packet.status()).hyperconduit$capability());
        }
    }
}
