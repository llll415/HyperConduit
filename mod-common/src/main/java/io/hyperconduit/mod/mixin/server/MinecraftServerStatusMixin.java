package io.hyperconduit.mod.mixin.server;

import io.hyperconduit.mod.probe.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.protocol.status.ServerStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerStatusMixin {
    @Inject(method = "buildServerStatus", at = @At("RETURN"))
    private void hyperconduit$declaration(CallbackInfoReturnable<ServerStatus> ci) {
        var listener = ((MinecraftServer) (Object) this).getConnection();
        CapabilityDetails declaration = listener == null
                ? CapabilityDetails.declaration(false, false, false, false)
                : ((TunnelListenerState) listener).hyperconduit$declaration();
        ((CapabilityStatus) (Object) ci.getReturnValue()).hyperconduit$details(declaration);
    }
}
