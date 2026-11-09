package io.hyperconduit.sidecar;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts the bundled {@code hyperconduit-tunnel} executable into a content-addressed cache.
 *
 * <p>Resources are read from {@code io/hyperconduit/sidecar/bin/<platform-id>/}. The manifest is
 * parsed strictly, the binary is copied through a same-directory temporary file while its SHA-256
 * is computed, and the verified temporary file is moved into place. A per-cache file lock makes
 * extraction safe across processes; a JVM-wide monitor prevents overlapping file locks in one JVM.
 *
 * <p>The cache directory is trusted user configuration. This class rejects a symlinked or
 * directory target, but does not attempt to defend against an attacker who controls the cache
 * directory itself.
 */
public final class BinaryExtractor {

    static final String EXPECTED_VERSION = "hy2core-dev";
    static final int PROTOCOL_VERSION = 1;
    static final int MAX_MANIFEST_BYTES = 16 * 1024;
    static final long MAX_BINARY_BYTES = 64L * 1024 * 1024;

    private static final String RESOURCE_ROOT = "io/hyperconduit/sidecar/bin";
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Object JVM_EXTRACT_LOCK = new Object();

    private final ClassLoader classLoader;

    public BinaryExtractor() {
        this(BinaryExtractor.class.getClassLoader());
    }

    public BinaryExtractor(ClassLoader classLoader) {
        this.classLoader = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();
    }

    /** Extracts the binary for the current platform. */
    public Path extract(Path cacheDir) throws IOException {
        return extract(cacheDir, Platform.current());
    }

