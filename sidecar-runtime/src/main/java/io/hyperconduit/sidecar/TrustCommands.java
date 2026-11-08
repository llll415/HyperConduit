package io.hyperconduit.sidecar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Bounded one-shot control commands; only Go reads/writes the trust database. */
public final class TrustCommands {
    private TrustCommands() {}

    public static Map<String, String> list(Path cache, Path launch, Path state, Path working) throws IOException {
        JsonObject response = run(cache, launch, state, working, null);
        Map<String, String> entries = new TreeMap<>();
        for (var value : response.getAsJsonArray("entries")) {
            JsonObject entry = value.getAsJsonObject();
            entries.put(entry.get("endpoint").getAsString(), entry.get("fingerprint").getAsString());
        }
        return Map.copyOf(entries);
    }

    public static void forget(Path cache, Path launch, Path state, Path working, String endpoint) throws IOException {
        run(cache, launch, state, working, endpoint);
    }

    private static JsonObject run(Path cache, Path launch, Path state, Path working, String endpoint) throws IOException {
        Path binary = new BinaryExtractor().extract(cache);
        try (PrivateConfig config = PrivateConfig.create(launch, Map.of("server", "127.0.0.1:25565", "stateDir", state.toString()))) {
            List<String> args = new ArrayList<>(List.of(binary.toString(), "trust", endpoint == null ? "list" : "forget", "--config", config.path().toString()));
            if (endpoint != null) args.addAll(List.of("--server", endpoint));
            Process child = new ProcessBuilder(args).directory(working.toFile()).start();
            FutureTask<byte[]> output = new FutureTask<>(() -> {
                byte[] bytes = child.getInputStream().readNBytes((1 << 20) + 1);
                if (bytes.length > (1 << 20)) { child.destroyForcibly(); throw new IOException("trust_response_limit"); }
                return bytes;
            });
            Thread.ofPlatform().daemon().start(output);
            Thread.ofPlatform().daemon().start(() -> {
                try { child.getErrorStream().transferTo(java.io.OutputStream.nullOutputStream()); }
                catch (IOException ignored) {}
            });
            try {
                child.getOutputStream().close();
                if (!child.waitFor(8, TimeUnit.SECONDS)) throw new IOException("trust_command_timeout");
                if (child.exitValue() != 0) throw new IOException("trust_command_failed");
                JsonObject value = JsonParser.parseString(new String(output.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8)).getAsJsonObject();
                if (value.get("version").getAsInt() != 1) throw new IOException("trust_protocol_version");
                return value;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("trust_command_canceled");
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                throw new IOException("trust_command_failed");
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                    try { child.waitFor(2, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                try { child.getInputStream().close(); } catch (IOException ignored) {}
                try { child.getErrorStream().close(); } catch (IOException ignored) {}
            }
        }
    }
}
