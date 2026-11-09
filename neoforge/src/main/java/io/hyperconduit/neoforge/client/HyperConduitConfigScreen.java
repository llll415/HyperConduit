package io.hyperconduit.neoforge.client;

import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.function.Consumer;

/**
 * Compact two-column editor for the HY2 configuration.
 *
 * <p>Token fields are intentionally blank on open: an existing token is never rendered in plaintext.
 * Typing a new value replaces it, while leaving the field empty keeps the stored token.
 */
public final class HyperConduitConfigScreen extends Screen {

    private static final int TITLE_COLOR = 0xFFFFFF;
    private static final int LABEL_COLOR = 0xE0E0E0;
    private static final int HINT_COLOR = 0xE8E8E8;
    private static final int ERROR_COLOR = 0xFF8080;
    private static final String ERROR_PREFIX = "[HyperConduit] ";
    private static final int ROW = 22;
    private static final int TOP = 36;

    private final Screen parent;
    private final HyperConduitConfig config;

    private int leftX;
    private int rightX;
    private int labelWidth;

    private EditBox upMbps;
    private EditBox downMbps;
    private EditBox clientToken;
    private EditBox serverToken;
    private EditBox connectTimeout;
    private EditBox maxConnections;
    private String errorMessage;

    public HyperConduitConfigScreen(Screen parent) {
        super(Component.literal("HyperConduit"));
        this.parent = parent;
        HyperConduitConfig current = ConfigHolder.get();
        this.config = current == null ? new HyperConduitConfig() : current.copy();
    }

