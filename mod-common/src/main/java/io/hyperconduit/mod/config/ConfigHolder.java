package io.hyperconduit.mod.config;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Loader-neutral access point for the HY2 configuration file.
 *
 * <p>No configuration values are cached: {@link #get()} loads the file on every call, so an edit
 * made by hand or by another process is observed at the next operation boundary. Only the config
 * directory is remembered. A read failure is never turned into defaults silently; it propagates as
 * an {@link IllegalStateException} carrying a code-only message.
 *
 * <p>Writes go through {@link #update(Consumer)} (read-modify-write for commands) or
 * {@link #compareAndSave(HyperConduitConfig, HyperConduitConfig)} (the GUI's conflict-checked save),
 * so unrelated fields or a detected external edit are not silently overwritten. The check happens
 * immediately before the atomic replace; there is no cross-process file lock, so an edit landing in
 * that final window can still be replaced.
 */
public final class ConfigHolder {

    private static volatile Path configDir;

    private ConfigHolder() {
    }

    /**
     * Records the directory that contains {@link HyperConduitConfig#FILE_NAME}. The supplied
     * configuration is deliberately ignored: values always come from disk.
     */
    public static void install(@Nullable HyperConduitConfig config, Path configDir) {
        install(configDir);
    }

    /** Records the directory that contains {@link HyperConduitConfig#FILE_NAME}. */
    public static void install(Path configDir) {
        ConfigHolder.configDir = Objects.requireNonNull(configDir, "configDir")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Legacy overload without a directory.
     *
     * <p>This is rejected instead of silently doing nothing: there is no directory to persist to
     * and no in-memory cache by design. Call {@link #install(Path)} and write the configuration to
     * disk, or keep the loaded value at the call site.
     *
     * @throws IllegalStateException always
     */
    @Deprecated
    public static void install(@Nullable HyperConduitConfig config) {
        throw new IllegalStateException(
                "ConfigHolder.install(HyperConduitConfig) is not supported: "
                        + "install a config directory and persist the configuration to disk");
    }

    /**
     * Loads the configuration from the installed directory.
     *
     * @return the freshly loaded configuration, or {@code null} when no directory is installed
     * @throws IllegalStateException with a code-only message when the file exists but cannot be
     *         read or fails strict validation
     */
    @Nullable
    public static HyperConduitConfig get() {
        Path directory = configDir;
        if (directory == null) {
            return null;
        }
        return HyperConduitConfig.load(directory);
    }

    /**
     * Connection-boundary read. Unlike {@link #get()}, this never returns {@code null} and never
     * falls back to defaults: an unreadable or invalid file aborts the connection with a Chinese,
     * user-facing {@link ConfigAccessException}. The raw cause is deliberately not attached, so no
     * token, path or other value can leak through the connection error UI.
     *
     * @throws ConfigAccessException with a safe Chinese message and a code-only diagnostic
     */
    public static HyperConduitConfig getForConnection() {
        HyperConduitConfig config;
        try {
            config = get();
        } catch (RuntimeException failure) {
            throw new ConfigAccessException(codeOf(failure));
        }
        if (config == null) {
            throw new ConfigAccessException("config_unavailable");
        }
        return config;
    }

    private static String codeOf(Throwable failure) {
        String message = failure.getMessage();
        return message != null && message.matches("[a-z][a-z0-9_]{0,63}")
                ? message
                : "config_unavailable";
    }

    /** Unchecked, user-facing configuration failure with a code-only diagnostic. */
    public static final class ConfigAccessException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        ConfigAccessException(String code) {
            super(ConfigErrorText.describe(code));
            this.code = code;
        }

        /** Internal diagnostic code; never part of the user-facing message. */
        public String code() {
            return code;
        }
    }

    /**
     * Synchronously reads the file, applies {@code mutation}, validates and atomically saves the
     * result. Intended for command-style single-field edits, which must not clobber unrelated
     * fields written by another process in between.
     *
     * @return an independent copy of the saved configuration
     * @throws IllegalStateException with a code-only message when no directory is installed, the
     *         file is unreadable, or the mutated configuration is invalid
     */
    public static synchronized HyperConduitConfig update(Consumer<HyperConduitConfig> mutation) {
        Objects.requireNonNull(mutation, "mutation");
        Path directory = configDir();
        HyperConduitConfig config = HyperConduitConfig.load(directory);
        mutation.accept(config);
        config.save(directory);
        return config.copy();
    }

    /**
     * Saves the GUI's edited copy only when the file still matches the snapshot the screen opened
     * with.
     *
     * <p>If another process (or a text editor) changed the file after {@code expected} was loaded,
     * nothing is written and {@link IllegalStateException} with the message {@code config_conflict}
     * is thrown. The comparison is done immediately before the atomic replace; there is no
     * cross-process file lock, so a change landing in that last window can still be replaced.
     *
     * @param expected the snapshot loaded when editing began
     * @param edited   the edited copy to persist
     * @return an independent copy of the saved configuration
     */
    public static synchronized HyperConduitConfig compareAndSave(HyperConduitConfig expected,
                                                                 HyperConduitConfig edited) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(edited, "edited");
        Path directory = configDir();
        HyperConduitConfig current = HyperConduitConfig.load(directory);
        if (!current.equals(expected)) {
            throw new IllegalStateException("config_conflict");
        }
        edited.save(directory);
        return edited.copy();
    }

    /** Returns the installed config directory. */
    public static Path configDir() {
        Path directory = configDir;
        if (directory == null) {
            throw new IllegalStateException("HyperConduit config directory is not installed");
        }
        return directory;
    }

    /** Test hook: forgets the installed directory so the no-directory path can be exercised. */
    static void resetForTests() {
        configDir = null;
    }
}
