package io.hyperconduit.sidecar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the real classpath resources shipped for every supported platform are complete
 * and internally consistent.
 *
 * <p>These tests never execute a bundled executable, so they are safe on any host and do not
 * require emulation. The actual binary format is checked from its header only: PE for Windows,
 * ELF for Linux and Mach-O for macOS.
 */
class BundledPlatformsTest {

    private static final ClassLoader CLASSPATH = BinaryExtractor.class.getClassLoader();
    private static final String LICENSE_ROOT = "META-INF/hyperconduit-sidecar";
    private static final long MIN_BINARY_BYTES = 3L * 1024 * 1024;
    private static final Pattern NOTICE_LICENSE_LINK =
            Pattern.compile("\\]\\((licenses/[^)]+)\\)");

    @TempDir
    Path cache;

    static Stream<PlatformCase> bundledPlatforms() {
        return Stream.of(
                new PlatformCase(new Platform("windows", "amd64"),
                        BinaryFormat.PE, 0x8664L),
                new PlatformCase(new Platform("linux", "amd64"),
                        BinaryFormat.ELF, 62L),
                new PlatformCase(new Platform("linux", "arm64"),
                        BinaryFormat.ELF, 183L),
                new PlatformCase(new Platform("darwin", "amd64"),
                        BinaryFormat.MACH_O, 0x01000007L),
                new PlatformCase(new Platform("darwin", "arm64"),
                        BinaryFormat.MACH_O, 0x0100000CL));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bundledPlatforms")
    void bundlesVerifiedNativeBinary(PlatformCase testCase) throws Exception {
        Platform platform = testCase.platform();
        String directory = "io/hyperconduit/sidecar/bin/" + platform.id();
        assertEquals(directory, BinaryExtractor.resourceDirectory(platform),
                () -> "Unexpected bundled resource directory for " + platform.id());
        String manifestResource = directory + "/manifest.json";
        String binaryResource = directory + "/" + platform.executableName();

        assertNotNull(CLASSPATH.getResource(manifestResource),
                () -> "Missing bundled manifest on the runtime classpath: " + manifestResource);
        assertNotNull(CLASSPATH.getResource(binaryResource),
                () -> "Missing bundled binary on the runtime classpath: " + binaryResource);

        JsonObject manifest = readManifest(manifestResource);
        assertEquals(Set.of("protocolVersion", "version", "sha256", "file"),
                manifest.keySet(), () -> "Unexpected manifest schema for " + platform.id());
        assertEquals(BinaryExtractor.PROTOCOL_VERSION,
                manifest.get("protocolVersion").getAsInt(),
                () -> "Unexpected protocol version for " + platform.id());
        assertEquals(BinaryExtractor.EXPECTED_VERSION,
                manifest.get("version").getAsString(),
                () -> "Unexpected sidecar version for " + platform.id());
        assertEquals(platform.executableName(),
                manifest.get("file").getAsString(),
                () -> "Manifest must name the exact platform executable for " + platform.id());
        String expectedSha = manifest.get("sha256").getAsString();
        assertTrue(expectedSha.matches("[0-9a-f]{64}"),
                () -> "Manifest sha256 must be 64 lowercase hex characters for " + platform.id());

        // extract() re-checks the manifest, copies through a temporary file, verifies SHA-256 and
        // only then atomically installs the binary. It does not execute anything.
        BinaryExtractor extractor = new BinaryExtractor(CLASSPATH);
        Path extracted = extractor.extract(cache, platform);

        Path expectedPath = cache.toAbsolutePath().normalize()
                .resolve(platform.id())
                .resolve(expectedSha)
                .resolve(platform.executableName());
        assertEquals(expectedPath, extracted,
                () -> "Unexpected content-addressed cache path for " + platform.id());
        assertTrue(Files.isRegularFile(extracted),
                () -> "Extracted sidecar binary is not a regular file for " + platform.id());

        long size = Files.size(extracted);
        assertTrue(size >= MIN_BINARY_BYTES,
                () -> platform.id() + " binary is unexpectedly small: " + size + " bytes");
        assertTrue(size <= BinaryExtractor.MAX_BINARY_BYTES,
                () -> platform.id() + " binary exceeds the 64 MiB limit: " + size + " bytes");
        assertEquals(expectedSha, BinaryExtractor.sha256Hex(extracted),
                () -> "Extracted binary does not match the manifest SHA-256 for " + platform.id());

        assertNativeHeader(extracted, testCase);
    }

    @Test
    void bundlesLicenseAndThirdPartyNoticeResources() throws IOException {
        String goLicense = readText(LICENSE_ROOT + "/GO-LICENSE");
        assertTrue(goLicense.contains("Copyright"),
                "GO-LICENSE is missing the Go copyright notice");
        assertTrue(goLicense.contains("Redistribution and use in source and binary forms"),
                "GO-LICENSE is missing the Go BSD license text");

        String hy2License = readText(LICENSE_ROOT + "/HY2-LICENSE.md");
        assertTrue(hy2License.contains("Permission is hereby granted"),
                "HY2-LICENSE.md is missing the upstream MIT license text");

        String notices = readText(LICENSE_ROOT + "/THIRD_PARTY_NOTICES.md");
        assertTrue(notices.contains("第三方软件许可声明"),
                "THIRD_PARTY_NOTICES.md is missing its heading");
        assertTrue(notices.contains("go list -deps"),
                "THIRD_PARTY_NOTICES.md is missing the documented generation command");

        Matcher matcher = NOTICE_LICENSE_LINK.matcher(notices);
        Set<String> referenced = new HashSet<>();
        while (matcher.find()) {
            String relative = matcher.group(1);
            if (!referenced.add(relative)) {
                continue;
            }
            String resource = LICENSE_ROOT + "/" + relative;
            assertNotNull(CLASSPATH.getResource(resource),
                    () -> "Third-party notices reference a missing resource: " + resource);
            assertFalse(readText(resource).isBlank(),
                    () -> "Bundled third-party license is empty: " + resource);
        }
        assertTrue(referenced.size() >= 10,
                "Expected the notices to reference the bundled third-party licenses, found "
                        + referenced.size());
    }

    private static JsonObject readManifest(String resource) throws IOException {
        byte[] bytes;
        try (InputStream in = CLASSPATH.getResourceAsStream(resource)) {
            assertNotNull(in, () -> "Missing bundled manifest resource: " + resource);
            bytes = in.readNBytes(BinaryExtractor.MAX_MANIFEST_BYTES + 1);
        }
        assertTrue(bytes.length <= BinaryExtractor.MAX_MANIFEST_BYTES,
                () -> "Bundled manifest exceeds the 16 KiB limit: " + resource);
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static String readText(String resource) throws IOException {
        try (InputStream in = CLASSPATH.getResourceAsStream(resource)) {
            assertNotNull(in, () -> "Missing bundled resource: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertNativeHeader(Path binary, PlatformCase testCase) throws IOException {
        try (FileChannel channel = FileChannel.open(binary, StandardOpenOption.READ)) {
            switch (testCase.format()) {
                case PE -> assertPeHeader(channel, testCase.expectedMachine());
                case ELF -> assertElfHeader(channel, testCase.expectedMachine());
                case MACH_O -> assertMachOHeader(channel, testCase.expectedMachine());
            }
        }
    }

    /** PE: DOS "MZ", e_lfanew at 0x3c, "PE\\0\\0" signature and the COFF machine field. */
    private static void assertPeHeader(FileChannel channel, long expectedMachine)
            throws IOException {
        byte[] dosHeader = readAt(channel, 0, 64);
        assertEquals('M', dosHeader[0] & 0xFF, "PE binary must start with the DOS MZ magic");
        assertEquals('Z', dosHeader[1] & 0xFF, "PE binary must start with the DOS MZ magic");

        long peOffset = readUInt32LE(dosHeader, 0x3c);
        assertTrue(peOffset >= 64 && peOffset + 6 <= channel.size(),
                () -> "PE header offset at 0x3c is out of range: " + peOffset);
        byte[] peHeader = readAt(channel, peOffset, 6);
        assertEquals('P', peHeader[0] & 0xFF, "PE signature must be 'P'");
        assertEquals('E', peHeader[1] & 0xFF, "PE signature must be 'E'");
        assertEquals(0, peHeader[2] & 0xFF, "PE signature must be followed by two zero bytes");
        assertEquals(0, peHeader[3] & 0xFF, "PE signature must be followed by two zero bytes");
        assertEquals(expectedMachine, readUInt16LE(peHeader, 4),
                "Unexpected PE COFF machine type");
    }

    /** ELF: 64-bit little-endian header with e_machine at offset 18. */
    private static void assertElfHeader(FileChannel channel, long expectedMachine)
            throws IOException {
        byte[] header = readAt(channel, 0, 20);
        assertArrayEquals(new byte[] {0x7F, 'E', 'L', 'F'}, Arrays.copyOf(header, 4),
                "ELF binary must start with the ELF magic");
        assertEquals(2, header[4] & 0xFF, "ELF EI_CLASS must be 64-bit");
        assertEquals(1, header[5] & 0xFF, "ELF EI_DATA must be little-endian");
        assertEquals(expectedMachine, readUInt16LE(header, 18),
                "Unexpected ELF e_machine value");
    }

    /** Mach-O: 64-bit little-endian magic and the cputype field at offset 4. */
    private static void assertMachOHeader(FileChannel channel, long expectedCpuType)
            throws IOException {
        byte[] header = readAt(channel, 0, 8);
        assertEquals(0xFEEDFACFL, readUInt32LE(header, 0),
                "Mach-O binary must use the 64-bit little-endian magic (cf fa ed fe)");
        assertEquals(expectedCpuType, readUInt32LE(header, 4),
                "Unexpected Mach-O cputype value");
    }

    private static byte[] readAt(FileChannel channel, long position, int length)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
                throw new IOException("Unexpected end of file at offset " + position);
            }
        }
        return buffer.array();
    }

    private static int readUInt16LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static long readUInt32LE(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF))
                | ((long) (data[offset + 1] & 0xFF) << 8)
                | ((long) (data[offset + 2] & 0xFF) << 16)
                | ((long) (data[offset + 3] & 0xFF) << 24);
    }

    private enum BinaryFormat {
        PE,
        ELF,
        MACH_O
    }

    private record PlatformCase(Platform platform, BinaryFormat format, long expectedMachine) {
        @Override
        public String toString() {
            return platform.id();
        }
    }
}
