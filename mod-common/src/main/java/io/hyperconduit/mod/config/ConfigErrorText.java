package io.hyperconduit.mod.config;

/**
 * Safe, user-facing Chinese text for configuration failures.
 *
 * <p>Code-only messages are the internal contract of {@link HyperConduitConfig} /
 * {@link ConfigHolder}; this class is the single place that turns them into text for connection
 * errors and commands. It never echoes a raw code, file path, token or other configuration value.
 */
public final class ConfigErrorText {

    public static final String PREFIX = "[HyperConduit] ";

    private static final String GENERIC = PREFIX + "无法读取 HyperConduit 配置，请检查配置文件。";
    private static final String READ = PREFIX + "无法读取 HyperConduit 配置，请检查文件权限。";
    private static final String INVALID = PREFIX + "HyperConduit 配置文件格式无效，请修正后重试。";
    private static final String TOO_LARGE = PREFIX + "HyperConduit 配置文件过大。";
    private static final String VALUE = PREFIX + "HyperConduit 配置值无效，请检查设置。";
    private static final String CONFLICT = PREFIX + "HyperConduit 配置文件已被外部修改，请重新打开设置。";
    private static final String WRITE = PREFIX + "无法保存 HyperConduit 配置文件。";

    private ConfigErrorText() {
    }

    /** Returns safe Chinese text for a failure; never includes its raw message unless already safe. */
    public static String describe(Throwable failure) {
        if (failure instanceof ConfigHolder.ConfigAccessException access) {
            return access.getMessage();
        }
        String message = failure == null ? null : failure.getMessage();
        if (message != null && message.startsWith(PREFIX)) {
            return message;
        }
        return describe(message);
    }

    /** Returns safe Chinese text for a known configuration code. */
    public static String describe(String code) {
        if (code == null) {
            return GENERIC;
        }
        return switch (code) {
            case "config_read", "config_permissions", "config_directory" -> READ;
            case "config_unavailable" -> GENERIC;
            case "config_invalid", "config_duplicate_field", "config_unknown_field" -> INVALID;
            case "config_too_large" -> TOO_LARGE;
            case "config_conflict" -> CONFLICT;
            case "config_write", "config_atomic_replace_unavailable" -> WRITE;
            case "config_up_mbps", "config_down_mbps", "config_congestion", "config_client_token",
                    "config_server_token", "config_connect_timeout", "config_max_connections",
                    "config_proxy_protocol_source", "config_proxy_protocol_sources_required",
                    "config_proxy_protocol_sources_limit" -> VALUE;
            default -> GENERIC;
        };
    }
}