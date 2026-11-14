package io.hyperconduit.velocity.command;

import io.hyperconduit.velocity.config.ProxyProtocolSources;
import io.hyperconduit.velocity.config.VelocityConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure parsing, validation, and Chinese message building for the Velocity tunnel command.
 *
 * <p>This class has no Velocity dependency so the rules can be tested directly. Values are never
 * echoed back: the server token is reported as "已设置/未设置" and all validation messages name
 * only the field and the reason.</p>
 */
public final class TunnelConfigValues {

    /** Approved save notice; sent verbatim after every successful configuration edit. */
    public static final String SAVE_NOTICE =
            "[HyperConduit] 已保存配置，重启隧道程序后生效。"
                    + "请执行 /hyperconduit tunnel restart 应用配置。"
                    + "注意：重启会断开所有通过 HyperConduit 连接的玩家，Velocity 的原版 TCP 连接不受影响。";

    private static final int MAX_TOKEN_LENGTH = 1024;
    private static final String TOKEN_HINT =
            "服务器口令只能包含可打印 ASCII 字符（≤1024，首尾不能为空白）。";
    private static final String TOKEN_CLEAR_HINT =
            "服务器口令不能为空；如需清空请使用 clear。";

    private TunnelConfigValues() {
    }

    /** A successful edit: the state line shown first and an optional warning line. */
    public record Change(String detail, String warning) {
    }

