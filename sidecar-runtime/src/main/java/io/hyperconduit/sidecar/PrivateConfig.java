package io.hyperconduit.sidecar;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.channels.FileChannel;
import com.sun.nio.file.ExtendedOpenOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A short-lived private JSON configuration file used to hand secrets to a child process without
 * putting them on the command line.
 *
 * <p>The caller supplies a trusted directory. Every instance creates a random {@code hc-*.json}
 * file, tightens and verifies its permissions to the current user before writing any content, and
 * deletes only that file on {@link #close()}. Error messages contain stable codes only; keys and
 * values are never included.
 */
public final class PrivateConfig implements AutoCloseable {

    static final int MAX_JSON_BYTES = 64 * 1024;

    private static final String PREFIX = "hc-";
    private static final String SUFFIX = ".json";
    private static final Gson GSON = new Gson();

    private final Path path;
    private final Object fileKey;
    private final FileChannel windowsGuard;
    private boolean closed;

    private PrivateConfig(Path path, Object fileKey, FileChannel windowsGuard) {
        this.path = path;
        this.fileKey = fileKey;
        this.windowsGuard = windowsGuard;
    }

    /**
     * Serializes {@code values} as UTF-8 JSON into a new private file under {@code directory}.
     *
     * <p>The directory is treated as trusted user configuration and is created if necessary. The
     * serialized form must not exceed 64 KiB. No file is created when serialization fails or the
     * limit is exceeded.
     *
     * @throws IOException with a code-only message if the values cannot be serialized, are too
     *         large, or the file cannot be created, secured, identified, or written
     */
    public static PrivateConfig create(Path directory, Map<String, ?> values) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(values, "values");
        byte[] json = serialize(values);

        Path root;
        try {
            root = directory.toAbsolutePath().normalize();
            Files.createDirectories(root);
        } catch (IOException | RuntimeException e) {
            throw new IOException("private_config_directory");
        }

        Path temp;
        try {
            temp = createTempFile(root);
        } catch (IOException | RuntimeException e) {
            throw new IOException("private_config_create");
        }

        try {
            secure(temp);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            throw new IOException("private_config_permissions");
        }

        Object fileKey;
        try {
            fileKey = Files.readAttributes(
                    temp, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            throw new IOException("private_config_identity");
        }
        boolean windows = Platform.current().os().equals("windows");
        if (fileKey == null && !windows) {
            deleteQuietly(temp);
            throw new IOException("private_config_identity");
        }

        try {
            Files.write(temp, json, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            throw new IOException("private_config_write");
        }

        FileChannel guard = null;
        if (windows) {
            try {
                guard = FileChannel.open(temp, StandardOpenOption.READ,
                        ExtendedOpenOption.NOSHARE_DELETE, ExtendedOpenOption.NOSHARE_WRITE);
            } catch (IOException | RuntimeException e) {
                deleteQuietly(temp);
                throw new IOException("private_config_guard");
            }
        }
        return new PrivateConfig(temp.toAbsolutePath().normalize(), fileKey, guard);
    }

    /** Absolute path of the private configuration file. */
    public Path path() {
        return path;
    }

    /**
     * Deletes the configuration file created by this instance.
     *
     * <p>Unix uses the file identity; Windows holds a no-delete/no-write handle
     * until close because the JDK provider may not expose a file key.
     * The file is only removed while it is still the regular file created by this instance.
     * A missing file is ignored; a replaced path is reported without deleting it. A failed delete
     * leaves this instance open so the caller can retry. Failures use a code-only message and never
     * include file contents.
     */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }

        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            closed = true;
            return;
        } catch (IOException | RuntimeException e) {
            throw new IOException("private_config_delete_failed");
        }

        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || replaced(attributes)) {
            // Never touch a path that no longer refers to the file this instance created.
            closed = true;
            throw new IOException("private_config_replaced");
        }

        try {
            if (windowsGuard != null) windowsGuard.close();
            Files.deleteIfExists(path);
            closed = true;
        } catch (IOException | RuntimeException e) {
            throw new IOException("private_config_delete_failed");
        }
    }

    private static byte[] serialize(Map<String, ?> values) throws IOException {
        String json;
        try {
            json = GSON.toJson(values);
        } catch (RuntimeException e) {
            throw new IOException("private_config_invalid");
        }
        if (json == null) {
            throw new IOException("private_config_invalid");
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JSON_BYTES) {
            throw new IOException("private_config_too_large");
        }
        return bytes;
    }

    private static Path createTempFile(Path directory) throws IOException {
        FileAttribute<?>[] attributes = creationAttributes();
        if (attributes.length == 0) {
            return Files.createTempFile(directory, PREFIX, SUFFIX);
        }
        try {
            return Files.createTempFile(directory, PREFIX, SUFFIX, attributes);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return Files.createTempFile(directory, PREFIX, SUFFIX);
        }
    }

    private static FileAttribute<?>[] creationAttributes() {
        try {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                return new FileAttribute<?>[] {
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
                };
            }
        } catch (RuntimeException ignored) {
            // Create with provider defaults and tighten permissions below.
        }
        return new FileAttribute<?>[0];
    }

    private static void secure(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            Set<PosixFilePermission> expected = PosixFilePermissions.fromString("rw-------");
            posix.setPermissions(expected);
            if (!posix.readAttributes().permissions().equals(expected)) {
                throw new IOException("private_config_permissions");
            }
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl == null) {
            throw new IOException("private_config_permissions");
        }
        UserPrincipal owner = currentUser();
        acl.setAcl(List.of(ownerOnly(owner)));
        verifyAcl(acl, owner);
    }

    private static UserPrincipal currentUser() throws IOException {
        String name = System.getProperty("user.name");
        if (name == null || name.isBlank()) {
            throw new IOException("private_config_permissions");
        }
        try {
            return FileSystems.getDefault().getUserPrincipalLookupService()
                    .lookupPrincipalByName(name);
        } catch (IOException | RuntimeException e) {
            throw new IOException("private_config_permissions");
        }
    }

    private static AclEntry ownerOnly(UserPrincipal owner) {
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(EnumSet.noneOf(AclEntryFlag.class))
                .build();
    }

    private static void verifyAcl(AclFileAttributeView acl, UserPrincipal owner) throws IOException {
        List<AclEntry> entries = acl.getAcl();
        if (entries.size() != 1) {
            throw new IOException("private_config_permissions");
        }
        AclEntry entry = entries.get(0);
        if (entry.type() != AclEntryType.ALLOW
                || !entry.principal().equals(owner)
                || !entry.flags().isEmpty()
                || !entry.permissions().containsAll(EnumSet.allOf(AclEntryPermission.class))) {
            throw new IOException("private_config_permissions");
        }
    }

    private boolean replaced(BasicFileAttributes attributes) {
        return fileKey != null && !fileKey.equals(attributes.fileKey());
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException ignored) {
            // Best effort; the original failure is reported with a code-only message.
        }
    }
}
