package io.hyperconduit.velocity.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandRoutingTest {

    @Test
    void routesTopLevelStatusWatchAndTunnel() {
        assertEquals(CommandRouting.Action.USAGE, CommandRouting.route(new String[0]));
        assertEquals(CommandRouting.Action.STATUS, CommandRouting.route(new String[] {"status"}));
        assertEquals(CommandRouting.Action.WATCH, CommandRouting.route(new String[] {"watch"}));
        assertEquals(CommandRouting.Action.WATCH_STOP,
                CommandRouting.route(new String[] {"watch", "stop"}));
        assertEquals(CommandRouting.Action.USAGE,
                CommandRouting.route(new String[] {"watch", "other"}));
        assertEquals(CommandRouting.Action.TUNNEL_USAGE,
                CommandRouting.route(new String[] {"tunnel"}));
        assertEquals(CommandRouting.Action.TUNNEL,
                CommandRouting.route(new String[] {"tunnel", "status"}));
        assertEquals(CommandRouting.Action.USAGE,
                CommandRouting.route(new String[] {"status", "extra"}));
        assertEquals(CommandRouting.Action.USAGE,
                CommandRouting.route(new String[] {"unknown"}));
    }

    @Test
    void suggestsTopLevelAndWatchStop() {
        assertEquals(List.of("status", "watch", "tunnel"), CommandRouting.suggest(new String[0]));
        assertEquals(List.of("stop"), CommandRouting.suggest(new String[] {"watch"}));
        assertEquals(List.of(), CommandRouting.suggest(new String[] {"watch", "stop"}));
        assertEquals(List.of(), CommandRouting.suggest(new String[] {"status"}));
        assertEquals(List.of("status", "restart", "config", "proxyprotocol"),
                CommandRouting.suggest(new String[] {"tunnel"}));
        assertEquals(List.of("upMbps", "downMbps", "congestion", "disableBrutal",
                        "connectTimeoutMs", "maxConnections", "proxyProtocolV2",
                        "proxyProtocolTrustedSources", "proxyProtocolV2Output"),
                CommandRouting.suggest(new String[] {"tunnel", "config"}));
        assertEquals(List.of("on", "off", "input", "output"),
                CommandRouting.suggest(new String[] {"tunnel", "proxyprotocol"}));
        assertEquals(List.of("bbr", "reno"),
                CommandRouting.suggest(new String[] {"tunnel", "config", "congestion", ""}));
    }
}