package io.hyperconduit.mod.probe;

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
}
