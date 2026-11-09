package io.hyperconduit.neoforge.watch;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the production {@link CommandWatchKeys} adapter with real (non-player)
 * {@link CommandSourceStack} instances. No Minecraft bootstrap is required for this path because
 * the stack only carries a {@code CommandSource} and a null entity.
 */
class CommandWatchKeysTest {

    @Test
    void freshStacksFromTheSameNonPlayerSourceShareAKey() {
        CommandSource console = new FakeSource();

        assertEquals(CommandWatchKeys.of(stack(console, "Server")),
                CommandWatchKeys.of(stack(console, "Server")));
    }

    @Test
    void differentNonPlayerSourcesDoNotShareAKey() {
        assertNotEquals(CommandWatchKeys.of(stack(new FakeSource(), "Server")),
                CommandWatchKeys.of(stack(new FakeSource(), "Server")));
    }

    @Test
    void keyUsesSourceIdentityRatherThanDisplayName() {
        CommandSource source = new FakeSource();

        assertEquals(CommandWatchKeys.of(stack(source, "first-name")),
                CommandWatchKeys.of(stack(source, "second-name")));
        assertNotEquals(CommandWatchKeys.of(stack(new FakeSource(), "same-name")),
                CommandWatchKeys.of(stack(new FakeSource(), "same-name")));
    }

    @Test
    void stopWithAFreshStackRemovesTheSubscription() {
        CommandSource source = new FakeSource();
        WatchRegistry<WatchKey, String> registry = new WatchRegistry<>();

        registry.put(CommandWatchKeys.of(stack(source, "watch")), "stack-from-watch");

        assertEquals("stack-from-watch",
                registry.remove(CommandWatchKeys.of(stack(source, "stop"))));
        assertTrue(registry.isEmpty());
    }

    @Test
    void repeatedWatchReplacesTheStoredStack() {
        CommandSource source = new FakeSource();
        WatchRegistry<WatchKey, String> registry = new WatchRegistry<>();

        registry.put(CommandWatchKeys.of(stack(source, "first")), "first-stack");
        registry.put(CommandWatchKeys.of(stack(source, "second")), "second-stack");

        assertEquals(1, registry.size());
        assertEquals("second-stack",
                registry.get(CommandWatchKeys.of(stack(source, "status"))).orElseThrow());
    }

    private static CommandSourceStack stack(CommandSource source, String name) {
        return new CommandSourceStack(source, Vec3.ZERO, Vec2.ZERO, null, 2,
                name, Component.literal(name), null, null);
    }

    private static final class FakeSource implements CommandSource {

        @Override
        public void sendSystemMessage(Component message) {
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
