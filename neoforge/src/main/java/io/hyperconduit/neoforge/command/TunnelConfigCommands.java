package io.hyperconduit.neoforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.hyperconduit.mod.config.ConfigHolder;
import io.hyperconduit.mod.config.HyperConduitConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Builds the {@code /hyperconduit tunnel} subtree for configuration and Proxy Protocol v2.
 *
 * <p>The root command keeps its original permission level 2 requirement, so every child inherits
 * it. Handlers still call {@link #ensureAdmin(CommandSourceStack)} defensively so future siblings
 * (status/restart) can reuse the same Chinese permission hint without widening access.</p>
 *
 * <p>Every edit goes through {@link ConfigHolder#update} (read-modify-write) and therefore never
 * clobbers unrelated fields written by another process. The server token is never echoed back.</p>
 */
public final class TunnelConfigCommands {

    /** Same requirement as the root command; kept as a constant for the admin helper. */
    public static final int REQUIRED_PERMISSION = 2;

    private static final String PREFIX = "[HyperConduit] ";

    private TunnelConfigCommands() {
    }

    /**
     * Returns the {@code tunnel} subtree. The caller adds it to the root command and may append
     * further children such as {@code status} and {@code restart}.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> branch() {
        // status/restart are added by the controller integration. Keeping this subtree limited to
        // configuration means the root permission requirement remains the only access gate.
        return Commands.literal("tunnel")
                .then(Commands.literal("config")
                        .executes(context -> list(context.getSource()))
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests(TunnelConfigCommands::suggestKeys)
                                .executes(context -> show(context.getSource(),
                                        StringArgumentType.getString(context, "key")))
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .suggests(TunnelConfigCommands::suggestValues)
                                        .executes(context -> set(context.getSource(),
                                                StringArgumentType.getString(context, "key"),
                                                StringArgumentType.getString(context, "value"))))))
                .then(Commands.literal("proxyprotocol")
                        .executes(context -> proxyUsage(context.getSource()))
                        .then(Commands.literal("on")
                                .executes(context -> proxy(context.getSource(), true)))
                        .then(Commands.literal("off")
                                .executes(context -> proxy(context.getSource(), false))));
    }

    /**
     * Sends the Chinese admin hint when the source lacks permission level 2.
     *
     * @return {@code true} when the caller may continue
     */
    public static boolean ensureAdmin(CommandSourceStack source) {
        if (source.hasPermission(REQUIRED_PERMISSION)) {
            return true;
        }
        source.sendFailure(Component.literal(PREFIX + TunnelConfigValues.permissionHint()));
        return false;
    }

    private static int list(CommandSourceStack source) {
        if (!ensureAdmin(source)) {
            return 0;
        }
        HyperConduitConfig config;
        try {
            config = ConfigHolder.get();
        } catch (RuntimeException failure) {
            return fail(source, TunnelConfigValues.explain(failure));
        }
        if (config == null) {
            return fail(source, "配置目录尚未安装，无法读取服务器配置。");
        }
        for (TunnelConfigKey key : TunnelConfigKey.values()) {
            success(source, key.key() + " = " + TunnelConfigValues.describe(config, key));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int show(CommandSourceStack source, String rawKey) {
        if (!ensureAdmin(source)) {
            return 0;
        }
        TunnelConfigKey key;
        try {
            key = requireKey(rawKey);
        } catch (TunnelCommandException failure) {
            return fail(source, failure.getMessage());
        }
        HyperConduitConfig config;
        try {
            config = ConfigHolder.get();
        } catch (RuntimeException failure) {
            return fail(source, TunnelConfigValues.explain(failure));
        }
        if (config == null) {
            return fail(source, "配置目录尚未安装，无法读取服务器配置。");
        }
        success(source, key.key() + " = " + TunnelConfigValues.describe(config, key));
        return Command.SINGLE_SUCCESS;
    }

    private static int set(CommandSourceStack source, String rawKey, String rawValue) {
        if (!ensureAdmin(source)) {
            return 0;
        }
        TunnelConfigKey key;
        try {
            key = requireKey(rawKey);
        } catch (TunnelCommandException failure) {
            return fail(source, failure.getMessage());
        }

        TunnelConfigValues.Change[] change = new TunnelConfigValues.Change[1];
        try {
            ConfigHolder.update(config -> {
                change[0] = TunnelConfigValues.apply(config, key, rawValue);
            });
        } catch (TunnelCommandException failure) {
            return fail(source, failure.getMessage());
        } catch (RuntimeException failure) {
            return fail(source, TunnelConfigValues.explain(failure));
        }
        sendChange(source, change[0]);
        return Command.SINGLE_SUCCESS;
    }

    private static int proxy(CommandSourceStack source, boolean enabled) {
        if (!ensureAdmin(source)) {
            return 0;
        }
        TunnelConfigValues.Change[] change = new TunnelConfigValues.Change[1];
        try {
            ConfigHolder.update(config -> {
                change[0] = TunnelConfigValues.applyProxyProtocolV2(config, enabled);
            });
        } catch (TunnelCommandException failure) {
            return fail(source, failure.getMessage());
        } catch (RuntimeException failure) {
            return fail(source, TunnelConfigValues.explain(failure));
        }
        sendChange(source, change[0]);
        return Command.SINGLE_SUCCESS;
    }

    private static int proxyUsage(CommandSourceStack source) {
        if (!ensureAdmin(source)) {
            return 0;
        }
        success(source, "用法：/hyperconduit tunnel proxyprotocol on|off。"
                + "开启前需先设置可信来源；保存后请执行 /hyperconduit tunnel restart 应用配置。"
                + "注意：重启会断开所有通过 HyperConduit 连接的玩家，原版 TCP 连接不受影响。");
        return Command.SINGLE_SUCCESS;
    }

    private static TunnelConfigKey requireKey(String rawKey) {
        return TunnelConfigKey.fromKey(rawKey)
                .orElseThrow(() -> new TunnelCommandException(
                        "未知配置项。可用配置项：" + TunnelConfigKey.keyList() + "。"));
    }

    private static CompletableFuture<Suggestions> suggestKeys(CommandContext<CommandSourceStack> context,
                                                              SuggestionsBuilder builder) {
        for (TunnelConfigKey key : TunnelConfigKey.values()) {
            if (!key.secret()) {
                builder.suggest(key.key());
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestValues(CommandContext<CommandSourceStack> context,
                                                                SuggestionsBuilder builder) {
        TunnelConfigKey key = TunnelConfigKey
                .fromKey(StringArgumentType.getString(context, "key"))
                .orElse(null);
        if (key != null) {
            for (String suggestion : key.valueSuggestions()) {
                builder.suggest(suggestion);
            }
        }
        return builder.buildFuture();
    }

    private static void sendChange(CommandSourceStack source, TunnelConfigValues.Change change) {
        if (change != null && change.detail() != null && !change.detail().isEmpty()) {
            success(source, change.detail());
        }
        if (change != null && change.warning() != null) {
            success(source, change.warning());
        }
        source.sendSuccess(() -> Component.literal(TunnelConfigValues.SAVE_NOTICE), false);
    }

    private static void success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(PREFIX + message), false);
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(PREFIX + message));
        return 0;
    }
}
