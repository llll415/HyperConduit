package io.hyperconduit.sidecar;

import java.util.Locale;

/**
 * Canonical OS/architecture pair used to select the bundled sidecar binary.
 *
 * <p>Both components are normalized on construction so callers can pass the raw values returned by
 * {@link System#getProperty(String)} or common aliases ({@code x86_64}, {@code aarch64}, ...).
 */
public record Platform(String os, String arch) {

    public Platform {
        os = normalizeOs(os);
        arch = normalizeArch(arch);
    }

    /** Detects the platform of the running JVM. */
    public static Platform current() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /**
     * Detects a platform from JVM-style OS and architecture strings.
     *
     * <p>Windows ARM64 is recognized as {@code windows-arm64}; whether a binary is actually bundled
     * for it is decided by {@link BinaryExtractor} when it looks up the resource.
     *
     * @throws IllegalArgumentException if the OS or architecture is not one of the supported values
     */
    public static Platform detect(String osName, String osArch) {
        return new Platform(osName, osArch);
    }

    /** Stable directory/resource name, for example {@code windows-amd64}. */
    public String id() {
        return os + "-" + arch;
    }

    /** File name of the sidecar executable for this platform. */
    public String executableName() {
        return "windows".equals(os) ? "hyperconduit-tunnel.exe" : "hyperconduit-tunnel";
    }

    private static String normalizeOs(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Operating system must not be null");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("windows")) {
            return "windows";
        }
        if (normalized.contains("mac") || normalized.contains("darwin") || normalized.equals("osx")) {
            return "darwin";
        }
        if (normalized.contains("linux")) {
            return "linux";
        }
        throw new IllegalArgumentException("Unsupported operating system: " + value);
    }

    private static String normalizeArch(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Architecture must not be null");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "amd64", "x86_64", "x64", "x86-64" -> "amd64";
            case "arm64", "aarch64" -> "arm64";
            default -> throw new IllegalArgumentException("Unsupported architecture: " + value);
        };
    }
}
