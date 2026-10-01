package io.hyperconduit.neoforge.client;

import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public final class HyperConduitConfigScreen extends Screen {

    private final Screen parent;
    private final HyperConduitConfig config;
    private EditBox mbps;

    public HyperConduitConfigScreen(Screen parent) {
        super(Component.literal("HyperConduit"));
        this.parent = parent;
        HyperConduitConfig current = ConfigHolder.get();
        this.config = copyOf(current == null ? new HyperConduitConfig() : current);
    }

    @Override
    protected void init() {
        int left = width / 2 - 100;
        int y = height / 2 - 80;
        addRenderableWidget(toggleButton(left, y, "启用 HyperConduit", config.enabled,
                enabled -> config.enabled = enabled));
        y += 26;
        mbps = new EditBox(font, left, y, 200, 20, Component.literal("带宽 Mbps"));
        mbps.setValue(Integer.toString(config.mbps));
        mbps.setFilter(value -> value.isEmpty() || value.matches("\\d{1,4}"));
        addRenderableWidget(mbps);
        y += 26;
        addRenderableWidget(toggleButton(left, y, "Brutal 拥塞控制", config.brutal,
                enabled -> config.brutal = enabled));
        y += 26;
        addRenderableWidget(toggleButton(left, y, "禁止原版 TCP 客户端", config.disableVanillaTcp,
                disabled -> config.disableVanillaTcp = disabled));
        y += 32;
        addRenderableWidget(Button.builder(Component.literal("已信任服务器…"), button ->
                        minecraft.setScreen(new KnownServersScreen(this)))
                .bounds(left, y, 200, 20).build());
        y += 28;
        addRenderableWidget(Button.builder(Component.literal("保存"), button -> saveAndClose())
                .bounds(left, y, 98, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(left + 102, y, 98, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 110, 0xFFFFFF);
        graphics.drawCenteredString(font, "保存的设置仅在下一次连接或下一次开放局域网时生效", width / 2,
                height / 2 + 86, 0xA0A0A0);
    }

    private Button toggleButton(int x, int y, String label, boolean initial,
                                java.util.function.Consumer<Boolean> setter) {
        boolean[] state = {initial};
        return Button.builder(Component.literal(label + ": " + (initial ? "开启" : "关闭")), button -> {
                    state[0] = !state[0];
                    button.setMessage(Component.literal(label + ": " + (state[0] ? "开启" : "关闭")));
                    setter.accept(state[0]);
                })
                .bounds(x, y, 200, 20)
                .build();
    }

    private void saveAndClose() {
        try {
            config.mbps = Math.max(1, Integer.parseInt(mbps.getValue()));
        } catch (NumberFormatException ignored) {
            config.mbps = 10;
        }
        ConfigHolder.save(config);
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    static HyperConduitConfig copyOf(HyperConduitConfig source) {
        HyperConduitConfig copy = new HyperConduitConfig();
        copy.enabled = source.enabled;
        copy.mbps = source.mbps;
        copy.brutal = source.brutal;
        copy.disableVanillaTcp = source.disableVanillaTcp;
        copy.receiveWindowBytes = source.receiveWindowBytes;
        copy.sendBufferBytes = source.sendBufferBytes;
        return copy;
    }
}
