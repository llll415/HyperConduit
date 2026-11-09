package io.hyperconduit.mod.probe;

import net.minecraft.client.multiplayer.ServerData;

/** Screen-local ownership avoids old refresh results changing a new list generation. */
public interface DiscoveryOwner {
    void hyperconduit$discover(ServerData server);
}
