package io.hyperconduit.mod.mixin;

import com.google.gson.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import io.hyperconduit.mod.probe.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientboundStatusResponsePacket.class)
public abstract class StatusPacketMixin {
    @Shadow public abstract ServerStatus status();

    @Redirect(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/FriendlyByteBuf;readJsonWithCodec(Lcom/mojang/serialization/Codec;)Ljava/lang/Object;"))
    private static Object hyperconduit$read(FriendlyByteBuf buffer, Codec<ServerStatus> codec) {
        String raw = buffer.readUtf();
        JsonElement json = JsonParser.parseString(raw);
        // Keep vanilla's decode/validation: a genuinely invalid status must still fail.
        ServerStatus status = codec.parse(JsonOps.INSTANCE, json).getOrThrow(message -> new IllegalArgumentException(message));
        // Only the advisory capability attachment is allowed to fail.
        StatusAugmentation.attach(status, raw);
        return status;
    }

    @Redirect(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/FriendlyByteBuf;writeJsonWithCodec(Lcom/mojang/serialization/Codec;Ljava/lang/Object;)V"))
    private void hyperconduit$write(FriendlyByteBuf buffer, Codec<ServerStatus> codec, Object value) {
        ServerStatus status = (ServerStatus) value;
        JsonElement json = codec.encodeStart(JsonOps.INSTANCE, status).getOrThrow(message -> new IllegalArgumentException(message));
        buffer.writeUtf(StatusAugmentation.decorate(json, StatusAugmentation.detailsOf(status)).toString());
    }

    @Redirect(method = "write", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/FriendlyByteBuf;writeUtf(Ljava/lang/String;)Lnet/minecraft/network/FriendlyByteBuf;"))
    private FriendlyByteBuf hyperconduit$cached(FriendlyByteBuf buffer, String cached) {
        try {
            return buffer.writeUtf(StatusAugmentation.decorate(cached, StatusAugmentation.detailsOf(status())));
        } catch (RuntimeException ignored) {
            // Decoration is advisory; keep the server's original cached status on any failure.
            return buffer.writeUtf(cached);
        }
    }
}
