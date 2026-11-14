package io.hyperconduit.mod.config;

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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * HyperConduit's HY2 user preferences.
 *
 * <p>This is a brand-new schema stored in {@value #FILE_NAME}. The legacy
 * {@code hyperconduit.json} file is deliberately not read or upgraded. The file is strict:
 * duplicate or unknown keys, comments, single-quoted strings, trailing content, non-canonical
 * integers, wrong JSON types, malformed UTF-8 and values outside the documented ranges are
 * rejected rather than silently repaired.
 *
 * <p>The file may contain client/server tokens, so it is written with owner-only permissions and
 * replaced atomically. Error messages and {@link #toString()} never include token values.
 */
public final class HyperConduitConfig {

    static final String FILE_NAME = "hyperconduit-hy2.json";
    static final int MAX_JSON_BYTES = 64 * 1024;

    private static final int MAX_MBPS = 1_000_000;
    private static final int MAX_TOKEN_LENGTH = 1024;
    private static final int MIN_CONNECT_TIMEOUT_MS = 100;
    private static final int MAX_CONNECT_TIMEOUT_MS = 120_000;
    private static final int MIN_MAX_CONNECTIONS = 1;
    private static final int MAX_MAX_CONNECTIONS = 4096;
    private static final int MAX_PROXY_PROTOCOL_SOURCES = 128;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Disabled by default so an unconfigured installation remains vanilla-compatible. */
    public boolean enabled = false;
    /** Target send rate in Mbps; {@code 0} means automatic. */
    public int upMbps = 0;
    /** Target receive rate in Mbps; {@code 0} means automatic. */
    public int downMbps = 0;
    /** Congestion controller: {@code bbr} or {@code reno}. */
    public String congestion = "bbr";
    /**
     * When true, both directions use local BBR/Reno instead of Brutal. Connections are still
     * accepted; this is a congestion-control choice, not an access-control switch.
     */
    public boolean disableBrutal = false;
    /** When true, the server accepts only HyperConduit UDP sessions. */
    public boolean disableVanillaTcp = false;
    /** Optional HY2 client token. */
    public String clientToken = "";
    /** Optional HY2 server token. */
    public String serverToken = "";
    /** Connection establishment timeout in milliseconds. */
    public int connectTimeoutMs = 5000;
    /** Maximum number of concurrent connections. */
    public int maxConnections = 64;
    /**
     * When true, the host accepts a Proxy Protocol v2 header from the sources listed in
     * {@link #proxyProtocolTrustedSources}. Disabled by default so no peer address is trusted.
     */
    public boolean proxyProtocolV2 = false;
    /**
     * Literal IP addresses or CIDR ranges allowed to send a Proxy Protocol v2 header. Hostnames
     * and IPv6 zone identifiers are never accepted or resolved. Empty means no source is trusted;
     * at most 128 entries are allowed.
     */
    public List<String> proxyProtocolTrustedSources = new ArrayList<>(List.of("127.0.0.1/32", "::1/128"));

    /** Returns an independent copy of this configuration, including token values. */
    public HyperConduitConfig copy() {
        HyperConduitConfig copy = new HyperConduitConfig();
        copy.enabled = enabled;
        copy.upMbps = upMbps;
        copy.downMbps = downMbps;
        copy.congestion = congestion;
        copy.disableBrutal = disableBrutal;
        copy.disableVanillaTcp = disableVanillaTcp;
        copy.clientToken = clientToken;
        copy.serverToken = serverToken;
        copy.connectTimeoutMs = connectTimeoutMs;
        copy.maxConnections = maxConnections;
        copy.proxyProtocolV2 = proxyProtocolV2;
        copy.proxyProtocolTrustedSources = proxyProtocolTrustedSources == null
                ? null
                : new ArrayList<>(proxyProtocolTrustedSources);
        return copy;
    }

    /**
     * Loads {@value #FILE_NAME} from {@code configDir}.
     *
     * <p>A missing file yields defaults and does not create anything. The legacy
     * {@code hyperconduit.json} is ignored. The file is bounded to 64 KiB and must be a strict JSON
     * object with only the documented keys.
     *
     * @throws IllegalStateException with a code-only message when the file is unreadable, too
     *         large, malformed, contains unknown keys or fails validation
     */
    public static HyperConduitConfig load(Path configDir) {
        Objects.requireNonNull(configDir, "configDir");
        Path file = configDir.resolve(FILE_NAME);

        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return new HyperConduitConfig();
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

        HyperConduitConfig config = readStrictJson(decodeUtf8(bytes));
        config.normalize();
        config.validate();
        return config;
    }

    /**
     * Validates and saves this configuration to {@value #FILE_NAME} under {@code configDir}.
     *
     * <p>The file is created in the same directory with owner-only permissions, written, and then
     * atomically moved over the target. The previous file is kept when validation or writing fails.
     *
     * @throws IllegalStateException with a code-only message when validation or writing fails
     */
    public void save(Path configDir) {
        Objects.requireNonNull(configDir, "configDir");
        normalize();
        validate();

        byte[] json = toJsonBytes();
        if (json.length > MAX_JSON_BYTES) {
            throw new IllegalStateException("config_too_large");
        }

        try {
            writePrivateAtomically(configDir, FILE_NAME, json);
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

        validateToken(clientToken, "config_client_token");
        validateToken(serverToken, "config_server_token");
        checkRange(connectTimeoutMs, MIN_CONNECT_TIMEOUT_MS, MAX_CONNECT_TIMEOUT_MS, "config_connect_timeout");
        checkRange(maxConnections, MIN_MAX_CONNECTIONS, MAX_MAX_CONNECTIONS, "config_max_connections");
        validateProxyProtocol();
    }

    private void validateProxyProtocol() {
        List<String> sources = proxyProtocolTrustedSources;
        if (sources == null) {
            throw new IllegalStateException("config_proxy_protocol_source");
        }
        if (sources.size() > MAX_PROXY_PROTOCOL_SOURCES) {
            throw new IllegalStateException("config_proxy_protocol_sources_limit");
        }
        for (String source : sources) {
            if (!isIpOrCidr(source)) {
                throw new IllegalStateException("config_proxy_protocol_source");
            }
        }
        if (proxyProtocolV2 && sources.isEmpty()) {
            throw new IllegalStateException("config_proxy_protocol_sources_required");
        }
    }

    /**
     * Accepts only literal IPv4/IPv6 addresses and CIDR ranges. Hostnames are never resolved and
     * IPv6 zone identifiers, unspecified addresses and IPv4-mapped IPv6 forms are rejected, matching
     * the sidecar's {@code netip}-based validation. For CIDR entries the network address is computed
     * before the checks: a range whose masked address is unspecified (for example
     * {@code 0.0.0.1/8} or {@code 1.2.3.4/0}) is rejected, while the original spelling is kept for
     * saving. Parsing is done here instead of being handed to {@link java.net.InetAddress}, because
     * that API performs DNS lookups for anything it does not recognise as a literal.
     */
    private static boolean isIpOrCidr(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.strip())) {
            return false;
        }
        int slash = value.indexOf('/');
        if (slash < 0) {
            return isAllowedIpv4(value) || isAllowedIpv6(value);
        }
        if (value.indexOf('/', slash + 1) >= 0) {
            return false;
        }
        String address = value.substring(0, slash);
        String prefix = value.substring(slash + 1);
        if (!prefix.matches("0|[1-9][0-9]{0,2}")) {
            return false;
        }
        int bits;
        try {
            bits = Integer.parseInt(prefix);
        } catch (NumberFormatException e) {
            return false;
        }
        if (ipv4Octets(address) != null) {
            return isAllowedIpv4Cidr(address, bits);
        }
        int[] groups = ipv6Groups(address);
        return groups != null && isAllowedIpv6Cidr(groups, bits);
    }

    /**
     * Validates a CIDR after masking its address. {@code /0} is always rejected, as is any range
     * whose network address is unspecified (for example {@code 0.0.0.1/8}).
     */
    private static boolean isAllowedIpv4Cidr(String value, int bits) {
        if (bits <= 0 || bits > 32) {
            return false;
        }
        int[] octets = ipv4Octets(value);
        if (octets == null) {
            return false;
        }
        int address = (octets[0] << 24) | (octets[1] << 16) | (octets[2] << 8) | octets[3];
        int mask = 0xFFFFFFFF << (32 - bits);
        return (address & mask) != 0;
    }

    /** Validates an IPv6 CIDR after masking; {@code /0} and unspecified networks are rejected. */
    private static boolean isAllowedIpv6Cidr(int[] groups, int bits) {
        if (bits <= 0 || bits > 128) {
            return false;
        }
        int[] network = new int[8];
        int full = bits / 16;
        int remainder = bits % 16;
        for (int i = 0; i < 8; i++) {
            if (i < full) {
                network[i] = groups[i];
            } else if (i == full && remainder > 0) {
                network[i] = groups[i] & (0xFFFF << (16 - remainder)) & 0xFFFF;
            }
        }
        return isAllowedIpv6(network);
    }

    private static boolean isAllowedIpv4(String value) {
        int[] octets = ipv4Octets(value);
        return octets != null && (octets[0] | octets[1] | octets[2] | octets[3]) != 0;
    }

    private static boolean isAllowedIpv6(String value) {
        int[] groups = ipv6Groups(value);
        return groups != null && isAllowedIpv6(groups);
    }

    private static boolean isAllowedIpv6(int[] groups) {
        // Reject "::" and the IPv4-mapped ::ffff:0:0/96 block: a mapped address would widen the
        // trusted source to an IPv4 form the sidecar does not treat as a literal source.
        boolean allZero = true;
        for (int group : groups) {
            if (group != 0) {
                allZero = false;
                break;
            }
        }
        if (allZero) {
            return false;
        }
        for (int i = 0; i < 5; i++) {
            if (groups[i] != 0) {
                return true;
            }
        }
        return groups[5] != 0xffff;
    }

    private static int[] ipv4Octets(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) {
                return null;
            }
            int octet = Integer.parseInt(parts[i]);
            if (octet > 255) {
                return null;
            }
            octets[i] = octet;
        }
        return octets;
    }

    private static int[] ipv4Groups(String value) {
        int[] octets = ipv4Octets(value);
        if (octets == null) {
            return null;
        }
        return new int[] {(octets[0] << 8) | octets[1], (octets[2] << 8) | octets[3]};
    }

    /** Returns the eight 16-bit groups of a literal IPv6 address, or {@code null}. */
    private static int[] ipv6Groups(String value) {
        if (value.indexOf(':') < 0 || value.indexOf('%') >= 0) {
            return null;
        }
        int compression = value.indexOf("::");
        if (compression >= 0 && value.indexOf("::", compression + 1) >= 0) {
            return null;
        }
        if (compression < 0) {
            int[] groups = parseIpv6Part(value);
            return groups != null && groups.length == 8 ? groups : null;
        }
        int[] head = parseIpv6Part(value.substring(0, compression));
        int[] tail = parseIpv6Part(value.substring(compression + 2));
        if (head == null || tail == null || head.length + tail.length > 7) {
            return null;
        }
        // An embedded IPv4 address is only valid as the final element, so it cannot sit in the
        // part before "::".
        if (value.substring(0, compression).indexOf('.') >= 0) {
            return null;
        }
        int[] groups = new int[8];
        System.arraycopy(head, 0, groups, 0, head.length);
        System.arraycopy(tail, 0, groups, 8 - tail.length, tail.length);
        return groups;
    }

    /** Parses colon-separated groups; the last one may be an embedded IPv4 address. */
    private static int[] parseIpv6Part(String part) {
        if (part.isEmpty()) {
            return new int[0];
        }
        String[] pieces = part.split(":", -1);
        int[] groups = new int[pieces.length + 1];
        int count = 0;
        for (int i = 0; i < pieces.length; i++) {
            String piece = pieces[i];
            if (piece.isEmpty()) {
                return null;
            }
            if (i == pieces.length - 1 && piece.indexOf('.') >= 0) {
                int[] embedded = ipv4Groups(piece);
                if (embedded == null) {
                    return null;
                }
                groups[count++] = embedded[0];
                groups[count++] = embedded[1];
                continue;
            }
            if (piece.length() > 4) {
                return null;
            }
            int group = 0;
            for (int c = 0; c < piece.length(); c++) {
                char ch = piece.charAt(c);
                int digit;
                if (ch >= '0' && ch <= '9') {
                    digit = ch - '0';
                } else if (ch >= 'a' && ch <= 'f') {
                    digit = ch - 'a' + 10;
                } else if (ch >= 'A' && ch <= 'F') {
                    digit = ch - 'A' + 10;
                } else {
                    return null;
                }
                group = (group << 4) | digit;
            }
            groups[count++] = group;
        }
        return Arrays.copyOf(groups, count);
    }

    private void normalize() {
        if (congestion != null) {
            congestion = congestion.toLowerCase(Locale.ROOT);
        }
        if (proxyProtocolTrustedSources != null) {
            proxyProtocolTrustedSources = new ArrayList<>(proxyProtocolTrustedSources);
        }
    }

    private static void checkRange(int value, int min, int max, String code) {
        if (value < min || value > max) {
            throw new IllegalStateException(code);
        }
    }

    private static void validateToken(String token, String code) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalStateException(code);
        }
        if (token.isEmpty()) {
            return;
        }
        if (Character.isWhitespace(token.charAt(0))
                || Character.isWhitespace(token.charAt(token.length() - 1))) {
            throw new IllegalStateException(code);
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new IllegalStateException(code);
            }
        }
    }

    private static HyperConduitConfig readStrictJson(String json) {
        HyperConduitConfig config = new HyperConduitConfig();
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
                    case "clientToken" -> config.clientToken = readString(reader);
                    case "serverToken" -> config.serverToken = readString(reader);
                    case "connectTimeoutMs" -> config.connectTimeoutMs = readInt(reader);
                    case "maxConnections" -> config.maxConnections = readInt(reader);
                    case "proxyProtocolV2" -> config.proxyProtocolV2 = readBoolean(reader);
                    case "proxyProtocolTrustedSources" ->
                            config.proxyProtocolTrustedSources = readStringArray(reader);
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
            // JsonReader messages may quote the input, which can contain tokens.
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
        json.addProperty("clientToken", clientToken);
        json.addProperty("serverToken", serverToken);
        json.addProperty("connectTimeoutMs", connectTimeoutMs);
        json.addProperty("maxConnections", maxConnections);
        json.addProperty("proxyProtocolV2", proxyProtocolV2);
        JsonArray sources = new JsonArray();
        for (String source : proxyProtocolTrustedSources) {
            sources.add(source);
        }
        json.add("proxyProtocolTrustedSources", sources);
        return GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
    }

    private static void writePrivateAtomically(Path directory, String fileName, byte[] json) throws IOException {
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
                    ? Files.createTempFile(directory, "hyperconduit-", ".json.tmp")
                    : Files.createTempFile(directory, "hyperconduit-", ".json.tmp", attributes);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            temp = Files.createTempFile(directory, "hyperconduit-", ".json.tmp");
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
        return "HyperConduitConfig{enabled=" + enabled + ", congestion='" + congestion
                + "', clientTokenSet=" + !clientToken.isEmpty()
                + ", serverTokenSet=" + !serverToken.isEmpty()
                + ", proxyProtocolV2=" + proxyProtocolV2 + '}';
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof HyperConduitConfig that)) {
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
                && Objects.equals(congestion, that.congestion)
                && Objects.equals(clientToken, that.clientToken)
                && Objects.equals(serverToken, that.serverToken)
                && Objects.equals(proxyProtocolTrustedSources, that.proxyProtocolTrustedSources);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, upMbps, downMbps, congestion, disableBrutal, disableVanillaTcp,
                clientToken, serverToken, connectTimeoutMs, maxConnections, proxyProtocolV2,
                proxyProtocolTrustedSources);
    }
}
