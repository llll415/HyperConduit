package io.hyperconduit.neoforge.client;

import io.hyperconduit.mod.bridge.TrustService;
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
    private String notice = "正在读取…";
    private int page;
    private int request;

    KnownServersScreen(Screen parent) {
        super(Component.literal("HyperConduit 已信任服务器"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        load();
        buildButtons();
    }

    private void load() {
        int generation = ++request;
        Thread.ofVirtual().start(() -> {
            try {
                var loaded = TrustService.list();
                minecraft.execute(() -> {
                    if (minecraft.screen != this || generation != request) return;
                    entries.clear();
                    entries.addAll(loaded.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList());
                    notice = entries.isEmpty() ? "暂无已信任服务器" : "";
                    clearWidgets(); buildButtons();
                });
            } catch (Exception failure) {
                minecraft.execute(() -> { if (generation == request) notice = "无法读取信任记录"; });
            }
        });
    }

    private void buildButtons() {
        int left = width / 2 - 150;
        int y = height / 2 - 78;
        page = Math.min(page, Math.max(0, (entries.size() - 1) / 5));
        for (int i = 0; i < Math.min(entries.size() - page * 5, 5); i++) {
            int index = page * 5 + i;
            Map.Entry<String, String> entry = entries.get(index);
            addRenderableWidget(Button.builder(Component.literal(entry.getKey()), button -> selected = index)
                    .bounds(left, y + i * 22, 300, 20).build());
        }
        if (entries.size() > 5) {
            addRenderableWidget(Button.builder(Component.literal("上一页"), button -> {
                page = Math.max(0, page - 1); selected = -1; clearWidgets(); buildButtons();
            }).bounds(left, y - 25, 70, 20).build());
            addRenderableWidget(Button.builder(Component.literal("下一页"), button -> {
                page = Math.min((entries.size() - 1) / 5, page + 1); selected = -1; clearWidgets(); buildButtons();
            }).bounds(left + 230, y - 25, 70, 20).build());
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
        if (!notice.isEmpty() && !entries.isEmpty()) graphics.drawCenteredString(font, notice, width / 2, height / 2 + 65, 0xFF5555);
        if (entries.isEmpty()) {
            graphics.drawCenteredString(font, notice, width / 2, height / 2 - 10, 0xA0A0A0);
        } else if (selected >= 0 && selected < entries.size()) {
            String fingerprint = entries.get(selected).getValue();
            graphics.drawCenteredString(font, "指纹: " + fingerprint, width / 2, height / 2 + 88, 0xA0A0A0);
        }
    }

    private void removeSelected() {
        if (selected < 0 || selected >= entries.size()) return;
        String endpoint = entries.get(selected).getKey();
        selected = -1;
        Thread.ofVirtual().start(() -> {
            try {
                TrustService.forget(endpoint);
                minecraft.execute(this::load);
            } catch (Exception failure) {
                minecraft.execute(() -> notice = "无法删除信任记录");
            }
        });
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
