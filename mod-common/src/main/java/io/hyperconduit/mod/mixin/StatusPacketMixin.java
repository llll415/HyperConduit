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
        ServerStatus status = codec.parse(JsonOps.INSTANCE, json).getOrThrow(message -> new IllegalArgumentException(message));
        ((CapabilityStatus) (Object) status).hyperconduit$capability(CapabilityJson.read(raw));
        return status;
    }

    @Redirect(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/FriendlyByteBuf;writeJsonWithCodec(Lcom/mojang/serialization/Codec;Ljava/lang/Object;)V"))
    private void hyperconduit$write(FriendlyByteBuf buffer, Codec<ServerStatus> codec, Object value) {
        JsonElement json = codec.encodeStart(JsonOps.INSTANCE, (ServerStatus) value).getOrThrow(message -> new IllegalArgumentException(message));
        buffer.writeUtf(hyperconduit$decorate(json, (ServerStatus) value).toString());
    }

    @Redirect(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/FriendlyByteBuf;writeUtf(Ljava/lang/String;)Lnet/minecraft/network/FriendlyByteBuf;"))
    private FriendlyByteBuf hyperconduit$cached(FriendlyByteBuf buffer, String cached) {
        try { return buffer.writeUtf(hyperconduit$decorate(JsonParser.parseString(cached), status()).toString()); }
        catch (JsonParseException invalid) { return buffer.writeUtf(cached); }
    }

    private static JsonElement hyperconduit$decorate(JsonElement json, ServerStatus status) {
        Capability state = ((CapabilityStatus) (Object) status).hyperconduit$capability();
        if (json.isJsonObject() && (state == Capability.ENABLED || state == Capability.DISABLED)) {
            json.getAsJsonObject().add("hyperconduit", Capability.declaration(state == Capability.ENABLED));
        }
        return json;
    }
}
