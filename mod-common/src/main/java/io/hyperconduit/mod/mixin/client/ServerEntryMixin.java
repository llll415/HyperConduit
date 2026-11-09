package io.hyperconduit.mod.mixin.client;

import io.hyperconduit.mod.probe.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(targets = "net.minecraft.client.gui.screens.multiplayer.ServerSelectionList$OnlineServerEntry")
public abstract class ServerEntryMixin {
    @Shadow @Final private ServerData serverData;
    @Shadow @Final private JoinMultiplayerScreen screen;

    // Only the right-aligned Component has this overload; name and MOTD use other overloads.
    // Reserve the badge before vanilla computes both text and player-list hover bounds.
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I"))
    private int hyperconduit$reserveBadge(Font font, FormattedText text) {
        return font.width(text) + 14;
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void hyperconduit$badge(GuiGraphics graphics, int index, int top, int left, int width, int height,
                                     int mouseX, int mouseY, boolean hovering, float partialTick, CallbackInfo ci) {
        ((DiscoveryOwner) screen).hyperconduit$discover(serverData);
        CapabilityDetails details = ((CapabilityEntry) serverData).hyperconduit$details();
        Capability state = details.state();
        // Badge stays immediately left of vanilla's signal and never covers the version text.
        int x = CapabilityIcon.left(left, width);
        graphics.fill(x, top, x + 12, top + 9, 0xCC182028);
        graphics.pose().pushPose();
        graphics.pose().translate(x + 1, top + 1, 0);
        graphics.pose().scale(0.75f, 0.75f, 1);
        graphics.drawString(Minecraft.getInstance().font, "HC", 0, 0, CapabilityIcon.color(state), false);
        graphics.pose().popPose();
        if (CapabilityIcon.hovered(x, top, mouseX, mouseY)) {
            List<FormattedCharSequence> tooltip = new ArrayList<>();
            tooltip.add(Component.literal(CapabilityIcon.description(state)).getVisualOrderText());
            for (String line : CapabilityIcon.details(details)) {
                tooltip.add(Component.literal(line).getVisualOrderText());
            }
            screen.setTooltipForNextRenderPass(tooltip);
        }
    }
}
