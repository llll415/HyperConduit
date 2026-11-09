package io.hyperconduit.mod.client;

import io.hyperconduit.mod.bridge.ConnectionAttemptScope;
import io.hyperconduit.mod.bridge.TransportChoicePolicy;
import io.hyperconduit.mod.bridge.TransportConsent;
import io.hyperconduit.mod.config.HyperConduitConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Connection-local consent. Leaving this screen invalidates every late discovery response. */
public final class TransportPreflightScreen extends Screen {
    private final Screen parent;
    private final ServerAddress address;
    private final ServerData data;
    private final boolean quickPlay;
    private final TransferState transfer;
    private final TransportConsent consent;
    private TransportDiscovery discovery;
    private TransportChoicePolicy.Decision decision = TransportChoicePolicy.Decision.CURRENT;
    private Button confirm;
    private boolean started;
    private boolean closed;
    private boolean completed;

    public TransportPreflightScreen(Screen parent, ServerAddress address, ServerData data,
                                     boolean quickPlay, TransferState transfer, HyperConduitConfig config) {
        super(Component.literal("HyperConduit 连接检查"));
        this.parent = parent;
        this.address = address;
        this.data = data;
        this.quickPlay = quickPlay;
        this.transfer = transfer;
        this.consent = new TransportConsent(config);
    }

    @Override protected void init() {
        int buttonWidth = Math.min(310, width - 24);
        int x = (width - buttonWidth) / 2;
        int y = Math.max(height / 2 + 35, height - 66);
        if (decision != TransportChoicePolicy.Decision.CURRENT) {
            String action = decision == TransportChoicePolicy.Decision.ASK_TCP
                    ? "本次使用原版 TCP" : "本次启用 HyperConduit 隧道连接";
            confirm = addRenderableWidget(Button.builder(Component.literal(action), button ->
                    connect(true))
                    .bounds(x, y, buttonWidth, 20).build());
            confirm.active = false;
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(x, y + 24, buttonWidth, 20).build());
        setInitialFocus(children().get(children().size() - 1));
        if (!started) {
            started = true;
            final net.minecraft.client.Minecraft client = minecraft;
            discovery = new TransportDiscovery(address);
            discovery.result().whenComplete((result, failure) -> client.execute(() -> {
                if (closed || completed || client.screen != this) return;
                boolean valid = failure == null && result != null;
                if (!consent.discovered(valid && result.declared(), valid && result.enabled(), valid && result.tcpAllowed())) return;
                decision = consent.decision();
                if (decision == TransportChoicePolicy.Decision.CURRENT) connect(false);
                else {
                    rebuildWidgets();
                    clearFocus();
                }
            }));
        }
    }

    @Override public void tick() {
        // Do not let the Enter key that opened the connection also accept a new consent prompt.
        if (confirm != null && !confirm.active
                && !com.mojang.blaze3d.platform.InputConstants.isKeyDown(minecraft.getWindow().getWindow(), 257)
                && !com.mojang.blaze3d.platform.InputConstants.isKeyDown(minecraft.getWindow().getWindow(), 335)) {
            confirm.active = true;
        }
    }

    private void connect(boolean explicitConsent) {
        if (closed || completed || minecraft.screen != this) return;
        HyperConduitConfig attempt = consent.proceed(explicitConsent);
        if (attempt == null) return;
        completed = true;
        try (var ignored = ConnectionAttemptScope.enter(attempt)) {
            ConnectScreen.startConnecting(parent, minecraft, address, data, quickPlay, transfer);
        }
    }

    @Override public void onClose() {
        if (closed || completed) return;
        closed = true;
        consent.cancel();
        if (discovery != null) discovery.close();
        minecraft.setScreen(parent);
    }

    @Override public void removed() {
        closed = true;
        consent.cancel();
        if (discovery != null) discovery.close();
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 24, 0xFFFFFF);
        String message = switch (decision) {
            case CURRENT -> "正在检查服务器的 HyperConduit 连接声明…";
            case ASK_TCP -> "服务器声明未启用 HyperConduit，允许原版 TCP。是否仅本次使用原版 TCP？"
                    + "这将不使用 HyperConduit 隧道保护及服务器身份校验，即使此地址已有信任记录也不会校验。"
                    + "声明未经身份认证，可能过期或被干扰。不会修改全局设置或已有信任。";
            case ASK_TUNNEL -> "服务器声明禁止原版 TCP。是否仅本次启用 HyperConduit 隧道连接？"
                    + "声明未经身份认证，实际连接仍须通过正常的身份校验及口令认证。不会修改全局设置。";
        };
        int textWidth = Math.max(1, Math.min(440, width - 32));
        int y = 50;
        for (var line : font.split(Component.literal(message), textWidth)) {
            graphics.drawString(font, line, (width - textWidth) / 2, y, 0xFFFFFF, true);
            y += font.lineHeight + 3;
        }
    }

    @Override public Component getNarrationMessage() {
        return switch (decision) {
            case CURRENT -> Component.literal("HyperConduit：正在检查服务器连接声明，可取消。");
            case ASK_TCP -> Component.literal("服务器声明未启用 HyperConduit，允许原版 TCP。"
                    + "本次使用原版 TCP 将不使用 HyperConduit 隧道保护与服务器身份校验，"
                    + "已有信任记录也不会校验。声明未经认证，可能过期或被干扰。请选择本次使用原版 TCP或取消。");
            case ASK_TUNNEL -> Component.literal("服务器声明禁止原版 TCP。请选择本次启用 HyperConduit 隧道连接或取消。"
                    + "声明未经认证，实际连接仍需身份校验与口令认证，不修改全局设置。");
        };
    }

    @Override public boolean isPauseScreen() { return false; }
}
