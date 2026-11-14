package io.hyperconduit.velocity.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Loader-neutral access point for the Velocity configuration file.
 *
 * <p>No values are cached: {@link #load()} reads the file on every call, so a manual edit is
 * observed at the next command or process-start boundary. Writes go through
 * {@link #update(Consumer)} (read-modify-write) so unrelated fields are not clobbered.</p>
 */
public final class VelocityConfigStore {

    private final Path dataDirectory;

    public VelocityConfigStore(Path dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath()
                .normalize();
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public Path configFile() {
        return dataDirectory.resolve(VelocityConfig.FILE_NAME);
    }

    /**
     * Loads the configuration from disk.
     *
     * @throws IllegalStateException with a code-only message when the file exists but cannot be
     *         read or fails strict validation
     */
    public VelocityConfig load() {
        return VelocityConfig.load(dataDirectory);
    }

    /**
     * Synchronously reads the file, applies {@code mutation}, validates and atomically saves the
     * result. Intended for command-style single-field edits.
     *
     * @return an independent copy of the saved configuration
     */
    public synchronized VelocityConfig update(Consumer<VelocityConfig> mutation) {
        Objects.requireNonNull(mutation, "mutation");
        VelocityConfig config = VelocityConfig.load(dataDirectory);
        mutation.accept(config);
        config.save(dataDirectory);
        return config.copy();
    }

    /** Saves an already validated copy. */
    public synchronized void save(VelocityConfig config) {
        Objects.requireNonNull(config, "config");
        config.save(dataDirectory);
    }
}
