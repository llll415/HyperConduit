package io.hyperconduit.mod.mixin.client;

import io.hyperconduit.mod.bridge.ResolvedEndpoint;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Optional;

@Mixin(ServerNameResolver.class)
public abstract class ServerNameResolverMixin {
    @Inject(method = "resolveAddress", at = @At("RETURN"))
    private void hyperconduit$rememberOriginal(ServerAddress original, CallbackInfoReturnable<Optional<ResolvedServerAddress>> ci) {
        String host = original.getHost();
        String endpoint = (host.contains(":") ? "[" + host + "]" : host) + ":" + original.getPort();
        ResolvedEndpoint.record(endpoint, ci.getReturnValue().map(ResolvedServerAddress::asInetSocketAddress).orElse(null));
    }
}