    /**
     * Extracts the binary for {@code platform} into {@code cacheDir} and returns its path.
     *
     * @throws IOException if the manifest or binary is missing, malformed, oversized, has the
     *         wrong version/name/checksum, or cannot be written to the cache
     */
    public Path extract(Path cacheDir, Platform platform) throws IOException {
        Objects.requireNonNull(cacheDir, "cacheDir");
        Objects.requireNonNull(platform, "platform");

        Path root = cacheDir.toAbsolutePath().normalize();
        synchronized (JVM_EXTRACT_LOCK) {
            Manifest manifest = readManifest(platform);
            try {
                Files.createDirectories(root);
            } catch (IOException e) {
                throw new IOException("Cannot create sidecar cache directory " + root, e);
            }

            Path lockFile = root.resolve(
                    ".hyperconduit-" + platform.id() + "-" + manifest.sha256() + ".lock");
            try (FileChannel channel = FileChannel.open(
                    lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return install(root, platform, manifest);
            } catch (OverlappingFileLockException e) {
                throw new IOException(
                        "Sidecar cache lock is already held in this JVM for " + platform.id(), e);
            }
        }
    }

    /** Package-visible SHA-256 helper used by tests. */
    static String sha256Hex(byte[] data) {
        Objects.requireNonNull(data, "data");
        return HexFormat.of().formatHex(sha256().digest(data));
    }

    /** Package-visible SHA-256 helper used by tests. */
    static String sha256Hex(Path file) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Package-visible resource directory helper used by tests. */
    static String resourceDirectory(Platform platform) {
        return RESOURCE_ROOT + "/" + platform.id();
    }

    private Manifest readManifest(Platform platform) throws IOException {
        String resource = resourceDirectory(platform) + "/manifest.json";
        byte[] bytes;
        try (InputStream in = classLoader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("No bundled sidecar manifest for platform "
                        + platform.id() + " (expected " + resource + ")");
            }
            bytes = in.readNBytes(MAX_MANIFEST_BYTES + 1);
        }
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw new IOException("Sidecar manifest for " + platform.id()
                    + " exceeds the 16 KiB limit");
        }
        return parseManifest(bytes, platform);
    }

    private Manifest parseManifest(byte[] bytes, Platform platform) throws IOException {
        int protocolVersion = -1;
        String version = null;
        String sha256 = null;
        String file = null;
        Set<String> seen = new HashSet<>();

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (JsonReader reader = new JsonReader(
                new InputStreamReader(new ByteArrayInputStream(bytes), decoder))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!seen.add(name)) {
                    throw invalidManifest(platform, "duplicate field '" + name + "'");
                }
                switch (name) {
                    case "protocolVersion" -> {
                        if (reader.peek() != JsonToken.NUMBER) {
                            throw invalidManifest(platform, "'protocolVersion' must be a number");
                        }
                        String raw = reader.nextString();
                        if (!"1".equals(raw)) {
                            throw invalidManifest(platform, "unsupported protocolVersion " + raw
                                    + " (expected 1)");
                        }
                        protocolVersion = 1;
                    }
                    case "version" -> version = readString(reader, platform, name);
                    case "sha256" -> sha256 = readString(reader, platform, name);
                    case "file" -> file = readString(reader, platform, name);
                    default -> {
                        reader.skipValue();
                        throw invalidManifest(platform, "unknown field '" + name + "'");
                    }
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw invalidManifest(platform, "unexpected trailing content");
            }
        } catch (MalformedJsonException | IllegalStateException e) {
            throw invalidManifest(platform, "malformed JSON");
        } catch (CharacterCodingException e) {
            throw invalidManifest(platform, "not valid UTF-8");
        }

        if (protocolVersion != PROTOCOL_VERSION) {
            throw invalidManifest(platform, "missing 'protocolVersion'");
        }
        if (version == null) {
            throw invalidManifest(platform, "missing 'version'");
        }
        if (!EXPECTED_VERSION.equals(version)) {
            throw new IOException("Unsupported sidecar manifest version for " + platform.id()
                    + " (expected " + EXPECTED_VERSION + ")");
        }
        if (sha256 == null || !SHA256_HEX.matcher(sha256).matches()) {
            throw invalidManifest(platform, "'sha256' must be 64 hexadecimal characters");
        }
        sha256 = sha256.toLowerCase(Locale.ROOT);
        if (file == null || file.isEmpty()) {
            throw invalidManifest(platform, "missing 'file'");
        }
        if (file.contains("/") || file.contains("\\") || file.equals(".")
                || file.equals("..") || file.contains("..")) {
            throw invalidManifest(platform, "'file' must not contain path separators or traversal");
        }
        if (!file.equals(platform.executableName())) {
            throw new IOException("Sidecar manifest for " + platform.id() + " names '" + file
                    + "', expected exact file '" + platform.executableName() + "'");
        }
        return new Manifest(protocolVersion, version, sha256, file);
    }

    private static String readString(JsonReader reader, Platform platform, String field)
            throws IOException {
        if (reader.peek() != JsonToken.STRING) {
            throw invalidManifest(platform, "'" + field + "' must be a string");
        }
        return reader.nextString();
    }

    private Path install(Path root, Platform platform, Manifest manifest) throws IOException {
        Path targetDir = root.resolve(platform.id()).resolve(manifest.sha256());
        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            throw new IOException("Cannot create sidecar cache directory " + targetDir, e);
        }

        Path target = targetDir.resolve(manifest.file());
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Refusing symlinked sidecar binary for platform "
                    + platform.id() + ": " + target);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Refusing to replace a directory with the sidecar binary for "
                        + platform.id() + ": " + target);
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Refusing to replace a non-regular file with the sidecar "
                        + "binary for " + platform.id() + ": " + target);
            }
            if (hasExpectedHash(target, manifest.sha256())) {
                ensureExecutable(target, platform);
                return target;
            }
            // A file inside our content-addressed directory with the wrong hash is treated as a
            // corrupt extraction and replaced below, but only after the new copy is verified.
        }

        Path temp = Files.createTempFile(targetDir, manifest.file() + ".", ".tmp");
        boolean installed = false;
        try (InputStream in = openBinary(platform, manifest)) {
            copyAndVerify(in, temp, manifest.sha256(), platform);
            ensureExecutable(temp, platform);
            moveIntoPlace(temp, target);
            installed = true;
            return target;
        } finally {
            if (!installed) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best-effort cleanup of our own temporary file only.
                }
            }
        }
    }

    private InputStream openBinary(Platform platform, Manifest manifest) throws IOException {
        String resource = resourceDirectory(platform) + "/" + manifest.file();
        InputStream in = classLoader.getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("No bundled sidecar binary for platform " + platform.id()
                    + " (expected " + resource + ")");
        }
        return in;
    }

    private static boolean hasExpectedHash(Path file, String expected) throws IOException {
        if (Files.size(file) > MAX_BINARY_BYTES) {
            return false;
        }
        return sha256Hex(file).equals(expected);
    }

    private static void copyAndVerify(InputStream in, Path temp, String expectedSha,
            Platform platform) throws IOException {
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(
                temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BINARY_BYTES) {
                    throw new IOException("Sidecar binary for " + platform.id()
                            + " exceeds the 64 MiB limit");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }

        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(expectedSha)) {
            throw new IOException("Sidecar binary checksum mismatch for " + platform.id()
                    + " (expected " + expectedSha + ", actual " + actual + ")");
        }
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Sidecar cache requires atomic file replacement", unsupported);
        }
    }

    private static void ensureExecutable(Path file, Platform platform) throws IOException {
        if ("windows".equals(platform.os())) {
            return;
        }
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
            if (!Files.isExecutable(file)) throw new IOException("Sidecar cache does not permit executable files");
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("Cannot set sidecar executable permissions", unsupported);
        }
    }

    private static IOException invalidManifest(Platform platform, String reason) {
        return new IOException("Invalid sidecar manifest for " + platform.id() + ": " + reason);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private record Manifest(int protocolVersion, String version, String sha256, String file) {
    }
}
