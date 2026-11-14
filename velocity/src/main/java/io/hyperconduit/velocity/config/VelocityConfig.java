package io.hyperconduit.velocity.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * HyperConduit's Velocity-side configuration.
 *
 * <p>The file is stored in the plugin data directory and is read from disk on every operation;
 * there is no in-memory cache. It is strict: duplicate or unknown keys, comments, trailing
 * content, non-canonical integers, wrong JSON types, malformed UTF-8 and values outside the
 * documented ranges are rejected rather than silently repaired.</p>
 *
 * <p>The file may contain the server token, so it is written with owner-only permissions and
 * replaced atomically. Error messages and {@link #toString()} never include token values.</p>
 */
public final class VelocityConfig {

    public static final String FILE_NAME = "config.json";
    public static final int MAX_JSON_BYTES = 64 * 1024;

    private static final int MAX_MBPS = 1_000_000;
    private static final int MAX_TOKEN_LENGTH = 1024;
    private static final int MIN_CONNECT_TIMEOUT_MS = 100;
    private static final int MAX_CONNECT_TIMEOUT_MS = 120_000;
    private static final int MIN_MAX_CONNECTIONS = 1;
    private static final int MAX_MAX_CONNECTIONS = 4096;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Disabled by default so installing the plugin does not change the proxy without opt-in. */
    public boolean enabled = false;
    /** Target send rate in Mbps; {@code 0} means automatic. */
    public int upMbps = 0;
    /** Target receive rate in Mbps; {@code 0} means automatic. */
    public int downMbps = 0;
    /** Congestion controller: {@code bbr} or {@code reno}. */
    public String congestion = "bbr";
    /** When true, both directions use local BBR/Reno instead of Brutal. */
    public boolean disableBrutal = false;
    /**
     * Retained for compatibility with the NeoForge configuration. It has no effect on Velocity:
     * the plugin never closes Velocity's public TCP listener, so {@code tcpAllowed} is always true.
     */
    public boolean disableVanillaTcp = false;
    /** Optional HY2 server token. */
    public String serverToken = "";
    /** Connection establishment timeout in milliseconds. */
    public int connectTimeoutMs = 5000;
    /** Maximum number of concurrent tunnel connections. */
    public int maxConnections = 64;
    /**
     * When true, the UDP/QUIC listener consumes a Proxy Protocol v2 header from the sources in
     * {@link #proxyProtocolTrustedSources}. This is the FRP input path and is disabled by default.
     */
    public boolean proxyProtocolV2 = false;
    /**
     * Literal IP addresses or CIDR ranges allowed to send an input Proxy Protocol v2 header.
     * Hostnames and IPv6 zone identifiers are never accepted or resolved. Empty means no source
     * is trusted; at most 128 entries are allowed.
     */
    public List<String> proxyProtocolTrustedSources =
            new ArrayList<>(List.of("127.0.0.1/32", "::1/128"));
    /**
     * When true, the tunnel process writes a Proxy Protocol v2 header to Velocity's TCP listener
     * before forwarding each connection, preserving the real player address. Velocity's own
     * {@code haproxy-protocol} switch must be enabled separately; the public ProxyConfig API does
     * not expose it, so this plugin cannot detect or enable it.
     */
    public boolean proxyProtocolV2Output = true;

    /** Returns an independent copy of this configuration, including the token. */
    public VelocityConfig copy() {
        VelocityConfig copy = new VelocityConfig();
        copy.enabled = enabled;
        copy.upMbps = upMbps;
        copy.downMbps = downMbps;
        copy.congestion = congestion;
        copy.disableBrutal = disableBrutal;
        copy.disableVanillaTcp = disableVanillaTcp;
        copy.serverToken = serverToken;
        copy.connectTimeoutMs = connectTimeoutMs;
        copy.maxConnections = maxConnections;
        copy.proxyProtocolV2 = proxyProtocolV2;
        copy.proxyProtocolTrustedSources = proxyProtocolTrustedSources == null
                ? null
                : new ArrayList<>(proxyProtocolTrustedSources);
        copy.proxyProtocolV2Output = proxyProtocolV2Output;
        return copy;
    }

    /**
     * Loads {@value #FILE_NAME} from {@code dataDirectory}. A missing file yields defaults and
     * does not create anything.
     *
     * @throws IllegalStateException with a code-only message when the file is unreadable, too
     *         large, malformed, contains unknown keys or fails validation
     */
    public static VelocityConfig load(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Path file = dataDirectory.resolve(FILE_NAME);

        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return new VelocityConfig();
        } catch (IOException e) {
            throw new IllegalStateException("config_read", e);
        }

        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IllegalStateException("config_invalid");
        }
        if (attributes.size() > MAX_JSON_BYTES) {
            throw new IllegalStateException("config_too_large");
        }

        byte[] bytes;
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ)) {
            bytes = input.readNBytes(MAX_JSON_BYTES + 1);
        } catch (IOException e) {
            throw new IllegalStateException("config_read", e);
        }
        if (bytes.length > MAX_JSON_BYTES) {
            throw new IllegalStateException("config_too_large");
        }

        VelocityConfig config = readStrictJson(decodeUtf8(bytes));
        config.normalize();
        config.validate();
        return config;
    }

    /**
     * Validates and saves this configuration to {@value #FILE_NAME} under {@code dataDirectory}.
     * The file is created in the same directory with owner-only permissions, written, and then
     * atomically moved over the target.
     *
     * @throws IllegalStateException with a code-only message when validation or writing fails
     */
    public void save(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        normalize();
        validate();

        byte[] json = toJsonBytes();
        if (json.length > MAX_JSON_BYTES) {
            throw new IllegalStateException("config_too_large");
        }

        try {
            writePrivateAtomically(dataDirectory, FILE_NAME, json);
        } catch (IOException e) {
            throw new IllegalStateException("config_write", e);
        }
    }

    /**
     * Checks every field against the supported ranges.
     *
     * @throws IllegalStateException with a code-only message when a value is invalid
     */
    public void validate() {
        checkRange(upMbps, 0, MAX_MBPS, "config_up_mbps");
        checkRange(downMbps, 0, MAX_MBPS, "config_down_mbps");

        if (congestion == null) {
            throw new IllegalStateException("config_congestion");
        }
        String controller = congestion.toLowerCase(Locale.ROOT);
        if (!controller.equals("bbr") && !controller.equals("reno")) {
            throw new IllegalStateException("config_congestion");
        }

        validateToken(serverToken);
        checkRange(connectTimeoutMs, MIN_CONNECT_TIMEOUT_MS, MAX_CONNECT_TIMEOUT_MS,
                "config_connect_timeout");
        checkRange(maxConnections, MIN_MAX_CONNECTIONS, MAX_MAX_CONNECTIONS, "config_max_connections");
        validateProxyProtocol();
    }

    private void validateProxyProtocol() {
        List<String> sources;
        try {
            sources = ProxyProtocolSources.validateAll(proxyProtocolTrustedSources);
        } catch (ProxyProtocolSources.InvalidSourceException failure) {
            if (failure.problem() == ProxyProtocolSources.Problem.TOO_MANY) {
                throw new IllegalStateException("config_proxy_protocol_sources_limit");
            }
            throw new IllegalStateException("config_proxy_protocol_source");
        }
        if (proxyProtocolV2 && sources.isEmpty()) {
            throw new IllegalStateException("config_proxy_protocol_sources_required");
        }
        proxyProtocolTrustedSources = new ArrayList<>(sources);
    }

    private static void checkRange(int value, int min, int max, String code) {
        if (value < min || value > max) {
            throw new IllegalStateException(code);
        }
    }

    private static void validateToken(String token) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalStateException("config_server_token");
        }
        if (token.isEmpty()) {
            return;
        }
        if (Character.isWhitespace(token.charAt(0))
                || Character.isWhitespace(token.charAt(token.length() - 1))) {
            throw new IllegalStateException("config_server_token");
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new IllegalStateException("config_server_token");
            }
        }
    }

    private void normalize() {
        if (congestion != null) {
            congestion = congestion.toLowerCase(Locale.ROOT);
        }
        if (proxyProtocolTrustedSources != null) {
            proxyProtocolTrustedSources = new ArrayList<>(proxyProtocolTrustedSources);
        }
    }

    private static VelocityConfig readStrictJson(String json) {
        VelocityConfig config = new VelocityConfig();
        Set<String> seen = new HashSet<>();
        try {
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new StrictConfigException("config_invalid");
            }
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!seen.add(name)) {
                    throw new StrictConfigException("config_duplicate_field");
                }
                switch (name) {
                    case "enabled" -> config.enabled = readBoolean(reader);
                    case "upMbps" -> config.upMbps = readInt(reader);
                    case "downMbps" -> config.downMbps = readInt(reader);
                    case "congestion" -> config.congestion = readString(reader);
                    case "disableBrutal" -> config.disableBrutal = readBoolean(reader);
                    case "disableVanillaTcp" -> config.disableVanillaTcp = readBoolean(reader);
                    case "serverToken" -> config.serverToken = readString(reader);
                    case "connectTimeoutMs" -> config.connectTimeoutMs = readInt(reader);
                    case "maxConnections" -> config.maxConnections = readInt(reader);
                    case "proxyProtocolV2" -> config.proxyProtocolV2 = readBoolean(reader);
                    case "proxyProtocolTrustedSources" ->
                            config.proxyProtocolTrustedSources = readStringArray(reader);
                    case "proxyProtocolV2Output" -> config.proxyProtocolV2Output = readBoolean(reader);
                    default -> throw new StrictConfigException("config_unknown_field");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new StrictConfigException("config_invalid");
            }
        } catch (StrictConfigException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // JsonReader messages may quote the input, which can contain the token.
            throw new IllegalStateException("config_invalid");
        }
        return config;
    }

    private static boolean readBoolean(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BOOLEAN) {
            throw new StrictConfigException("config_invalid");
        }
        return reader.nextBoolean();
    }

    private static int readInt(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) {
            throw new StrictConfigException("config_invalid");
        }
        String raw = reader.nextString();
        // Reject 1.0, 1e3, -0, leading zeros and other non-canonical integer spellings.
        if (!raw.matches("0|-?[1-9][0-9]*")) {
            throw new StrictConfigException("config_invalid");
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new StrictConfigException("config_invalid");
        }
    }

    private static String readString(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) {
            throw new StrictConfigException("config_invalid");
        }
        return reader.nextString();
    }

    private static List<String> readStringArray(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            throw new StrictConfigException("config_invalid");
        }
        List<String> values = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.STRING) {
                throw new StrictConfigException("config_invalid");
            }
            values.add(reader.nextString());
        }
        reader.endArray();
        return values;
    }

    private static final class StrictConfigException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        StrictConfigException(String code) {
            super(code);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("config_invalid");
        }
    }

    private byte[] toJsonBytes() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("upMbps", upMbps);
        json.addProperty("downMbps", downMbps);
        json.addProperty("congestion", congestion);
        json.addProperty("disableBrutal", disableBrutal);
        json.addProperty("disableVanillaTcp", disableVanillaTcp);
        json.addProperty("serverToken", serverToken);
        json.addProperty("connectTimeoutMs", connectTimeoutMs);
        json.addProperty("maxConnections", maxConnections);
        json.addProperty("proxyProtocolV2", proxyProtocolV2);
        JsonArray sources = new JsonArray();
        for (String source : proxyProtocolTrustedSources) {
            sources.add(source);
        }
        json.add("proxyProtocolTrustedSources", sources);
        json.addProperty("proxyProtocolV2Output", proxyProtocolV2Output);
        return GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
    }

    private static void writePrivateAtomically(Path directory, String fileName, byte[] json)
            throws IOException {
        Path root;
        try {
            root = directory.toAbsolutePath().normalize();
            Files.createDirectories(root);
        } catch (IOException | RuntimeException e) {
            throw new IOException("config_directory");
        }

        Path target = root.resolve(fileName);
        Path temp = null;
        try {
            temp = createPrivateTempFile(root);
            Files.write(temp, json, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            moveReplacing(temp, target);
            temp = null;
        } finally {
            if (temp != null) {
                deleteQuietly(temp);
            }
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException | UnsupportedOperationException e) {
            throw new IOException("config_atomic_replace_unavailable");
        }
    }

    private static Path createPrivateTempFile(Path directory) throws IOException {
        FileAttribute<?>[] attributes = creationAttributes();
        Path temp;
        try {
            temp = attributes.length == 0
                    ? Files.createTempFile(directory, "hyperconduit-velocity-", ".json.tmp")
                    : Files.createTempFile(directory, "hyperconduit-velocity-", ".json.tmp", attributes);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            temp = Files.createTempFile(directory, "hyperconduit-velocity-", ".json.tmp");
        }

        try {
            secure(temp);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            throw new IOException("config_permissions");
        }
        return temp;
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
                throw new IOException("config_permissions");
            }
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl == null) {
            throw new IOException("config_permissions");
        }
        UserPrincipal owner = currentUser();
        acl.setAcl(List.of(ownerOnly(owner)));
        verifyAcl(acl, owner);
    }

    private static UserPrincipal currentUser() throws IOException {
        String name = System.getProperty("user.name");
        if (name == null || name.isBlank()) {
            throw new IOException("config_permissions");
        }
        try {
            return FileSystems.getDefault().getUserPrincipalLookupService()
                    .lookupPrincipalByName(name);
        } catch (IOException | RuntimeException e) {
            throw new IOException("config_permissions");
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
            throw new IOException("config_permissions");
        }
        AclEntry entry = entries.get(0);
        if (entry.type() != AclEntryType.ALLOW
                || !entry.principal().equals(owner)
                || !entry.flags().isEmpty()
                || !entry.permissions().containsAll(EnumSet.allOf(AclEntryPermission.class))) {
            throw new IOException("config_permissions");
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException ignored) {
            // Best effort; the original failure is reported with a code-only message.
        }
    }

    @Override
    public String toString() {
        return "VelocityConfig{enabled=" + enabled + ", congestion='" + congestion
                + "', serverTokenSet=" + (serverToken != null && !serverToken.isEmpty())
                + ", proxyProtocolV2=" + proxyProtocolV2
                + ", proxyProtocolV2Output=" + proxyProtocolV2Output + '}';
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VelocityConfig that)) {
            return false;
        }
        return enabled == that.enabled
                && upMbps == that.upMbps
                && downMbps == that.downMbps
                && disableBrutal == that.disableBrutal
                && disableVanillaTcp == that.disableVanillaTcp
                && connectTimeoutMs == that.connectTimeoutMs
                && maxConnections == that.maxConnections
                && proxyProtocolV2 == that.proxyProtocolV2
                && proxyProtocolV2Output == that.proxyProtocolV2Output
                && Objects.equals(congestion, that.congestion)
                && Objects.equals(serverToken, that.serverToken)
                && Objects.equals(proxyProtocolTrustedSources, that.proxyProtocolTrustedSources);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, upMbps, downMbps, congestion, disableBrutal, disableVanillaTcp,
                serverToken, connectTimeoutMs, maxConnections, proxyProtocolV2,
                proxyProtocolTrustedSources, proxyProtocolV2Output);
    }
}
