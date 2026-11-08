package io.hyperconduit.mod.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HyperConduitConfigTest {

    @Test
    void defaultsAreSafe() {
        HyperConduitConfig config = new HyperConduitConfig();

        assertFalse(config.enabled);
        assertEquals(0, config.upMbps);
        assertEquals(0, config.downMbps);
        assertEquals("bbr", config.congestion);
        assertFalse(config.disableBrutal);
        assertFalse(config.disableVanillaTcp);
        assertEquals("", config.clientToken);
        assertEquals("", config.serverToken);
        assertEquals(5000, config.connectTimeoutMs);
        assertEquals(64, config.maxConnections);
    }

    @Test
    void loadMissingFileReturnsDefaultsAndIgnoresLegacyFile(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("hyperconduit.json"),
                "{\"enabled\":true,\"mbps\":99}", StandardCharsets.UTF_8);

        HyperConduitConfig config = HyperConduitConfig.load(directory);

        assertFalse(config.enabled);
        assertEquals(0, config.upMbps);
        assertFalse(Files.exists(directory.resolve(HyperConduitConfig.FILE_NAME)));
    }

    @Test
    void loadPartialObjectUsesDefaults(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve(HyperConduitConfig.FILE_NAME),
                "{\"enabled\":true}", StandardCharsets.UTF_8);

        HyperConduitConfig config = HyperConduitConfig.load(directory);

        assertTrue(config.enabled);
        assertEquals(0, config.upMbps);
        assertEquals("bbr", config.congestion);
        assertEquals(5000, config.connectTimeoutMs);
        assertEquals(64, config.maxConnections);
    }

    @Test
    void saveAndLoadRoundTripPreservesEverything(@TempDir Path directory) throws Exception {
        HyperConduitConfig config = new HyperConduitConfig();
        config.enabled = true;
        config.upMbps = 123;
        config.downMbps = 456;
        config.congestion = "reno";
        config.disableBrutal = true;
        config.disableVanillaTcp = true;
        config.clientToken = "client-token_123";
        config.serverToken = "server-token_456";
        config.connectTimeoutMs = 1234;
        config.maxConnections = 7;

        config.save(directory);

        assertTrue(Files.isRegularFile(directory.resolve(HyperConduitConfig.FILE_NAME)));
        assertFalse(Files.exists(directory.resolve("hyperconduit.json")));
        assertEquals(config, HyperConduitConfig.load(directory));
    }

    @Test
    void saveNormalizesCongestionController(@TempDir Path directory) throws Exception {
        HyperConduitConfig config = new HyperConduitConfig();
        config.congestion = "RENO";

        config.save(directory);

        assertEquals("reno", HyperConduitConfig.load(directory).congestion);
    }

    @Test
    void loadRejectsUnknownFields(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve(HyperConduitConfig.FILE_NAME),
                "{\"enabled\":true,\"mbps\":10}", StandardCharsets.UTF_8);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> HyperConduitConfig.load(directory));
        assertEquals("config_unknown_field", error.getMessage());
    }

    @Test
    void loadRejectsWrongJsonTypes(@TempDir Path directory) throws Exception {
        String[] documents = {
                "{\"upMbps\":\"10\"}",
                "{\"upMbps\":1.5}",
                "{\"enabled\":1}",
                "{\"clientToken\":null}",
                "[]"
        };

        for (String document : documents) {
            Files.writeString(directory.resolve(HyperConduitConfig.FILE_NAME), document,
                    StandardCharsets.UTF_8);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> HyperConduitConfig.load(directory), document);
            assertEquals("config_invalid", error.getMessage(), document);
        }
    }

    @Test
    void loadRejectsNonStrictJson(@TempDir Path directory) throws Exception {
        assertLoadError(directory, "{\"enabled\":true,\"enabled\":false}", "config_duplicate_field");
        assertLoadError(directory, "{'enabled':true}", "config_invalid");
        assertLoadError(directory, "// comment\n{\"enabled\":true}", "config_invalid");
        assertLoadError(directory, "/* comment */ {\"enabled\":true}", "config_invalid");
        assertLoadError(directory, "{\"enabled\":true /* comment */}", "config_invalid");
        assertLoadError(directory, "{\"enabled\":true} // comment", "config_invalid");
        assertLoadError(directory, "{\"enabled\":true} {}", "config_invalid");
        assertLoadError(directory, "{\"enabled\":true,}", "config_invalid");
        assertLoadError(directory, "{enabled:true}", "config_invalid");
        assertLoadError(directory, "{\"upMbps\":1.0}", "config_invalid");
        assertLoadError(directory, "{\"upMbps\":1e3}", "config_invalid");
        assertLoadError(directory, "{\"upMbps\":-0}", "config_invalid");
        assertLoadError(directory, "{\"upMbps\":01}", "config_invalid");
        assertLoadError(directory, "{\"upMbps\":-1}", "config_up_mbps");
        assertLoadError(directory, "{\"clientToken\":\"café\"}", "config_client_token");
    }

    @Test
    void loadRejectsOversizedFile(@TempDir Path directory) throws Exception {
        Files.write(directory.resolve(HyperConduitConfig.FILE_NAME),
                new byte[HyperConduitConfig.MAX_JSON_BYTES + 1]);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> HyperConduitConfig.load(directory));
        assertEquals("config_too_large", error.getMessage());
    }

    @Test
    void validatesRanges() {
        HyperConduitConfig config = new HyperConduitConfig();

        config.upMbps = -1;
        assertInvalid(config);
        config.upMbps = 1_000_001;
        assertInvalid(config);

        config = new HyperConduitConfig();
        config.downMbps = -1;
        assertInvalid(config);
        config.downMbps = 1_000_001;
        assertInvalid(config);

        config = new HyperConduitConfig();
        config.connectTimeoutMs = 99;
        assertInvalid(config);
        config.connectTimeoutMs = 120_001;
        assertInvalid(config);

        config = new HyperConduitConfig();
        config.maxConnections = 0;
        assertInvalid(config);
        config.maxConnections = 4097;
        assertInvalid(config);

        config = new HyperConduitConfig();
        config.congestion = "cubic";
        assertInvalid(config);
        config.congestion = null;
        assertInvalid(config);
    }

    @Test
    void validatesTokens() {
        HyperConduitConfig config = new HyperConduitConfig();

        config.clientToken = " leading";
        assertInvalid(config);
        config.clientToken = "trailing ";
        assertInvalid(config);
        config.clientToken = "bad\u0000token";
        assertInvalid(config);
        config.clientToken = "x".repeat(1025);
        assertInvalid(config);

        config = new HyperConduitConfig();
        config.serverToken = "line\nbreak";
        assertInvalid(config);
        config.serverToken = "café";
        assertInvalid(config);
    }

    @Test
    void acceptsBoundaryValues() {
        HyperConduitConfig config = new HyperConduitConfig();
        config.upMbps = 1_000_000;
        config.downMbps = 1_000_000;
        config.congestion = "RENO";
        config.connectTimeoutMs = 120_000;
        config.maxConnections = 4096;
        config.clientToken = "x".repeat(1024);
        config.serverToken = "a".repeat(1024);

        assertDoesNotThrow(config::validate);
    }

    @Test
    void failedSaveKeepsPreviousFile(@TempDir Path directory) throws Exception {
        HyperConduitConfig good = new HyperConduitConfig();
        good.enabled = true;
        good.save(directory);
        byte[] before = Files.readAllBytes(directory.resolve(HyperConduitConfig.FILE_NAME));

        HyperConduitConfig bad = good.copy();
        bad.upMbps = -1;
        assertThrows(IllegalStateException.class, () -> bad.save(directory));

        assertArrayEquals(before, Files.readAllBytes(directory.resolve(HyperConduitConfig.FILE_NAME)));
    }

    @Test
    void savedFileIsOwnerOnly(@TempDir Path directory) throws Exception {
        new HyperConduitConfig().save(directory);
        Path file = directory.resolve(HyperConduitConfig.FILE_NAME);

        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), posix.readAttributes().permissions());
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        assertTrue(acl != null, "File must expose POSIX or ACL permissions");
        UserPrincipal owner = FileSystems.getDefault().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        List<AclEntry> entries = acl.getAcl();
        assertEquals(1, entries.size());
        AclEntry entry = entries.get(0);
        assertEquals(AclEntryType.ALLOW, entry.type());
        assertEquals(owner, entry.principal());
        assertTrue(entry.permissions().containsAll(EnumSet.allOf(AclEntryPermission.class)));
        assertTrue(entry.flags().isEmpty());
    }

    @Test
    void toStringDoesNotExposeTokens() {
        HyperConduitConfig config = new HyperConduitConfig();
        config.clientToken = "client-secret-value";
        config.serverToken = "server-secret-value";

        String text = config.toString();

        assertFalse(text.contains("client-secret-value"));
        assertFalse(text.contains("server-secret-value"));
        assertTrue(text.contains("clientTokenSet=true"));
        assertTrue(text.contains("serverTokenSet=true"));
    }

    @Test
    void loadErrorsDoNotExposeTokens(@TempDir Path directory) throws Exception {
        String token = "secret-token-value";
        Path file = directory.resolve(HyperConduitConfig.FILE_NAME);

        Files.writeString(file, "{\"clientToken\":\" " + token + "\"}", StandardCharsets.UTF_8);
        IllegalStateException validation = assertThrows(IllegalStateException.class,
                () -> HyperConduitConfig.load(directory));
        assertEquals("config_client_token", validation.getMessage());
        assertFalse(validation.toString().contains(token));

        Files.writeString(file, "{\"clientToken\":\"" + token + "\"", StandardCharsets.UTF_8);
        IllegalStateException malformed = assertThrows(IllegalStateException.class,
                () -> HyperConduitConfig.load(directory));
        assertEquals("config_invalid", malformed.getMessage());
        assertFalse(malformed.toString().contains(token));
    }

    @Test
    void copyIsIndependent() {
        HyperConduitConfig original = new HyperConduitConfig();
        original.enabled = true;
        original.upMbps = 12;
        original.clientToken = "token";

        HyperConduitConfig copy = original.copy();

        assertNotSame(original, copy);
        assertEquals(original, copy);

        copy.enabled = false;
        copy.upMbps = 34;
        copy.clientToken = "other";

        assertTrue(original.enabled);
        assertEquals(12, original.upMbps);
        assertEquals("token", original.clientToken);
    }

    @Test
    void unknownOrDuplicateFieldErrorsDoNotEchoValues(@TempDir Path directory) throws Exception {
        String token = "secret-token-value";
        Path file = directory.resolve(HyperConduitConfig.FILE_NAME);

        Files.writeString(file, "{\"unknown\":\"" + token + "\"}", StandardCharsets.UTF_8);
        IllegalStateException unknown = assertThrows(IllegalStateException.class,
                () -> HyperConduitConfig.load(directory));
        assertEquals("config_unknown_field", unknown.getMessage());
        assertFalse(unknown.toString().contains(token));

        Files.writeString(file, "{\"clientToken\":\"" + token + "\",\"clientToken\":\"" + token + "\"}",
                StandardCharsets.UTF_8);
        IllegalStateException duplicate = assertThrows(IllegalStateException.class,
                () -> HyperConduitConfig.load(directory));
        assertEquals("config_duplicate_field", duplicate.getMessage());
        assertFalse(duplicate.toString().contains(token));
    }

    private static void assertLoadError(Path directory, String document, String code) throws Exception {
        Files.writeString(directory.resolve(HyperConduitConfig.FILE_NAME), document, StandardCharsets.UTF_8);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> HyperConduitConfig.load(directory), document);
        assertEquals(code, error.getMessage(), document);
    }

    private static void assertInvalid(HyperConduitConfig config) {
        assertThrows(IllegalStateException.class, config::validate);
    }
}
