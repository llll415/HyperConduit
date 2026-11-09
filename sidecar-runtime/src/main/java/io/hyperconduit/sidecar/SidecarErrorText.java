package io.hyperconduit.sidecar;

/**
 * Safe, user-facing text for sidecar failures.
 *
 * <p>The original error code remains available through {@link SidecarException#code()}; this class
 * deliberately never embeds an arbitrary code or raw child output in the message. Unknown codes
 * therefore fall back to a generic sentence instead of echoing untrusted text.
 */
public final class SidecarErrorText {

    public static final String PREFIX = "[HyperConduit] ";

    private static final String UNKNOWN = PREFIX + "隧道进程遇到未知错误，请查看日志。";
    private static final String TRUST_UNKNOWN = PREFIX + "无法访问信任记录，请稍后重试。";

    private SidecarErrorText() {
    }

    /** Returns a Chinese, prefixed message for a known sidecar failure code. */
    public static String describe(String code) {
        if (code == null) {
            return UNKNOWN;
        }
        return switch (code) {
            case "listener_port_required" -> PREFIX + "仅隧道模式需要固定的对外端口，请先设置端口。";
            case "listener_failed" -> PREFIX + "无法启动服务器隧道监听，请检查端口与服务器配置。";
            case "identity_changed" -> PREFIX + "服务器身份已变更，连接已中止；请确认后重新信任该服务器。";
            case "authentication" -> PREFIX + "认证失败，请检查客户端与服务器口令是否一致。";
            case "timeout", "startup_timeout", "ready_timeout" ->
                    PREFIX + "连接或启动超时，请检查网络或服务器状态后重试。";
            case "binary_unavailable" -> PREFIX + "找不到可用的隧道程序，请重新安装模组。";
            case "binary_invalid" -> PREFIX + "隧道程序校验失败或已损坏，请重新安装模组。";
            case "config_unavailable" -> PREFIX + "无法读取隧道配置，请检查配置文件权限。";
            case "unsupported_platform" -> PREFIX + "当前系统或处理器架构不受支持，无法启动隧道程序。";
            case "launch_failed" -> PREFIX + "无法启动隧道进程，请检查系统权限或安全软件。";
            case "config" -> PREFIX + "隧道配置无效，请检查设置。";
            case "tls" -> PREFIX + "安全连接建立失败。";
            case "storage" -> PREFIX + "无法访问本地存储，请检查磁盘权限。";
            case "target" -> PREFIX + "无法连接目标服务器。";
            case "address_unresolved" -> PREFIX + "无法解析服务器地址，请检查地址是否正确。";
            case "transport" -> PREFIX + "网络传输失败，请稍后重试。";
            case "limit" -> PREFIX + "请求过于频繁，请稍后重试。";
            case "canceled" -> PREFIX + "操作已取消。";
            case "not_running" -> PREFIX + "隧道进程未运行。";
            case "unexpected_exit", "exited_before_ready" -> PREFIX + "隧道进程意外退出，请查看日志。";
            case "interrupted" -> PREFIX + "操作被中断。";
            case "output_unavailable" -> PREFIX + "无法读取隧道进程输出。";
            case "protocol_version", "protocol_invalid", "protocol_mode", "protocol_fingerprint",
                    "protocol_duplicate_ready", "protocol_event", "protocol_state",
                    "protocol_capability", "protocol_line_too_long" ->
                    PREFIX + "隧道通信协议异常，连接已中止。";
            default -> UNKNOWN;
        };
    }

    /** Returns a Chinese, prefixed message for a trust command failure. */
    public static String describeTrust(Throwable failure) {
        String code = failure == null ? null : failure.getMessage();
        if (code == null) {
            return TRUST_UNKNOWN;
        }
        return switch (code) {
            case "trust_command_timeout" -> PREFIX + "读取信任记录超时，请稍后重试。";
            case "trust_command_canceled" -> PREFIX + "信任记录操作已取消。";
            case "trust_protocol_version" -> PREFIX + "信任记录协议不兼容，请更新模组。";
            case "trust_response_limit" -> PREFIX + "信任记录过大，无法安全读取。";
            case "trust_command_failed" -> PREFIX + "无法访问信任记录，请确认隧道程序可用。";
            case "binary_unavailable", "binary_invalid", "unsupported_platform", "config_unavailable",
                    "launch_failed" -> describe(code);
            default -> TRUST_UNKNOWN;
        };
    }
}
