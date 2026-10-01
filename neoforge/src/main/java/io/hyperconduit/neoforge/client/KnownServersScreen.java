package io.hyperconduit.neoforge.client;

import io.hyperconduit.mod.config.ConfigHolder;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class KnownServersScreen extends Screen {

    private final Screen parent;
    private final List<Map.Entry<String, String>> entries = new ArrayList<>();
    private int selected = -1;

    KnownServersScreen(Screen parent) {
        super(Component.literal("HyperConduit 已信任服务器"));
        this.parent = parent;
        entries.addAll(ConfigHolder.knownServers().entries().entrySet());
    }

    @Override
    protected void init() {
        int left = width / 2 - 150;
        int y = height / 2 - 78;
        for (int i = 0; i < Math.min(entries.size(), 5); i++) {
            int index = i;
            Map.Entry<String, String> entry = entries.get(i);
            addRenderableWidget(Button.builder(Component.literal(entry.getKey()), button -> selected = index)
                    .bounds(left, y + i * 22, 300, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("删除选中信任"), button -> removeSelected())
                .bounds(left, y + 118, 148, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(left + 152, y + 118, 148, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 108, 0xFFFFFF);
        if (entries.isEmpty()) {
            graphics.drawCenteredString(font, "暂无已信任服务器", width / 2, height / 2 - 10, 0xA0A0A0);
        } else if (selected >= 0 && selected < entries.size()) {
            String fingerprint = entries.get(selected).getValue();
            graphics.drawCenteredString(font, "指纹: " + fingerprint, width / 2, height / 2 + 88, 0xA0A0A0);
        }
    }

    private void removeSelected() {
        if (selected < 0 || selected >= entries.size()) return;
        String endpoint = entries.get(selected).getKey();
        int colon = endpoint.lastIndexOf(':');
        String host = endpoint.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        ConfigHolder.knownServers().remove(host, Integer.parseInt(endpoint.substring(colon + 1)));
        entries.remove(selected);
        selected = -1;
        minecraft.setScreen(new KnownServersScreen(parent));
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
