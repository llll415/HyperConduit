package io.hyperconduit.sidecar;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Test-only bridge to {@link SidecarProcess#startCommand}. Keeping this helper in the
 * {@code io.hyperconduit.sidecar} package lets lifecycle tests use a real managed process without
 * making the arbitrary-command launcher public production API.
 */
public final class ControllerProcesses {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(1);

    private ControllerProcesses() {
    }

    public static SidecarProcess startServer(Path workingDirectory) throws IOException {
        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path java = Path.of(javaHome, "bin", windows ? "java.exe" : "java");
        return SidecarProcess.startCommand(List.of(
                        java.toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        ControllerProcessFixture.class.getName()),
                "server", workingDirectory, STARTUP_TIMEOUT, STOP_TIMEOUT);
    }
}
