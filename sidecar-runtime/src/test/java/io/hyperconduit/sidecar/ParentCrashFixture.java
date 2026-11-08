package io.hyperconduit.sidecar;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Parent JVM used by {@code ParentDeathTest}. It starts a real managed Go sidecar, reports the
 * Go child's pid on its own stdout, and then stays alive until the test forcibly kills it.
 *
 * <p>The Go process's stdout is consumed by {@link SidecarProcess}, so the pid printed here is
 * the fixture's own output, not the sidecar's protocol stream. This fixture intentionally does
 * not rely on {@code close()} or a JVM shutdown hook: the test proves that closing the parent's
 * stdin pipe (by killing this JVM) is enough for the managed Go child to stop.
 */
public final class ParentCrashFixture {

    private ParentCrashFixture() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: ParentCrashFixture <binary> <config> <workingDirectory>");
        }

        Path binary = Path.of(args[0]);
        Path config = Path.of(args[1]);
        Path workingDirectory = Path.of(args[2]);

        SidecarProcess server = SidecarProcess.start(binary, "server", config, workingDirectory,
                Duration.ofSeconds(15), Duration.ofSeconds(2));

        // Deliberately no try-with-resources: the parent is killed with destroyForcibly(), so no
        // Java cleanup may run. The Go child must observe stdin EOF on its own.
        server.ready().get(15, TimeUnit.SECONDS);
        System.out.println(server.pid());
        System.out.flush();

        while (true) {
            Thread.sleep(1_000);
        }
    }
}
