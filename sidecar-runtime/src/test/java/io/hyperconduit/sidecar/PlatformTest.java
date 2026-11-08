package io.hyperconduit.sidecar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformTest {

    @Test
    void detectsWindowsAmd64Aliases() {
        assertEquals("windows-amd64", Platform.detect("Windows 11", "x86_64").id());
        assertEquals("windows-amd64", Platform.detect("Windows 10", "X64").id());
        assertEquals("windows-amd64", Platform.detect("windows", "amd64").id());
    }

    @Test
    void detectsLinuxAndDarwin() {
        assertEquals("linux-amd64", Platform.detect("Linux", "amd64").id());
        assertEquals("linux-arm64", Platform.detect("Linux", "aarch64").id());
        assertEquals("darwin-amd64", Platform.detect("Mac OS X", "x86_64").id());
        assertEquals("darwin-arm64", Platform.detect("Darwin", "arm64").id());
    }

    @Test
    void detectsWindowsArm64ButLeavesResourceAvailabilityToTheExtractor() {
        Platform platform = Platform.detect("Windows 11", "aarch64");
        assertEquals("windows", platform.os());
        assertEquals("arm64", platform.arch());
        assertEquals("windows-arm64", platform.id());
        assertEquals("hyperconduit-tunnel.exe", platform.executableName());
    }

    @Test
    void executableNameHasSuffixOnlyOnWindows() {
        assertEquals("hyperconduit-tunnel.exe", new Platform("windows", "amd64").executableName());
        assertEquals("hyperconduit-tunnel", new Platform("linux", "amd64").executableName());
        assertEquals("hyperconduit-tunnel", new Platform("darwin", "arm64").executableName());
    }

    @Test
    void rejectsUnsupportedOperatingSystem() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Platform.detect("Solaris", "amd64"));
        assertTrue(error.getMessage().contains("Unsupported operating system"));
    }

    @Test
    void rejectsUnsupportedArchitecture() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Platform.detect("Linux", "sparc64"));
        assertTrue(error.getMessage().contains("Unsupported architecture"));
    }

    @Test
    void currentPlatformIsSupported() {
        Platform current = Platform.current();
        assertTrue(current.os().equals("windows")
                || current.os().equals("linux")
                || current.os().equals("darwin"));
        assertTrue(current.arch().equals("amd64") || current.arch().equals("arm64"));
    }
}
