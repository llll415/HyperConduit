package io.hyperconduit.velocity.status;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.status.server.WrapperStatusServerResponse;
import com.google.gson.JsonObject;
import io.hyperconduit.velocity.config.VelocityConfig;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Adds the advisory {@code hyperconduit} object to the complete status JSON.
 *
 * <p>PacketEvents reconstructs the status packet from its JSON component, so
 * all unrelated fields survive. Any decoding, serialization or version failure
 * is swallowed: the proxy's status response must remain exactly as it would
 * have been without this listener.</p>
 */
public final class PacketEventsStatusListener extends PacketListenerAbstract {

    private final Supplier<VelocityConfig> runningConfig;

    public PacketEventsStatusListener(Supplier<VelocityConfig> runningConfig) {
        super(PacketListenerPriority.NORMAL);
        this.runningConfig = Objects.requireNonNull(runningConfig, "runningConfig");
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        try {
            if (event.getPacketType() != PacketType.Status.Server.RESPONSE) {
                return;
            }
            JsonObject declaration = StatusDeclaration.fromRunning(runningConfig.get());
            if (declaration == null) {
                return;
            }
            WrapperStatusServerResponse response = new WrapperStatusServerResponse(event);
            JsonObject status = response.getComponent();
            if (status == null) {
                return;
            }
            response.setComponent(StatusJson.augment(status, declaration));
        } catch (RuntimeException | LinkageError unavailable) {
            // Status augmentation is advisory. Never fail or replace the
            // original status response because PacketEvents is unavailable.
        }
    }
}