    /** Returns the command-friendly display value for {@code key}. */
    public static String describe(VelocityConfig config, TunnelConfigKey key) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(key, "key");
        return switch (key) {
            case UP_MBPS -> Integer.toString(config.upMbps);
            case DOWN_MBPS -> Integer.toString(config.downMbps);
            case CONGESTION -> String.valueOf(config.congestion);
            case DISABLE_BRUTAL -> boolText(config.disableBrutal);
            case SERVER_TOKEN ->
                    config.serverToken == null || config.serverToken.isEmpty() ? "未设置" : "已设置";
            case CONNECT_TIMEOUT_MS -> Integer.toString(config.connectTimeoutMs);
            case MAX_CONNECTIONS -> Integer.toString(config.maxConnections);
            case PROXY_PROTOCOL_V2 -> boolText(config.proxyProtocolV2);
            case PROXY_PROTOCOL_TRUSTED_SOURCES -> {
                List<String> sources = config.proxyProtocolTrustedSources;
                yield sources == null || sources.isEmpty() ? "（空）" : String.join(", ", sources);
            }
            case PROXY_PROTOCOL_V2_OUTPUT -> boolText(config.proxyProtocolV2Output);
        };
    }

    /**
     * Applies one user value to {@code config} and returns the change description. The
     * configuration is only mutated after the value has been validated, so a thrown
     * {@link TunnelCommandException} leaves the instance untouched.
     */
    public static Change apply(VelocityConfig config, TunnelConfigKey key, String raw) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(key, "key");
        String value = raw == null ? "" : raw.strip();
        return switch (key) {
            case UP_MBPS -> {
                config.upMbps = parseInteger(key, value);
                yield new Change(saved(key, config.upMbps), null);
            }
            case DOWN_MBPS -> {
                config.downMbps = parseInteger(key, value);
                yield new Change(saved(key, config.downMbps), null);
            }
            case CONGESTION -> {
                String controller = value.toLowerCase(Locale.ROOT);
                if (!controller.equals("bbr") && !controller.equals("reno")) {
                    throw new TunnelCommandException(key.commandPath() + " 只能是 bbr 或 reno。");
                }
                config.congestion = controller;
                yield new Change(saved(key, controller), null);
            }
            case DISABLE_BRUTAL -> {
                config.disableBrutal = parseBoolean(key, value);
                yield new Change(saved(key, boolText(config.disableBrutal)), null);
            }
            case SERVER_TOKEN -> applyServerToken(config, raw == null ? "" : raw);
            case CONNECT_TIMEOUT_MS -> {
                config.connectTimeoutMs = parseInteger(key, value);
                yield new Change(saved(key, config.connectTimeoutMs), null);
            }
            case MAX_CONNECTIONS -> {
                config.maxConnections = parseInteger(key, value);
                yield new Change(saved(key, config.maxConnections), null);
            }
            case PROXY_PROTOCOL_V2 -> applyProxyProtocolV2(config, parseBoolean(key, value));
            case PROXY_PROTOCOL_TRUSTED_SOURCES -> applySources(config, value);
            case PROXY_PROTOCOL_V2_OUTPUT -> applyProxyProtocolV2Output(config, parseBoolean(key, value));
        };
    }

    /**
     * Enables or disables receiving input Proxy Protocol v2 headers (the FRP path).
     *
     * @throws TunnelCommandException when enabling without a trusted source
     */
    public static Change applyProxyProtocolV2(VelocityConfig config, boolean enabled) {
        Objects.requireNonNull(config, "config");
        if (enabled && !hasSources(config)) {
            throw new TunnelCommandException("开启 Proxy Protocol v2 输入前，请先设置可信来源："
                    + TunnelConfigKey.PROXY_PROTOCOL_TRUSTED_SOURCES.commandPath()
                    + " <IP或CIDR,...>。");
        }
        config.proxyProtocolV2 = enabled;
        if (enabled) {
            return new Change(
                    "已开启 Proxy Protocol v2 输入（FRP），重启隧道程序后生效。"
                            + "仅接受可信转发来源的数据，新转发会话必须提供合法的 Proxy Protocol v2 头部。",
                    null);
        }
        return new Change(
                "已关闭 Proxy Protocol v2 输入（FRP），重启隧道程序后生效。"
                        + "请同步关闭中转端的 Proxy Protocol v2 发送。",
                null);
    }

    /**
     * Enables or disables output Proxy Protocol v2 headers to Velocity's TCP listener.
     *
     * <p>Velocity's public {@code ProxyConfig} does not expose its {@code haproxy-protocol}
     * switch, so this plugin can neither detect nor enable it. The returned warning tells the
     * administrator to enable it manually.</p>
     */
    public static Change applyProxyProtocolV2Output(VelocityConfig config, boolean enabled) {
        Objects.requireNonNull(config, "config");
        config.proxyProtocolV2Output = enabled;
        if (enabled) {
            return new Change(
                    "已开启向 Velocity 监听器发送 Proxy Protocol v2 头部，重启隧道程序后生效。",
                    "请确认 velocity.toml 的 haproxy-protocol = true；公开 API 无法读取或自动开启该开关，"
                            + "不匹配时玩家连接会被拒绝。");
        }
        return new Change(
                "已关闭向 Velocity 监听器发送 Proxy Protocol v2 头部，重启隧道程序后生效。",
                null);
    }

    /** Maps a code-only configuration failure to a Chinese message without echoing its payload. */
    public static String explain(Throwable failure) {
        String code = failure == null || failure.getMessage() == null ? "" : failure.getMessage();
        return switch (code) {
            case "config_read" -> "无法读取服务器配置文件。";
            case "config_invalid" -> "服务器配置文件格式或编码无效。";
            case "config_too_large" -> "服务器配置文件过大。";
            case "config_write" -> "无法写入服务器配置文件。";
            case "config_duplicate_field" -> "服务器配置文件包含重复字段。";
            case "config_unknown_field" -> "服务器配置文件包含未知字段。";
            case "config_conflict" -> "服务器配置文件已被外部修改，本次保存已取消。";
            case "config_up_mbps", "config_down_mbps" -> "带宽必须在 0–1000000 Mbps 之间。";
            case "config_congestion" -> "拥塞控制只能是 bbr 或 reno。";
            case "config_server_token" -> "口令只能包含可打印 ASCII 字符（≤1024，首尾不能为空白）。";
            case "config_connect_timeout" -> "连接超时必须在 100–120000 毫秒之间。";
            case "config_max_connections" -> "最大连接数必须在 1–4096 之间。";
            case "config_proxy_protocol_source" ->
                    "可信来源必须是 IP 或 CIDR 字面值（不支持域名），用逗号分隔。";
            case "config_proxy_protocol_sources_required" ->
                    "启用 Proxy Protocol v2 输入时至少需要一个可信来源。";
            case "config_proxy_protocol_sources_limit" -> "可信来源最多 128 条。";
            case "config_atomic_replace_unavailable" -> "无法原子替换配置文件，请检查文件系统。";
            default -> "保存失败，请检查服务器配置。";
        };
    }

    /** Hint used when a non-admin tries to use a tunnel command. */
    public static String permissionHint() {
        return "权限不足，此命令仅供管理员使用。";
    }

    private static Change applyServerToken(VelocityConfig config, String raw) {
        if (raw.strip().equalsIgnoreCase("clear")) {
            config.serverToken = "";
            return new Change(
                    "服务器口令已清空。",
                    "注意：此前输入的口令可能已留在代理日志中；重启隧道程序后，将不再要求口令认证。");
        }
        validateToken(raw);
        config.serverToken = raw;
        return new Change(
                "服务器口令已更新（不回显）。",
                "提示：命令行输入可能被代理日志记录，敏感口令建议通过配置文件设置。");
    }

    private static Change applySources(VelocityConfig config, String value) {
        if (value.equalsIgnoreCase("clear")) {
            if (config.proxyProtocolV2) {
                throw new TunnelCommandException(
                        "Proxy Protocol v2 输入已开启，不能清空可信来源；"
                                + "请先关闭：/hyperconduit tunnel proxyprotocol input off");
            }
            config.proxyProtocolTrustedSources = new ArrayList<>();
            return new Change("已清空 Proxy Protocol v2 可信来源。", null);
        }
        List<String> sources;
        try {
            sources = ProxyProtocolSources.parse(value);
        } catch (ProxyProtocolSources.InvalidSourceException failure) {
            throw new TunnelCommandException(sourceMessage(failure.problem()));
        }
        config.proxyProtocolTrustedSources = new ArrayList<>(sources);
        return new Change("已更新 Proxy Protocol v2 可信来源（" + sources.size() + " 条）。", null);
    }

    private static String sourceMessage(ProxyProtocolSources.Problem problem) {
        return switch (problem) {
            case EMPTY_LIST -> "可信来源不能为空；如需清空请使用 clear。";
            case EMPTY_ENTRY -> "可信来源不能包含空条目；请用英文逗号分隔。";
            case TOO_MANY -> "可信来源最多 " + ProxyProtocolSources.MAX_ENTRIES + " 条。";
            case NOT_LITERAL -> "可信来源必须是 IP 或 CIDR 字面值（不支持域名），用逗号分隔。";
            case SPECIAL_ADDRESS ->
                    "可信来源不能是 IPv6 zone、IPv4-mapped 或未指定地址（0.0.0.0、::）。";
        };
    }

    private static void validateToken(String token) {
        if (token.isEmpty()) {
            throw new TunnelCommandException(TOKEN_CLEAR_HINT);
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new TunnelCommandException(TOKEN_HINT);
        }
        if (Character.isWhitespace(token.charAt(0))
                || Character.isWhitespace(token.charAt(token.length() - 1))) {
            throw new TunnelCommandException(TOKEN_HINT);
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new TunnelCommandException(TOKEN_HINT);
            }
        }
    }

    private static int parseInteger(TunnelConfigKey key, String raw) {
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException failure) {
            throw new TunnelCommandException(key.commandPath() + " 必须是整数（"
                    + key.minValue() + "–" + key.maxValue() + "）。");
        }
        if (value < key.minValue() || value > key.maxValue()) {
            throw new TunnelCommandException(key.commandPath() + " 必须在 "
                    + key.minValue() + "–" + key.maxValue() + " 之间。");
        }
        return value;
    }

    private static boolean parseBoolean(TunnelConfigKey key, String raw) {
        if (raw.equalsIgnoreCase("true")) {
            return true;
        }
        if (raw.equalsIgnoreCase("false")) {
            return false;
        }
        throw new TunnelCommandException(key.commandPath() + " 只能是 true 或 false。");
    }

    private static boolean hasSources(VelocityConfig config) {
        return config.proxyProtocolTrustedSources != null
                && !config.proxyProtocolTrustedSources.isEmpty();
    }

    private static String saved(TunnelConfigKey key, Object value) {
        return key.key() + " = " + value + "。";
    }

    private static String boolText(boolean value) {
        return value ? "true" : "false";
    }
}
