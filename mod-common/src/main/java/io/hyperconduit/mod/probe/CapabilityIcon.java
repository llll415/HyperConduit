package io.hyperconduit.mod.probe;

import java.util.List;

/** Geometry shared with tests: a 12px badge and 2px gap left of vanilla's 10px signal. */
public final class CapabilityIcon {
    private CapabilityIcon() {}

    public static int left(int entryLeft, int entryWidth) { return entryLeft + entryWidth - 15 - 14; }

    public static boolean hovered(int left, int top, int mouseX, int mouseY) {
        return mouseX >= left && mouseX < left + 12 && mouseY >= top && mouseY < top + 9;
    }

    public static int color(Capability state) {
        return switch (state) {
            case ENABLED -> 0xFF55DD88;
            case DISABLED -> 0xFFE0B04B;
            case INCOMPATIBLE -> 0xFFFF6666;
            case CHECKING -> 0xFF77AADD;
            case UNDECLARED, UNKNOWN -> 0xFF999999;
        };
    }

    public static String description(Capability state) {
        return switch (state) {
            case ENABLED -> "HyperConduit：已启用";
            case DISABLED -> "HyperConduit：服务器未启用";
            case UNDECLARED -> "HyperConduit：未声明支持";
            case UNKNOWN -> "HyperConduit：未知或探测超时";
            case INCOMPATIBLE -> "HyperConduit：协议不兼容";
            case CHECKING -> "HyperConduit：正在探测…";
        };
    }

    /**
     * Four policy lines shown below the state. A state without a complete compatible declaration
     * produces no policy lines, so unknown/timeout is never presented as "not installed".
     */
    public static List<String> details(CapabilityDetails details) {
        if (details == null || !details.policyComplete()) return List.of();
        return List.of(
                "支持证据：已安装并声明",
                "TCP 直连：" + (details.tcpAllowed() ? "允许" : "不允许"),
                "Brutal 拥塞控制：" + (details.disableBrutal() ? "已禁用" : "启用"),
                "访问令牌：" + (details.tokenRequired() ? "需要" : "不需要"));
    }
}
