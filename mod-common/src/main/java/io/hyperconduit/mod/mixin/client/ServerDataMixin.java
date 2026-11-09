package io.hyperconduit.mod.mixin.client;

import io.hyperconduit.mod.probe.*;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerData.class)
public abstract class ServerDataMixin implements CapabilityEntry {
    @Unique private final CapabilityEvidence hyperconduit$evidence = new CapabilityEvidence();
    @Unique private volatile long hyperconduit$generation;
    @Override public Capability hyperconduit$capability() { return hyperconduit$evidence.state(); }
    @Override public CapabilityDetails hyperconduit$details() { return hyperconduit$evidence.details(); }
    @Override public void hyperconduit$vanilla(Capability value) { hyperconduit$evidence.vanilla(value); }
    @Override public void hyperconduit$probe(Capability value) { hyperconduit$evidence.quic(value); }
    @Override public void hyperconduit$vanillaDetails(CapabilityDetails details) { hyperconduit$evidence.vanilla(details); }
    @Override public void hyperconduit$probeDetails(CapabilityDetails details) { hyperconduit$evidence.quic(details); }
    @Override public void hyperconduit$reset() { hyperconduit$generation++; hyperconduit$evidence.reset(); }
    @Override public long hyperconduit$generation() { return hyperconduit$generation; }
}
