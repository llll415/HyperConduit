package io.hyperconduit.sidecar;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivateConfigTest {

    @Test
    void writesUtf8JsonAndDeletesItOnClose(@TempDir Path directory) throws Exception {
        String token = "token-\u4e2d\u6587-\uD83D\uDE00";
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("token", token);
        values.put("unicode", "秘密");
        values.put("nested", Map.of("enabled", true, "count", 3));

        Path path;
        try (PrivateConfig config = PrivateConfig.create(directory, values)) {
            path = config.path();
            assertTrue(Files.isRegularFile(path));
            assertEquals(directory.toAbsolutePath().normalize(), path.getParent());
            assertTrue(path.getFileName().toString().startsWith("hc-"));
            assertTrue(path.getFileName().toString().endsWith(".json"));

            byte[] actual = Files.readAllBytes(path);
            byte[] expected = new Gson().toJson(values).getBytes(StandardCharsets.UTF_8);
            assertEquals(expected.length, actual.length);

            JsonObject parsed = JsonParser.parseString(new String(actual, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertEquals(token, parsed.get("token").getAsString());
            assertEquals("秘密", parsed.get("unicode").getAsString());
        }
        assertFalse(Files.exists(path));
    }

    @Test
    void doesNotExposeValuesInToStringOrErrors(@TempDir Path directory) throws Exception {
        String token = "secret-" + UUID.randomUUID();
        try (PrivateConfig config = PrivateConfig.create(directory, Map.of("token", token))) {
            assertFalse(config.toString().contains(token));
        }

        String oversized = token + "x".repeat(70 * 1024);
        IOException error = assertThrows(IOException.class,
                () -> PrivateConfig.create(directory, Map.of("token", oversized)));
        assertFalse(error.getMessage().contains(token));
        assertFalse(error.toString().contains(token));
        assertTrue(error.getMessage().startsWith("private_config_"));

        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void rejectsJsonLargerThan64KiBWithoutLeavingFile(@TempDir Path directory) throws Exception {
        String oversized = "x".repeat(64 * 1024 + 1);

        IOException error = assertThrows(IOException.class,
                () -> PrivateConfig.create(directory, Map.of("token", oversized)));

        assertEquals("private_config_too_large", error.getMessage());
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void createsMissingDirectory(@TempDir Path directory) throws Exception {
        Path nested = directory.resolve("nested").resolve("config");

        try (PrivateConfig config = PrivateConfig.create(nested, Map.of("token", "secret"))) {
            assertTrue(Files.isDirectory(nested));
            assertTrue(Files.isRegularFile(config.path()));
        }
    }

    @Test
    void restrictsFileToOwner(@TempDir Path directory) throws Exception {
        try (PrivateConfig config = PrivateConfig.create(directory, Map.of("token", "secret"))) {
            Path path = config.path();

            PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (posix != null) {
                assertEquals(PosixFilePermissions.fromString("rw-------"),
                        posix.readAttributes().permissions());
                return;
            }

            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            assertNotNull(acl, "File must expose POSIX or ACL permissions");
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
    }

    @Test
    void concurrentInstancesUseDistinctFiles(@TempDir Path directory) throws Exception {
        int count = 16;
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PrivateConfig>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return PrivateConfig.create(directory, Map.of("index", index));
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<PrivateConfig> configs = new ArrayList<>();
            try {
                for (Future<PrivateConfig> future : futures) {
                    configs.add(future.get(10, TimeUnit.SECONDS));
                }
                Set<Path> paths = new HashSet<>();
                for (PrivateConfig config : configs) {
                    assertTrue(paths.add(config.path()));
                    assertTrue(Files.isRegularFile(config.path()));
                }
                assertEquals(count, paths.size());
            } finally {
                for (PrivateConfig config : configs) {
                    config.close();
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void closeDeletesOnlyItsOwnFile(@TempDir Path directory) throws Exception {
        PrivateConfig first = PrivateConfig.create(directory, Map.of("id", 1));
        PrivateConfig second = PrivateConfig.create(directory, Map.of("id", 2));
        Path firstPath = first.path();
        Path secondPath = second.path();
        assertNotEquals(firstPath, secondPath);

        first.close();
        first.close();

        assertFalse(Files.exists(firstPath));
        assertTrue(Files.exists(secondPath));

        second.close();
        assertFalse(Files.exists(secondPath));
    }

    @Test
    void closeRefusesToDeleteAReplacedFile(@TempDir Path directory) throws Exception {
        PrivateConfig config = PrivateConfig.create(directory, Map.of("token", "secret"));
        Path path = config.path();
        if (Platform.current().os().equals("windows")) {
            assertThrows(IOException.class, () -> Files.delete(path));
            config.close();
            assertFalse(Files.exists(path));
            return;
        }

        // Create the replacement while the original still exists so the filesystem cannot reuse
        // the original file identity, then atomically move it over the original.
        Path replacement = Files.createTempFile(directory, "replacement-", ".json");
        Files.writeString(replacement, "neighbor", StandardCharsets.UTF_8);
        Files.move(replacement, path, StandardCopyOption.REPLACE_EXISTING);

        IOException error = assertThrows(IOException.class, config::close);
        assertEquals("private_config_replaced", error.getMessage());
        assertTrue(Files.exists(path));
        assertEquals("neighbor", Files.readString(path));
    }
}