    @Override
    protected void init() {
        int columnWidth = Math.min(190, Math.max(120, (width - 24) / 2));
        labelWidth = Math.min(84, columnWidth / 2);
        int fieldWidth = columnWidth - labelWidth;
        leftX = width / 2 - columnWidth - 4;
        rightX = width / 2 + 4;

        int leftY = TOP;
        addRenderableWidget(toggleButton(leftX, leftY, columnWidth, "启用 HyperConduit", config.enabled,
                value -> config.enabled = value));
        leftY += ROW;

        upMbps = numericBox(leftX + labelWidth, leftY, fieldWidth, config.upMbps, "发送 Mbps");
        upMbps.setTooltip(Tooltip.create(Component.literal("客户端：上传带宽；服务端：单连接 Brutal 发送上限。0 不声明带宽")));
        addRenderableWidget(upMbps);
        leftY += ROW;

        downMbps = numericBox(leftX + labelWidth, leftY, fieldWidth, config.downMbps, "接收 Mbps");
        downMbps.setTooltip(Tooltip.create(Component.literal("客户端：下载带宽；服务端：单连接 Brutal 接收上限。0 不声明带宽")));
        addRenderableWidget(downMbps);
        leftY += ROW;

        Button congestion = Button.builder(congestionLabel(config.congestion), button -> {
            config.congestion = isBbr(config.congestion) ? "reno" : "bbr";
            button.setMessage(congestionLabel(config.congestion));
        }).bounds(leftX, leftY, columnWidth, 20).build();
        congestion.setTooltip(Tooltip.create(Component.literal("BBR 适合高带宽；Reno 更保守")));
        addRenderableWidget(congestion);
        leftY += ROW;

        Button disableBrutal = toggleButton(leftX, leftY, columnWidth, "禁用 Brutal", config.disableBrutal,
                value -> config.disableBrutal = value);
        disableBrutal.setTooltip(Tooltip.create(Component.literal("仅主机生效：双向改用本地 BBR/Reno，不拒绝连接")));
        addRenderableWidget(disableBrutal);
        leftY += ROW;

        Button disableTcp = toggleButton(leftX, leftY, columnWidth, "禁止原版 TCP", config.disableVanillaTcp,
                value -> config.disableVanillaTcp = value);
        disableTcp.setTooltip(Tooltip.create(Component.literal("仅主机生效：只接受 HyperConduit 隧道连接")));
        addRenderableWidget(disableTcp);
        leftY += ROW;

        addRenderableWidget(Button.builder(Component.literal("已信任服务器…"), button ->
                        minecraft.setScreen(new KnownServersScreen(this)))
                .bounds(leftX, leftY, columnWidth, 20).build());

        int rightY = TOP;
        clientToken = tokenBox(rightX + labelWidth, rightY, fieldWidth, config.clientToken, "客户端口令");
        addRenderableWidget(clientToken);
        rightY += ROW;

        serverToken = tokenBox(rightX + labelWidth, rightY, fieldWidth, config.serverToken, "服务器口令");
        addRenderableWidget(serverToken);
        rightY += ROW;

        connectTimeout = numericBox(rightX + labelWidth, rightY, fieldWidth, config.connectTimeoutMs, "连接超时");
        addRenderableWidget(connectTimeout);
        rightY += ROW;

        maxConnections = numericBox(rightX + labelWidth, rightY, fieldWidth, config.maxConnections, "最大连接数");
        addRenderableWidget(maxConnections);
        rightY += ROW + 4;
        addRenderableWidget(Button.builder(Component.literal("清除客户端口令"), button -> {
            config.clientToken = ""; clientToken.setValue(""); clientToken.setHint(Component.literal("留空不使用"));
        }).bounds(rightX,rightY,columnWidth,20).build());
        rightY += ROW;
        addRenderableWidget(Button.builder(Component.literal("清除服务器口令"), button -> {
            config.serverToken = ""; serverToken.setValue(""); serverToken.setHint(Component.literal("留空不使用"));
        }).bounds(rightX,rightY,columnWidth,20).build());

        int bottom = height - 28;
        int buttonWidth = Math.min(98, (width - 30) / 2);
        addRenderableWidget(Button.builder(Component.literal("保存"), button -> saveAndClose())
                .bounds(width / 2 - buttonWidth - 2, bottom, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(width / 2 + 2, bottom, buttonWidth, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        drawCentered(graphics, title, 10, TITLE_COLOR);
        if (errorMessage != null) {
            drawCentered(graphics, Component.literal(errorMessage), 24, ERROR_COLOR);
        } else {
            drawCentered(graphics, Component.literal("保存后将在下一次连接或开放局域网时生效"), 24, HINT_COLOR);
        }

        drawFieldLabel(graphics, "发送Mbps", upMbps);
        drawFieldLabel(graphics, "接收Mbps", downMbps);
        drawFieldLabel(graphics, "客户端口令", clientToken);
        drawFieldLabel(graphics, "服务器口令", serverToken);
        drawFieldLabel(graphics, "超时(ms)", connectTimeout);
        drawFieldLabel(graphics, "最大连接", maxConnections);
    }

    private void drawCentered(GuiGraphics graphics, Component text, int y, int color) {
        graphics.drawString(font, text, (width - font.width(text)) / 2, y, color, true);
    }

    private void drawFieldLabel(GuiGraphics graphics, String text, EditBox box) {
        if (box == null) {
            return;
        }
        graphics.drawString(font, text, box.getX() - labelWidth, box.getY() + 6, LABEL_COLOR, true);
    }

    private Button toggleButton(int x, int y, int width, String label, boolean initial,
                                Consumer<Boolean> setter) {
        boolean[] state = {initial};
        return Button.builder(toggleLabel(label, initial), button -> {
            state[0] = !state[0];
            button.setMessage(toggleLabel(label, state[0]));
            setter.accept(state[0]);
        }).bounds(x, y, width, 20).build();
    }

    private EditBox numericBox(int x, int y, int width, int value, String narration) {
        EditBox box = new EditBox(font, x, y, width, 20, Component.literal(narration));
        box.setFilter(text -> text.isEmpty() || text.matches("\\d{0,8}"));
        box.setValue(Integer.toString(value));
        return box;
    }

    private EditBox tokenBox(int x, int y, int width, String existing, String narration) {
        EditBox box = new EditBox(font, x, y, width, 20, Component.literal(narration));
        box.setMaxLength(1024);
        box.setFilter(text -> text.length() <= 1024
                && text.chars().allMatch(c -> c >= 0x20 && c <= 0x7E));
        box.setHint(Component.literal(existing.isEmpty() ? "留空不使用" : "已设置·留空保持"));
        box.setFormatter((text, cursor) -> maskToken(text));
        // Never put the stored token into the box: it must not be rendered in plaintext.
        return box;
    }

    private static FormattedCharSequence maskToken(String text) {
        if (text.isEmpty()) {
            return FormattedCharSequence.EMPTY;
        }
        return FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY);
    }

    private void saveAndClose() {
        try {
            config.upMbps = parse(upMbps, 0);
            config.downMbps = parse(downMbps, 0);
            config.connectTimeoutMs = parse(connectTimeout, 5000);
            config.maxConnections = parse(maxConnections, 64);

            String client = clientToken.getValue();
            if (!client.isEmpty()) {
                config.clientToken = client;
            }
            String server = serverToken.getValue();
            if (!server.isEmpty()) {
                config.serverToken = server;
            }

            ConfigHolder.save(config);
            minecraft.setScreen(parent);
        } catch (RuntimeException e) {
            errorMessage = friendlyError(e.getMessage());
        }
    }

    private static int parse(EditBox box, int fallback) {
        String value = box.getValue().trim();
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String friendlyError(String code) {
        if (code == null) {
            return ERROR_PREFIX + "保存失败，请检查配置。";
        }
        String message = switch (code) {
            case "config_up_mbps", "config_down_mbps" -> "带宽必须在 0–1000000 Mbps 之间。";
            case "config_congestion" -> "拥塞控制只能是 BBR 或 Reno。";
            case "config_client_token", "config_server_token" ->
                    "口令只能包含可打印 ASCII 字符（≤1024，首尾不能为空白）。";
            case "config_connect_timeout" -> "连接超时必须在 100–120000 毫秒之间。";
            case "config_max_connections" -> "最大连接数必须在 1–4096 之间。";
            case "config_write", "config_directory", "config_permissions" -> "无法安全写入配置文件。";
            case "config_too_large" -> "配置文件过大。";
            default -> "保存失败，请检查配置。";
        };
        return ERROR_PREFIX + message;
    }

    private static Component toggleLabel(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "开启" : "关闭"));
    }

    private static Component congestionLabel(String congestion) {
        return Component.literal("拥塞控制: " + (isBbr(congestion) ? "BBR" : "Reno"));
    }

    private static boolean isBbr(String congestion) {
        return congestion != null && congestion.equalsIgnoreCase("bbr");
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
