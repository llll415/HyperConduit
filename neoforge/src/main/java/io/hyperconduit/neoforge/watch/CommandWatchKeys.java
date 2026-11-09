package io.hyperconduit.neoforge.watch;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BaseCommandBlock;

/**
 * Bridges the real {@link CommandSourceStack} API to the stable {@link WatchKey} used by the
 * registry.
 *
 * <p>Identity always follows {@code CommandSourceStack.source}, never the execute-as entity
 * returned by {@code getPlayer()}. Console/command-block {@code execute as <player>} feedback is
 * still delivered to the original source, and a player executing as another entity must keep the
 * source player's UUID.</p>
 */
public final class CommandWatchKeys {

    private CommandWatchKeys() {
    }

    public static WatchKey of(CommandSourceStack stack) {
        CommandSource source = stack.source;
        if (source instanceof ServerPlayer player) {
            return WatchKey.player(player.getUUID());
        }
        return WatchKey.source(source);
    }

    public static boolean shouldDrop(WatchKey key, CommandSourceStack stack) {
        CommandSource source = stack.source;
        if (!key.isPlayer()) {
            return isInvalidNonPlayerSource(source);
        }
        if (!(source instanceof ServerPlayer player)) {
            return true;
        }

        MinecraftServer server = stack.getServer();
        WatchTargetState state = new WatchTargetState(
                player.connection != null && player.connection.isAcceptingMessages(),
                player.hasPermissions(2),
                server != null && server.getPlayerList().getPlayer(key.playerId()) == player
        );
        return WatchCleanup.shouldDrop(key, state, false);
    }

    private static boolean isInvalidNonPlayerSource(CommandSource source) {
        if (source instanceof BaseCommandBlock commandBlock) {
            return !commandBlock.isValid();
        }
        if (source instanceof Entity entity) {
            return entity.isRemoved();
        }
        return false;
    }
}
