package io.hyperconduit.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class TrustCommandsTest {
    @TempDir Path directory;

    @Test void emptyTrustCanBeListedAndUnknownEndpointForgotten() throws Exception {
        Path cache=directory.resolve("bin"), launch=directory.resolve("launch"), state=directory.resolve("state");
        assertTrue(TrustCommands.list(cache,launch,state,directory).isEmpty());
        TrustCommands.forget(cache,launch,state,directory,"mc.example:25565");
        assertTrue(TrustCommands.list(cache,launch,state,directory).isEmpty());
        assertFalse(Files.exists(state.resolve("known-servers.json")));
        try(var remaining=Files.list(launch)){assertEquals(0,remaining.count());}
    }
}
