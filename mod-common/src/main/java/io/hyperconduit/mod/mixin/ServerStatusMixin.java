package io.hyperconduit.mod.mixin;

import io.hyperconduit.mod.probe.Capability;
import io.hyperconduit.mod.probe.CapabilityStatus;
import net.minecraft.network.protocol.status.ServerStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerStatus.class)
public abstract class ServerStatusMixin implements CapabilityStatus {
    @Unique private Capability hyperconduit$capability;
    @Override public Capability hyperconduit$capability() {
        return hyperconduit$capability == null ? Capability.UNDECLARED : hyperconduit$capability;
    }
    @Override public void hyperconduit$capability(Capability capability) { hyperconduit$capability = capability; }
}
