package io.hyperconduit.velocity.command;

import java.util.List;
import java.util.Locale;

/**
 * Pure top-level command routing and tab-completion.
 *
 * <p>Kept separate from Velocity's {@code Invocation} so command shape can be
 * unit-tested without constructing a proxy.</p>
 */
public final class CommandRouting {

    public enum Action {
        USAGE,
        STATUS,
        WATCH,
        WATCH_STOP,
        TUNNEL_USAGE,
        TUNNEL
    }

    private CommandRouting() {
    }

    public static Action route(String[] args) {
        if (args == null || args.length == 0) {
            return Action.USAGE;
        }
        if ("status".equalsIgnoreCase(args[0])) {
            return args.length == 1 ? Action.STATUS : Action.USAGE;
        }
        if ("watch".equalsIgnoreCase(args[0])) {
            if (args.length == 1) {
                return Action.WATCH;
            }
            if (args.length == 2 && "stop".equalsIgnoreCase(args[1])) {
                return Action.WATCH_STOP;
            }
            return Action.USAGE;
        }
        if ("tunnel".equalsIgnoreCase(args[0])) {
            return args.length == 1 ? Action.TUNNEL_USAGE : Action.TUNNEL;
        }
        return Action.USAGE;
    }

    public static List<String> suggest(String[] args) {
        if (args == null || args.length == 0) {
            return List.of("status", "watch", "tunnel");
        }
        if ("watch".equalsIgnoreCase(args[0])) {
            return args.length == 1 ? List.of("stop") : List.of();
        }
        if (!"tunnel".equalsIgnoreCase(args[0])) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("status", "restart", "config", "proxyprotocol");
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (sub.equals("config")) {
                return visibleKeys();
            }
            if (sub.equals("proxyprotocol")) {
                return List.of("on", "off", "input", "output");
            }
            return List.of();
        }
        if (args.length == 3) {
            if (sub.equals("config")) {
                return visibleKeys();
            }
            if (sub.equals("proxyprotocol")) {
                String direction = args[2].toLowerCase(Locale.ROOT);
                if (direction.equals("input") || direction.equals("output")) {
                    return List.of("on", "off");
                }
            }
            return List.of();
        }
        if (args.length == 4) {
            if (sub.equals("config")) {
                return TunnelConfigKey.fromKey(args[2])
                        .map(TunnelConfigKey::valueSuggestions)
                        .orElse(List.of());
            }
            if (sub.equals("proxyprotocol")) {
                return List.of("on", "off");
            }
        }
        return List.of();
    }

    private static List<String> visibleKeys() {
        return TunnelConfigKey.visibleKeys();
    }
}