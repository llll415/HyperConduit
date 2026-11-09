package io.hyperconduit.neoforge.watch;

import net.minecraft.DetectedVersion;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Execute-as fixtures: the subscription identity must follow {@code CommandSourceStack.source},
 * not the entity returned by {@code getPlayer()}. This keeps console feedback on the console and
 * prevents a player who is merely the execute-as target from controlling another subscription.
 */
class CommandWatchKeysExecuteAsTest {

    private static Object unsafe;
    private static Method allocateInstance;
    private static Field uuidField;

    @BeforeAll
    static void bootstrap() throws Exception {
        // NeoForge's Bootstrap patch calls FeatureFlagLoader, which expects a loading mod list.
        // A real test JVM has no mod discovery pass, so install an explicit empty list first.
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());

        SharedConstants.tryDetectVersion();
        try {
            SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        } catch (IllegalStateException alreadySet) {
            // Another test in the same JVM already chose a version.
        }
        Bootstrap.bootStrap();

        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        unsafe = theUnsafe.get(null);
        allocateInstance = unsafeType.getMethod("allocateInstance", Class.class);

        uuidField = Entity.class.getDeclaredField("uuid");
        uuidField.setAccessible(true);
    }

    @Test
    void consoleExecuteAsPlayerKeepsTheConsoleKey() throws Exception {
        CommandSource console = new FakeSource();
        ServerPlayer target = player(UUID.randomUUID());
        CommandSourceStack stack = stack(console, target);

        assertEquals(WatchKey.source(console), CommandWatchKeys.of(stack));
        assertNotEquals(WatchKey.player(target.getUUID()), CommandWatchKeys.of(stack));
    }

    @Test
    void playerExecuteAsAnotherEntityUsesTheSourcePlayerUuid() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ServerPlayer source = player(sourceId);
        ServerPlayer target = player(targetId);
        CommandSourceStack stack = stack(source, target);

        assertEquals(WatchKey.player(sourceId), CommandWatchKeys.of(stack));
        assertNotEquals(WatchKey.player(targetId), CommandWatchKeys.of(stack));
        assertEquals(CommandWatchKeys.of(stack), CommandWatchKeys.of(stack(source, null)));
    }

    @Test
    void playerExecuteAsAnotherPlayerIsIsolatedFromTheTargetPlayer() throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        ServerPlayer target = player(UUID.randomUUID());

        assertNotEquals(
                CommandWatchKeys.of(stack(player(firstId), target)),
                CommandWatchKeys.of(stack(player(secondId), target)));
    }

    @Test
    void playerKeyWithANonPlayerSourceIsDropped() throws Exception {
        CommandSource console = new FakeSource();
        ServerPlayer target = player(UUID.randomUUID());

        assertTrue(CommandWatchKeys.shouldDrop(WatchKey.player(target.getUUID()), stack(console, target)));
    }

    private static ServerPlayer player(UUID id) throws Exception {
        ServerPlayer player = (ServerPlayer) allocateInstance.invoke(unsafe, ServerPlayer.class);
        uuidField.set(player, id);
        return player;
    }

    private static CommandSourceStack stack(CommandSource source, Entity entity) {
        return new CommandSourceStack(source, Vec3.ZERO, Vec2.ZERO, null, 2,
                "fixture", Component.literal("fixture"), null, entity);
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
