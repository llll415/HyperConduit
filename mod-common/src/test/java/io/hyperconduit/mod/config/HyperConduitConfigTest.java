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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertFalse(config.proxyProtocolV2);
        assertNotNull(config.proxyProtocolTrustedSources);
        assertTrue(config.proxyProtocolTrustedSources.isEmpty());
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
        assertFalse(config.proxyProtocolV2);
        assertTrue(config.proxyProtocolTrustedSources.isEmpty());
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
        config.proxyProtocolV2 = true;
        config.proxyProtocolTrustedSources = List.of("127.0.0.1", "10.0.0.0/8", "::1");

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
                "{\"proxyProtocolV2\":1}",
                "{\"proxyProtocolTrustedSources\":\"127.0.0.1\"}",
                "{\"proxyProtocolTrustedSources\":null}",
                "{\"proxyProtocolTrustedSources\":[1]}",
                "{\"proxyProtocolTrustedSources\":[null]}",
                "{\"proxyProtocolTrustedSources\":[[\"127.0.0.1\"]]}",
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
    void acceptsLiteralProxyProtocolSources() {
        HyperConduitConfig config = new HyperConduitConfig();
        List<String> valid = List.of(
                "127.0.0.1",
                "127.0.0.1/32",
                "255.255.255.255/32",
                "10.0.0.0/8",
                "::1",
                "::1/128",
                "2001:db8::/32",
                "2001:db8:0:0:0:0:0:1",
                "1:2:3:4:5:6:192.0.2.1");

        for (String source : valid) {
            config.proxyProtocolTrustedSources = List.of(source);
            assertDoesNotThrow(config::validate, source);
        }

        config.proxyProtocolV2 = true;
        config.proxyProtocolTrustedSources = List.of("127.0.0.1");
        assertDoesNotThrow(config::validate);
    }

    @Test
    void rejectsNonLiteralOrMalformedProxyProtocolSources() {
        HyperConduitConfig config = new HyperConduitConfig();
        List<String> invalid = List.of(
                "",
                " ",
                " localhost",
                "localhost",
                "example.com",
                "127.0.0.1 ",
                "1.2.3",
                "1.2.3.4.5",
                "256.0.0.1",
                "01.2.3.4",
                "1.2.3.4/33",
                "1.2.3.4/-1",
                "1.2.3.4/",
                "1.2.3.4/024",
                "1.2.3.4/32/1",
                "1.2.3.4::",
                "0.0.0.0",
                "0.0.0.0/0",
                "::",
                "::/0",
                "::ffff:192.0.2.1",
                "::ffff:10.0.0.0/104",
                "0:0:0:0:0:ffff:1.2.3.4",
                "::g",
                "1::2::3",
                ":::",
                "1:2:3:4:5:6:7",
                "1:2:3:4:5:6:7:8:9",
                "fe80::1%eth0",
                "2001:db8::/129");

        for (String source : invalid) {
            config.proxyProtocolTrustedSources = List.of(source);
            assertInvalid(config, source);
        }

        config.proxyProtocolTrustedSources = null;
        assertInvalid(config, "null list");

        config.proxyProtocolTrustedSources = new java.util.ArrayList<>();
        for (int i = 0; i < 129; i++) {
            config.proxyProtocolTrustedSources.add("10.0.0." + (i % 255));
        }
        IllegalStateException limit = assertThrows(IllegalStateException.class, config::validate);
        assertEquals("config_proxy_protocol_sources_limit", limit.getMessage());
    }

    @Test
    void requiresAtLeastOneSourceWhenProxyProtocolIsEnabled() {
        HyperConduitConfig config = new HyperConduitConfig();
        config.proxyProtocolV2 = true;
        assertInvalid(config);

        config.proxyProtocolTrustedSources = List.of("127.0.0.1");
        assertDoesNotThrow(config::validate);

        config.proxyProtocolV2 = false;
        config.proxyProtocolTrustedSources = List.of();
        assertDoesNotThrow(config::validate);
    }

    @Test
    void cidrSourcesAreValidatedAfterMasking() {
        HyperConduitConfig config = new HyperConduitConfig();

        for (String source : List.of(
                "10.1.2.3/8",
                "192.168.1.5/24",
                "0.0.0.1/32",
                "2001:db8::1/32",
                "8000::/1",
                "::1/128")) {
            config.proxyProtocolTrustedSources = List.of(source);
            assertDoesNotThrow(config::validate, source);
        }

        for (String source : List.of(
                "1.2.3.4/0",
                "0.0.0.1/8",
                "0.0.0.0/1",
                "0.0.0.1/31",
                "::1/0",
                "::1/127",
                "1:2:3:4:5:6:7:8/1",
                "0:0:0:0:0:0:0:1/64")) {
            config.proxyProtocolTrustedSources = List.of(source);
            assertInvalid(config, source);
        }
    }

    @Test
    void cidrInputSpellingIsPreserved(@TempDir Path directory) throws Exception {
        HyperConduitConfig config = new HyperConduitConfig();
        config.proxyProtocolTrustedSources = List.of("10.1.2.3/8", "2001:db8::1/32");

        config.save(directory);

        assertEquals(List.of("10.1.2.3/8", "2001:db8::1/32"),
                HyperConduitConfig.load(directory).proxyProtocolTrustedSources);
    }

    @Test
    void acceptsAtMost128ProxyProtocolSources() {
        HyperConduitConfig config = new HyperConduitConfig();
        for (int i = 0; i < 128; i++) {
            config.proxyProtocolTrustedSources.add("10.0." + (i / 256) + "." + (i % 256));
        }

        assertDoesNotThrow(config::validate);
    }

    @Test
    void proxyProtocolFieldsParticipateInEqualityAndCopy() {
        HyperConduitConfig original = new HyperConduitConfig();
        original.proxyProtocolV2 = true;
        original.proxyProtocolTrustedSources = new java.util.ArrayList<>(List.of("127.0.0.1"));

        HyperConduitConfig copy = original.copy();
        assertEquals(original, copy);
        assertEquals(original.hashCode(), copy.hashCode());

        copy.proxyProtocolTrustedSources.add("10.0.0.1");
        assertFalse(original.proxyProtocolTrustedSources.contains("10.0.0.1"));
        assertNotEquals(original, copy);

        HyperConduitConfig other = new HyperConduitConfig();
        other.proxyProtocolV2 = true;
        other.proxyProtocolTrustedSources = List.of("127.0.0.1");
        assertEquals(original, other);
        assertEquals(original.hashCode(), other.hashCode());
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

    private static void assertInvalid(HyperConduitConfig config, String message) {
        assertThrows(IllegalStateException.class, config::validate, message);
    }
}
