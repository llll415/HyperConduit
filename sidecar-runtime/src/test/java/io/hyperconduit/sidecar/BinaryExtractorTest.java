package io.hyperconduit.sidecar;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryExtractorTest {

    private static final Platform LINUX = new Platform("linux", "amd64");
    private static final byte[] BINARY = "hyperconduit-tunnel test binary\n"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void extractsVerifiedBinaryToContentAddressedPath(@TempDir Path cache) throws Exception {
        BinaryExtractor extractor = extractor(LINUX, manifest(LINUX, BINARY), BINARY);

        Path extracted = extractor.extract(cache, LINUX);

        assertEquals(expectedPath(cache, LINUX, BINARY), extracted);
        assertArrayEquals(BINARY, Files.readAllBytes(extracted));
    }

    @Test
    void extractsForTheCurrentPlatformByDefault(@TempDir Path cache) throws Exception {
        Platform current = Platform.current();
        BinaryExtractor extractor = extractor(current, manifest(current, BINARY), BINARY);

        Path extracted = extractor.extract(cache);

        assertEquals(expectedPath(cache, current, BINARY), extracted);
        assertArrayEquals(BINARY, Files.readAllBytes(extracted));
    }

    @Test
    void reusesAnExistingValidBinary(@TempDir Path cache) throws Exception {
        BinaryExtractor extractor = extractor(LINUX, manifest(LINUX, BINARY), BINARY);

        Path first = extractor.extract(cache, LINUX);
        Path second = extractor.extract(cache, LINUX);

        assertEquals(first, second);
        assertArrayEquals(BINARY, Files.readAllBytes(second));
    }

    @Test
    void concurrentExtractionsShareOneVerifiedFile(@TempDir Path cache) throws Exception {
        BinaryExtractor extractor = extractor(LINUX, manifest(LINUX, BINARY), BINARY);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Path>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return extractor.extract(cache, LINUX);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            Set<Path> paths = new HashSet<>();
            for (Future<Path> future : futures) {
                paths.add(future.get(60, TimeUnit.SECONDS));
            }
            assertEquals(Set.of(expectedPath(cache, LINUX, BINARY)), paths);
            assertArrayEquals(BINARY, Files.readAllBytes(paths.iterator().next()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void replacesTamperedBinaryFromResources(@TempDir Path cache) throws Exception {
        BinaryExtractor extractor = extractor(LINUX, manifest(LINUX, BINARY), BINARY);
        Path extracted = extractor.extract(cache, LINUX);

        Files.write(extracted, "tampered".getBytes(StandardCharsets.UTF_8));

        Path repaired = extractor.extract(cache, LINUX);

        assertEquals(extracted, repaired);
        assertArrayEquals(BINARY, Files.readAllBytes(repaired));
        try (Stream<Path> entries = Files.list(repaired.getParent())) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void rejectsChecksumMismatchAndLeavesNoTemporaryFile(@TempDir Path cache) throws Exception {
        String wrongSha = "0".repeat(64);
        BinaryExtractor extractor = extractor(LINUX,
                manifest(LINUX, BinaryExtractor.EXPECTED_VERSION, wrongSha, LINUX.executableName()),
                BINARY);

        IOException error = assertThrows(IOException.class, () -> extractor.extract(cache, LINUX));

        assertTrue(error.getMessage().contains("checksum"));
        Path hashDir = cache.toAbsolutePath().normalize()
                .resolve(LINUX.id()).resolve(wrongSha);
        assertFalse(Files.exists(hashDir.resolve(LINUX.executableName())));
        try (Stream<Path> entries = Files.list(hashDir)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void rejectsPathTraversalInManifestFileName(@TempDir Path cache) {
        BinaryExtractor extractor = extractor(LINUX,
                manifest(LINUX, BinaryExtractor.EXPECTED_VERSION,
                        BinaryExtractor.sha256Hex(BINARY), "../hyperconduit-tunnel"),
                BINARY);

        IOException error = assertThrows(IOException.class, () -> extractor.extract(cache, LINUX));

        assertTrue(error.getMessage().contains("manifest"));
    }

    @Test
    void rejectsUnknownManifestFields(@TempDir Path cache) {
        String json = "{\"protocolVersion\":1,\"version\":\"hy2core-dev\",\"sha256\":\""
                + BinaryExtractor.sha256Hex(BINARY) + "\",\"file\":\""
                + LINUX.executableName() + "\",\"extra\":true}";
        BinaryExtractor extractor = extractor(LINUX,
                json.getBytes(StandardCharsets.UTF_8), BINARY);

        IOException error = assertThrows(IOException.class, () -> extractor.extract(cache, LINUX));

        assertTrue(error.getMessage().contains("unknown field"));
    }

    @Test
    void rejectsVersionMismatch(@TempDir Path cache) {
        BinaryExtractor extractor = extractor(LINUX,
                manifest(LINUX, "not-hy2core-dev",
                        BinaryExtractor.sha256Hex(BINARY), LINUX.executableName()),
                BINARY);

        IOException error = assertThrows(IOException.class, () -> extractor.extract(cache, LINUX));

        assertTrue(error.getMessage().contains("version"));
    }

    @Test
    void rejectsProtocolVersionMismatch(@TempDir Path cache) {
        String json = "{\"protocolVersion\":2,\"version\":\"hy2core-dev\",\"sha256\":\""
                + BinaryExtractor.sha256Hex(BINARY) + "\",\"file\":\""
                + LINUX.executableName() + "\"}";
        BinaryExtractor extractor = extractor(LINUX,
                json.getBytes(StandardCharsets.UTF_8), BINARY);

        IOException error = assertThrows(IOException.class, () -> extractor.extract(cache, LINUX));

        assertTrue(error.getMessage().contains("protocolVersion"));
    }

    @Test
    void rejectsWrongExactFileName(@TempDir Path cache) {
        BinaryExtractor extractor = extractor(LINUX,
                manifest(LINUX, BinaryExtractor.EXPECTED_VERSION,
                        BinaryExtractor.sha256Hex(BINARY), "hyperconduit-tunnel.exe"),
                BINARY);

        IOException error = assertThrows(IOException.class, () -> extractor.extract(cache, LINUX));

        assertTrue(error.getMessage().contains("exact file"));
    }

    @Test
    void missingBinaryResourceFailsClearly(@TempDir Path cache) {
        BinaryExtractor extractor = new BinaryExtractor(
                resources(LINUX, manifest(LINUX, BINARY), null));

        IOException error = assertThrows(IOException.class, () -> extractor.extract(cache, LINUX));

        assertTrue(error.getMessage().contains("binary"));
        assertTrue(error.getMessage().contains(LINUX.id()));
    }

    @Test
    void windowsArm64IsDetectableButHasNoBundledResource(@TempDir Path cache) {
        Platform platform = Platform.detect("Windows 11", "aarch64");
        BinaryExtractor extractor = new BinaryExtractor(new FakeClassLoader(Map.of()));

        IOException error = assertThrows(IOException.class,
                () -> extractor.extract(cache, platform));

        assertTrue(error.getMessage().contains("windows-arm64"));
    }

    @Test
    void rejectsOversizedManifest(@TempDir Path cache) {
        byte[] oversized = new byte[BinaryExtractor.MAX_MANIFEST_BYTES + 1];
        Arrays.fill(oversized, (byte) ' ');
        BinaryExtractor extractor = new BinaryExtractor(resources(LINUX, oversized, BINARY));

        IOException error = assertThrows(IOException.class, () -> extractor.extract(cache, LINUX));

        assertTrue(error.getMessage().contains("16 KiB"));
    }

    @Test
    void refusesSymlinkedFinalBinary(@TempDir Path cache) throws Exception {
        String sha = BinaryExtractor.sha256Hex(BINARY);
        Path target = cache.toAbsolutePath().normalize()
                .resolve(LINUX.id()).resolve(sha).resolve(LINUX.executableName());
        Files.createDirectories(target.getParent());
        Path outside = cache.resolve("outside.bin");
        Files.write(outside, BINARY);
        try {
            Files.createSymbolicLink(target, outside);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "Symbolic links are not supported: " + e);
        }

        BinaryExtractor extractor = extractor(LINUX, manifest(LINUX, BINARY), BINARY);
        IOException error = assertThrows(IOException.class, () -> extractor.extract(cache, LINUX));

        assertTrue(error.getMessage().contains("symlink"));
        assertArrayEquals(BINARY, Files.readAllBytes(outside));
    }

    @Test
    void refusesDirectoryAsFinalBinary(@TempDir Path cache) throws Exception {
        String sha = BinaryExtractor.sha256Hex(BINARY);
        Path target = cache.toAbsolutePath().normalize()
                .resolve(LINUX.id()).resolve(sha).resolve(LINUX.executableName());
        Files.createDirectories(target);

        BinaryExtractor extractor = extractor(LINUX, manifest(LINUX, BINARY), BINARY);
        IOException error = assertThrows(IOException.class, () -> extractor.extract(cache, LINUX));

        assertTrue(error.getMessage().contains("directory"));
    }

    @Test
    void setsUnixExecutePermissionsWhenSupported(@TempDir Path cache) throws Exception {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        BinaryExtractor extractor = extractor(LINUX, manifest(LINUX, BINARY), BINARY);

        Path extracted = extractor.extract(cache, LINUX);

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(extracted);
        assertTrue(permissions.contains(PosixFilePermission.OWNER_READ));
        assertTrue(permissions.contains(PosixFilePermission.OWNER_WRITE));
        assertTrue(permissions.contains(PosixFilePermission.OWNER_EXECUTE));
        assertTrue(permissions.contains(PosixFilePermission.GROUP_READ));
        assertTrue(permissions.contains(PosixFilePermission.GROUP_EXECUTE));
        assertTrue(permissions.contains(PosixFilePermission.OTHERS_READ));
        assertTrue(permissions.contains(PosixFilePermission.OTHERS_EXECUTE));
        assertFalse(permissions.contains(PosixFilePermission.GROUP_WRITE));
        assertFalse(permissions.contains(PosixFilePermission.OTHERS_WRITE));
    }

    private static BinaryExtractor extractor(Platform platform, byte[] manifest, byte[] binary) {
        return new BinaryExtractor(resources(platform, manifest, binary));
    }

    private static FakeClassLoader resources(Platform platform, byte[] manifest, byte[] binary) {
        Map<String, byte[]> resources = new HashMap<>();
        String directory = BinaryExtractor.resourceDirectory(platform);
        resources.put(directory + "/manifest.json", manifest);
        if (binary != null) {
            resources.put(directory + "/" + platform.executableName(), binary);
        }
        return new FakeClassLoader(resources);
    }

    private static byte[] manifest(Platform platform, byte[] binary) {
        return manifest(platform, BinaryExtractor.EXPECTED_VERSION,
                BinaryExtractor.sha256Hex(binary), platform.executableName());
    }

    private static byte[] manifest(Platform platform, String version, String sha, String file) {
        String json = "{\"protocolVersion\":1,\"version\":\"" + version
                + "\",\"sha256\":\"" + sha + "\",\"file\":\"" + file + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static Path expectedPath(Path cache, Platform platform, byte[] binary) {
        return cache.toAbsolutePath().normalize()
                .resolve(platform.id())
                .resolve(BinaryExtractor.sha256Hex(binary))
                .resolve(platform.executableName());
    }

    private static final class FakeClassLoader extends ClassLoader {

        private final Map<String, byte[]> resources;

        private FakeClassLoader(Map<String, byte[]> resources) {
            super(null);
            this.resources = Map.copyOf(resources);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            byte[] data = resources.get(name);
            return data == null ? null : new ByteArrayInputStream(data);
        }
    }
}
