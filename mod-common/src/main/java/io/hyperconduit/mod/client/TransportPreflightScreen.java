package io.hyperconduit.mod.client;

import io.hyperconduit.mod.bridge.ConnectionAttemptScope;
import io.hyperconduit.mod.bridge.TransportChoicePolicy;
import io.hyperconduit.mod.bridge.TransportConsent;
import io.hyperconduit.mod.config.HyperConduitConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Connection-local consent, laid out like vanilla's DisconnectedScreen: a centered short message
 * over the blurred vanilla background, followed by standard-width buttons. Leaving this screen
 * invalidates every late discovery response.
 */
public final class TransportPreflightScreen extends Screen {
    private static final int MIN_BUTTON_WIDTH = 200;
    private static final int MAX_BUTTON_WIDTH = 310;

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
        // No title is drawn; narration is supplied below.
        super(Component.empty());
        this.parent = parent;
        this.address = address;
        this.data = data;
        this.quickPlay = quickPlay;
        this.transfer = transfer;
        this.consent = new TransportConsent(config);
    }

    @Override protected void init() {
        LinearLayout layout = LinearLayout.vertical();
        layout.defaultCellSetting().alignHorizontallyCenter().padding(10);
        layout.addChild(new MultiLineTextWidget(message(), font)
                .setMaxWidth(Math.max(1, width - 50)).setCentered(true));
        layout.defaultCellSetting().padding(2);
        int buttonWidth = buttonWidth();
        if (decision != TransportChoicePolicy.Decision.CURRENT) {
            confirm = layout.addChild(Button.builder(Component.literal(confirmLabel()), button ->
                            connect(true))
                    .width(buttonWidth).build());
            confirm.active = false;
        }
        layout.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .width(buttonWidth).build());
        layout.arrangeElements();
        FrameLayout.centerInRectangle(layout, getRectangle());
        layout.visitWidgets(this::addRenderableWidget);
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

    private int buttonWidth() {
        int textWidth = decision == TransportChoicePolicy.Decision.CURRENT
                ? 0 : font.width(confirmLabel()) + 24;
        int preferred = Math.max(MIN_BUTTON_WIDTH, textWidth);
        return Math.min(Math.min(MAX_BUTTON_WIDTH, Math.max(1, width - 24)), preferred);
    }

    private String confirmLabel() {
        return decision == TransportChoicePolicy.Decision.ASK_TCP
                ? "本次使用原版 TCP" : "本次启用 HyperConduit 隧道连接";
    }

    private Component message() {
        return switch (decision) {
            case CURRENT -> Component.literal("[HyperConduit] 正在检查服务器…");
            case ASK_TCP -> Component.literal(
                    "[HyperConduit] 服务器未启用 HyperConduit，允许原版 TCP。\n\n"
                            + "是否改用原版 TCP？本次不使用隧道保护和服务器身份校验。");
            case ASK_TUNNEL -> Component.literal(
                    "[HyperConduit] 服务器禁止原版 TCP。\n\n"
                            + "是否启用 HyperConduit 隧道连接？");
        };
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

    @Override public Component getNarrationMessage() {
        return switch (decision) {
            case CURRENT -> Component.literal("[HyperConduit] 正在检查服务器… 可取消。");
            case ASK_TCP -> Component.literal(
                    "[HyperConduit] 服务器未启用 HyperConduit，允许原版 TCP。"
                            + "是否改用原版 TCP？本次不使用隧道保护和服务器身份校验。");
            case ASK_TUNNEL -> Component.literal(
                    "[HyperConduit] 服务器禁止原版 TCP。是否启用 HyperConduit 隧道连接？");
        };
    }

    @Override public boolean isPauseScreen() { return false; }
}
