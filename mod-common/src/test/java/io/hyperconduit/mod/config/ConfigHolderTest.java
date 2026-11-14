package io.hyperconduit.mod.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that {@link ConfigHolder} reads the file instead of caching a process-wide snapshot. */
class ConfigHolderTest {

    @TempDir
    Path directory;

    @AfterEach
    void forgetDirectory() {
        ConfigHolder.resetForTests();
    }

    @Test
    void getWithoutInstalledDirectoryReturnsNull() {
        ConfigHolder.resetForTests();

        assertNull(ConfigHolder.get());
        assertThrows(IllegalStateException.class, ConfigHolder::configDir);
    }

    @Test
    void installRecordsTheDirectoryButNotTheConfigObject() throws Exception {
        HyperConduitConfig inMemory = new HyperConduitConfig();
        inMemory.enabled = true;

        ConfigHolder.install(inMemory, directory);

        HyperConduitConfig loaded = ConfigHolder.get();
        assertNotNull(loaded);
        assertFalse(loaded.enabled, "install must not cache the passed instance");
        assertEquals(directory.toAbsolutePath().normalize(), ConfigHolder.configDir());
    }

    @Test
    void installWithoutDirectoryIsRejectedInsteadOfCaching() {
        ConfigHolder.resetForTests();
        HyperConduitConfig inMemory = new HyperConduitConfig();
        inMemory.enabled = true;

        assertThrows(IllegalStateException.class, () -> ConfigHolder.install(inMemory));
        assertNull(ConfigHolder.get());
    }

    @Test
    void getReadsTheFileOnEveryCall() throws Exception {
        ConfigHolder.install(directory);
        HyperConduitConfig first = new HyperConduitConfig();
        first.enabled = true;
        first.save(directory);
        assertTrue(ConfigHolder.get().enabled);

        HyperConduitConfig external = new HyperConduitConfig();
        external.enabled = false;
        external.maxConnections = 12;
        external.save(directory);

        HyperConduitConfig reread = ConfigHolder.get();
        assertFalse(reread.enabled);
        assertEquals(12, reread.maxConnections);
    }

    @Test
    void getDoesNotSilentlyDefaultOnReadError() throws Exception {
        ConfigHolder.install(directory);
        Files.writeString(directory.resolve(HyperConduitConfig.FILE_NAME), "{", StandardCharsets.UTF_8);

        IllegalStateException error = assertThrows(IllegalStateException.class, ConfigHolder::get);
        assertEquals("config_invalid", error.getMessage());
    }

    @Test
    void getForConnectionFailsWithSafeChineseMessage() throws Exception {
        ConfigHolder.install(directory);
        Files.writeString(directory.resolve(HyperConduitConfig.FILE_NAME), "{", StandardCharsets.UTF_8);

        ConfigHolder.ConfigAccessException error = assertThrows(ConfigHolder.ConfigAccessException.class,
                ConfigHolder::getForConnection);

        assertEquals("config_invalid", error.code());
        assertTrue(error.getMessage().startsWith(ConfigErrorText.PREFIX));
        assertFalse(error.getMessage().contains("config_invalid"));
        assertNull(error.getCause());
    }

    @Test
    void getForConnectionWithoutDirectoryFailsSafely() {
        ConfigHolder.resetForTests();

        ConfigHolder.ConfigAccessException error = assertThrows(ConfigHolder.ConfigAccessException.class,
                ConfigHolder::getForConnection);

        assertEquals("config_unavailable", error.code());
        assertTrue(error.getMessage().startsWith(ConfigErrorText.PREFIX));
        assertNull(error.getCause());
    }

    @Test
    void updateReadsModifiesAndSavesAtomically() throws Exception {
        ConfigHolder.install(directory);

        HyperConduitConfig returned = ConfigHolder.update(config -> {
            config.enabled = true;
            config.proxyProtocolV2 = true;
            config.proxyProtocolTrustedSources = List.of("127.0.0.1");
        });

        assertTrue(returned.enabled);
        assertEquals(List.of("127.0.0.1"), returned.proxyProtocolTrustedSources);
        assertEquals(returned, HyperConduitConfig.load(directory));
    }

    @Test
    void updateWithoutDirectoryFailsInsteadOfWritingElsewhere() {
        ConfigHolder.resetForTests();

        assertThrows(IllegalStateException.class,
                () -> ConfigHolder.update(config -> config.enabled = true));
    }

    @Test
    void compareAndSaveWritesWhenDiskStillMatches() throws Exception {
        ConfigHolder.install(directory);
        HyperConduitConfig base = HyperConduitConfig.load(directory);
        HyperConduitConfig edited = base.copy();
        edited.enabled = true;

        ConfigHolder.compareAndSave(base, edited);

        assertTrue(HyperConduitConfig.load(directory).enabled);
    }

    @Test
    void compareAndSaveRefusesToOverwriteAnExternalEdit() throws Exception {
        ConfigHolder.install(directory);
        HyperConduitConfig base = HyperConduitConfig.load(directory);
        HyperConduitConfig edited = base.copy();
        edited.enabled = true;

        HyperConduitConfig external = base.copy();
        external.maxConnections = 7;
        external.save(directory);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ConfigHolder.compareAndSave(base, edited));
        assertEquals("config_conflict", error.getMessage());

        HyperConduitConfig after = HyperConduitConfig.load(directory);
        assertEquals(7, after.maxConnections, "the external edit must survive");
        assertFalse(after.enabled, "the stale GUI copy must not be written");
    }

    @Test
    void compareAndSaveWithoutDirectoryFails() {
        ConfigHolder.resetForTests();
        HyperConduitConfig base = new HyperConduitConfig();

        assertThrows(IllegalStateException.class,
                () -> ConfigHolder.compareAndSave(base, base.copy()));
    }
}
