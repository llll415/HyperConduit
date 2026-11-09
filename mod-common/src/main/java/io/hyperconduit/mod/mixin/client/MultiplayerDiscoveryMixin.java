package io.hyperconduit.mod.mixin.client;

import io.hyperconduit.mod.probe.*;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerDiscoveryMixin implements DiscoveryOwner {
    @Shadow private ServerList servers;
    @Unique private DiscoverySession hyperconduit$session;

    @Inject(method = "init", at = @At("TAIL"))
    private void hyperconduit$start(CallbackInfo ci) {
        if (hyperconduit$session != null) return; // resizing is not a refresh
        hyperconduit$session = new DiscoverySession();
        for (int i = 0; i < servers.size(); i++) {
            ServerData server = servers.get(i);
            ((CapabilityEntry) server).hyperconduit$reset();
            server.setState(ServerData.State.INITIAL);
            hyperconduit$session.submit(server);
        }
    }

    @Override public void hyperconduit$discover(ServerData server) {
        if (hyperconduit$session != null) hyperconduit$session.submit(server);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void hyperconduit$stop(CallbackInfo ci) {
        if (hyperconduit$session != null) hyperconduit$session.close();
        hyperconduit$session = null;
    }
}
