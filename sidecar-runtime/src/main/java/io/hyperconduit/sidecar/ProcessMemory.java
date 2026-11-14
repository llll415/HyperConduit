package io.hyperconduit.sidecar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort, bounded resident-set-size lookup for the current OS.
 *
 * <p>Linux reads {@code /proc/<pid>/status} directly. macOS and Windows use a
 * single short-lived OS command ({@code ps} and {@code tasklist}) with an
 * absolute executable path, a strict timeout, and a small output bound. No
 * shell is involved and the process environment is inherited unchanged.
 *
 * <p>A lookup never throws: an unsupported platform, a missing tool, a
 * permission error, a timeout, or unparsable output all return {@code null},
 * which callers must report as "unavailable" rather than zero.
 */
final class ProcessMemory {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(1);
    private static final int MAX_OUTPUT_BYTES = 4096;
    private static final String OS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private ProcessMemory() {
    }

    /** True when RSS requires an OS command instead of a cheap procfs read. */
    static boolean commandBased() {
        return !OS.contains("linux");
    }

    static Long read(long pid) {
        try {
            if (OS.contains("linux")) return linux(pid);
            if (OS.contains("mac") || OS.contains("darwin") || OS.equals("osx")) return mac(pid);
            if (OS.contains("win")) return windows(pid);
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    private static Long linux(long pid) throws IOException {
        Path status = Path.of("/proc", Long.toString(pid), "status");
        try (BufferedReader reader = Files.newBufferedReader(status, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmRSS:")) return kilobytes(line.substring("VmRSS:".length()));
            }
        }
        return null;
    }

    private static Long mac(long pid) throws IOException, InterruptedException {
        String ps = executable("/bin/ps", "/usr/bin/ps");
        if (ps == null) return null;
        return parsePsRss(run(List.of(ps, "-o", "rss=", "-p", Long.toString(pid))));
    }

    private static Long windows(long pid) throws IOException, InterruptedException {
        String tasklist = executable(system32("tasklist.exe"));
        if (tasklist == null) return null;
        return parseTasklistCsv(
                run(List.of(tasklist, "/FI", "PID eq " + pid, "/FO", "CSV", "/NH")), pid);
    }

    private static String system32(String name) {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) root = System.getenv("WINDIR");
        if (root == null || root.isBlank()) return null;
        return Path.of(root, "System32", name).toString();
    }

    private static String executable(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null) continue;
            Path path = Path.of(candidate);
            if (path.isAbsolute() && Files.isRegularFile(path) && Files.isExecutable(path)) {
                return path.toString();
            }
        }
        return null;
    }

    private static String run(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (!command.isEmpty()) {
            Path executable = Path.of(command.get(0));
            Path parent = executable.isAbsolute() ? executable.getParent() : null;
            // Keep the OS tool out of a game/working directory that could
            // contain a malicious DLL or helper executable.
            if (parent != null) builder.directory(parent.toFile());
        }
        Process child = builder.start();
        try {
            child.getOutputStream().close();
            if (!child.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                child.destroyForcibly();
                return null;
            }
            if (child.exitValue() != 0) return null;
            try (InputStream input = child.getInputStream()) {
                byte[] buffer = new byte[MAX_OUTPUT_BYTES];
                int size = 0;
                int read;
                while (size < buffer.length
                        && (read = input.read(buffer, size, buffer.length - size)) != -1) {
                    size += read;
                }
                return new String(buffer, 0, size, StandardCharsets.UTF_8);
            }
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    /** Parses the {@code VmRSS: 1234 kB} line of {@code /proc/<pid>/status}. */
    static Long parseLinuxStatus(String text) {
        if (text == null) return null;
        for (String line : text.split("\\R")) {
            if (line.startsWith("VmRSS:")) return kilobytes(line.substring("VmRSS:".length()));
        }
        return null;
    }

    /** Parses {@code ps -o rss= -p <pid>} output, which is in 1024-byte units. */
    static Long parsePsRss(String text) {
        return kilobytes(text);
    }

    /**
     * Parses one {@code tasklist /FO CSV /NH} row. The memory column is
     * localized but always ends in a K-based unit, so only its digits are
     * used. The PID column is matched exactly.
     */
    static Long parseTasklistCsv(String text, long pid) {
        if (text == null) return null;
        String expectedPid = Long.toString(pid);
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            List<String> fields = csvFields(line);
            if (fields.size() < 5 || !fields.get(1).trim().equals(expectedPid)) continue;
            String digits = fields.get(fields.size() - 1).replaceAll("[^0-9]", "");
            if (digits.isEmpty()) return null;
            try {
                long kib = Long.parseLong(digits);
                return kib > Long.MAX_VALUE / 1024 ? null : kib * 1024;
            } catch (NumberFormatException overflow) {
                return null;
            }
        }
        return null;
    }

    private static Long kilobytes(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        int end = 0;
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) end++;
        if (end == 0) return null;
        try {
            long kib = Long.parseLong(trimmed.substring(0, end));
            return kib > Long.MAX_VALUE / 1024 ? null : kib * 1024;
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    /** Minimal CSV parser: quoted fields may contain commas and doubled quotes. */
    private static List<String> csvFields(String line) {
        List<String> fields = new ArrayList<>(6);
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (quoted) {
                if (value == '"') {
                    if (index + 1 < line.length() && line.charAt(index + 1) == '"') {
                        current.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(value);
                }
            } else if (value == '"') {
                quoted = true;
            } else if (value == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(value);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
