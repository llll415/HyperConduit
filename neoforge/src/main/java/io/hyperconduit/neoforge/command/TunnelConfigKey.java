package io.hyperconduit.neoforge.command;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The only configuration keys exposed by the server command.
 *
 * <p>This list is deliberately smaller than {@link io.hyperconduit.mod.config.HyperConduitConfig}:
 * loader/client settings such as {@code enabled}, {@code disableVanillaTcp} and {@code clientToken}
 * are not server-side knobs and must never become command arguments.</p>
 */
public enum TunnelConfigKey {
    UP_MBPS("upMbps", "发送带宽上限 (Mbps)", ValueKind.INTEGER, 0, 1_000_000),
    DOWN_MBPS("downMbps", "接收带宽上限 (Mbps)", ValueKind.INTEGER, 0, 1_000_000),
    CONGESTION("congestion", "拥塞控制 (bbr/reno)", ValueKind.CONGESTION, 0, 0),
    DISABLE_BRUTAL("disableBrutal", "禁用 Brutal", ValueKind.BOOLEAN, 0, 0),
    SERVER_TOKEN("serverToken", "服务器口令", ValueKind.TOKEN, 0, 0),
    CONNECT_TIMEOUT_MS("connectTimeoutMs", "连接超时 (毫秒)", ValueKind.INTEGER, 100, 120_000),
    MAX_CONNECTIONS("maxConnections", "最大连接数", ValueKind.INTEGER, 1, 4096),
    PROXY_PROTOCOL_V2("proxyProtocolV2", "Proxy Protocol v2", ValueKind.BOOLEAN, 0, 0),
    PROXY_PROTOCOL_TRUSTED_SOURCES("proxyProtocolTrustedSources",
            "Proxy Protocol v2 可信来源 (IP/CIDR，逗号分隔)", ValueKind.SOURCES, 0, 0);

    private final String key;
    private final String label;
    private final ValueKind kind;
    private final int minValue;
    private final int maxValue;

    TunnelConfigKey(String key, String label, ValueKind kind, int minValue, int maxValue) {
        this.key = key;
        this.label = label;
        this.kind = kind;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** Inclusive lower bound for {@link ValueKind#INTEGER} keys. */
    public int minValue() {
        return minValue;
    }

    /** Inclusive upper bound for {@link ValueKind#INTEGER} keys. */
    public int maxValue() {
        return maxValue;
    }

    /** Stable command path used in Chinese error and usage messages. */
    public String commandPath() {
        return "/hyperconduit tunnel config " + key;
    }

    public boolean secret() {
        return kind == ValueKind.TOKEN;
    }

    public List<String> valueSuggestions() {
        return switch (kind) {
            case CONGESTION -> List.of("bbr", "reno");
            case BOOLEAN -> List.of("true", "false");
            case TOKEN, SOURCES -> List.of("clear");
            case INTEGER -> List.of();
        };
    }

    public static Optional<TunnelConfigKey> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        for (TunnelConfigKey candidate : values()) {
            if (candidate.key.equals(key)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Stable completion/usage order. */
    public static List<String> keys() {
        return Arrays.stream(values()).map(TunnelConfigKey::key).toList();
    }

    public static String keyList() {
        return String.join(", ", keys());
    }

    private enum ValueKind {
        INTEGER,
        CONGESTION,
        BOOLEAN,
        TOKEN,
        SOURCES
    }

    @Override
    public String toString() {
        return key + " (" + label + ")";
    }

    /** Lower-cased spelling is only used for tolerant user input, never for the stored key. */
    static boolean matchesKey(TunnelConfigKey key, String input) {
        return input != null && key.key.equals(input.toLowerCase(Locale.ROOT));
    }
}
