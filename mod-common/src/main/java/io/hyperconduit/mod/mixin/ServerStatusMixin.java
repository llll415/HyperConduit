package io.hyperconduit.mod.mixin;

import io.hyperconduit.mod.probe.Capability;
import io.hyperconduit.mod.probe.CapabilityDetails;
import io.hyperconduit.mod.probe.CapabilityStatus;
import net.minecraft.network.protocol.status.ServerStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerStatus.class)
public abstract class ServerStatusMixin implements CapabilityStatus {
    @Unique private CapabilityDetails hyperconduit$declaration;
    @Override public Capability hyperconduit$capability() {
        return hyperconduit$declaration == null ? Capability.UNDECLARED : hyperconduit$declaration.state();
    }
    @Override public void hyperconduit$capability(Capability capability) {
        hyperconduit$declaration = CapabilityDetails.of(capability);
    }
    @Override public CapabilityDetails hyperconduit$details() {
        return hyperconduit$declaration == null ? CapabilityDetails.UNDECLARED : hyperconduit$declaration;
    }
    @Override public void hyperconduit$details(CapabilityDetails details) {
        hyperconduit$declaration = details == null ? CapabilityDetails.UNDECLARED : details;
    }
}
