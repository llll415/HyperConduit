package io.hyperconduit.sidecar;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Test-only child process that speaks the managed sidecar protocol. It never opens a socket; the
 * parent-side lifecycle only needs a valid READY event and a process that waits for shutdown.
 */
public final class ControllerProcessFixture {
    private ControllerProcessFixture() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("{\"version\":1,\"event\":\"ready\",\"mode\":\"server\","
                + "\"listen\":\"127.0.0.1:12345\"}");
        System.out.flush();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains("shutdown")) return;
        }
    }
}
