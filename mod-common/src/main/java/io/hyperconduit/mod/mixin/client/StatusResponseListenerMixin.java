package io.hyperconduit.mod.mixin.client;

import io.hyperconduit.mod.probe.CapabilityEntry;
import io.hyperconduit.mod.probe.CapabilityStatus;
import io.hyperconduit.mod.probe.StatusPingLifecycle;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetSocketAddress;

/**
 * Reads the advisory declaration from the vanilla status response.
 *
 * <p>The captured fields of {@code ServerStatusPinger$1} are synthetic and their names differ
 * between the development and production mappings ({@code val$serverData} vs {@code val$data}).
 * Shadowing them made the whole mixin fail to apply in production. Capture the constructor
 * arguments instead: the constructor descriptor is mapping-independent.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ServerStatusPinger$1")
public abstract class StatusResponseListenerMixin {
    @Unique private ServerData hyperconduit$serverData;
    @Unique private ServerAddress hyperconduit$serverAddress;
    @Unique private long hyperconduit$generation;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void hyperconduit$capture(ServerStatusPinger pinger, Connection connection, ServerData serverData,
                                      Runnable serverListUpdater, Runnable stateUpdater,
                                      InetSocketAddress address, ServerAddress serverAddress, CallbackInfo ci) {
        this.hyperconduit$serverData = serverData;
        this.hyperconduit$serverAddress = serverAddress;
        if (serverData instanceof CapabilityEntry entry) {
            this.hyperconduit$generation = entry.hyperconduit$generation();
        }
    }

    @Inject(method = "handleStatusResponse", at = @At("HEAD"))
    private void hyperconduit$capability(ClientboundStatusResponsePacket packet, CallbackInfo ci) {
        try {
            ServerData data = this.hyperconduit$serverData;
            ServerAddress expected = this.hyperconduit$serverAddress;
            if (!(data instanceof CapabilityEntry entry) || expected == null) {
                return;
            }
            if (entry.hyperconduit$generation() != this.hyperconduit$generation) {
                return;
            }
            ServerAddress current = ServerAddress.parseString(data.ip);
            if (current == null
                    || !current.getHost().equals(expected.getHost())
                    || current.getPort() != expected.getPort()) {
                return;
            }
            Object decoded = packet.status();
            if (decoded instanceof CapabilityStatus status) {
                entry.hyperconduit$vanillaDetails(status.hyperconduit$details());
            }
        } catch (RuntimeException ignored) {
            // Advisory only: vanilla must still send the ping request and complete pong handling.
        }
    }

    /**
     * Vanilla returns immediately from {@code onDisconnect} once {@code success} is true, so a
     * status response followed by a missing pong/read-timeout never calls {@code onPingFailed}.
     * Close that hole at TAIL: the legacy fallback (if any) has already been started and may later
     * set INCOMPATIBLE; only an entry that is still PINGING is changed.
     */
    @Inject(method = "onDisconnect", at = @At("TAIL"))
    private void hyperconduit$exitPingingOnDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        try {
            ServerData data = this.hyperconduit$serverData;
            ServerAddress expected = this.hyperconduit$serverAddress;
            if (!(data instanceof CapabilityEntry entry) || expected == null) {
                return;
            }
            boolean sameGeneration = entry.hyperconduit$generation() == this.hyperconduit$generation;
            ServerAddress current = ServerAddress.parseString(data.ip);
            boolean sameAddress = current != null
                    && current.getHost().equals(expected.getHost())
                    && current.getPort() == expected.getPort();
            if (StatusPingLifecycle.shouldExitPinging(
                    sameGeneration, sameAddress, data.state() == ServerData.State.PINGING)) {
                data.setState(ServerData.State.UNREACHABLE);
            }
        } catch (RuntimeException ignored) {
            // Never let the advisory state cleanup disturb vanilla disconnect handling.
        }
    }
}
