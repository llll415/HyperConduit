package io.hyperconduit.velocity.status;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.hyperconduit.velocity.config.VelocityConfig;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusJsonTest {

    @Test
    void declarationUsesRunningPolicyAndFiveWireFields() {
        VelocityConfig running = new VelocityConfig();
        running.enabled = true;
        running.disableBrutal = true;
        running.serverToken = "secret";

        JsonObject declaration = StatusDeclaration.fromRunning(running);
        assertEquals(Set.of("protocol", "enabled", "tcpAllowed", "disableBrutal", "tokenRequired"),
                declaration.keySet());
        assertEquals(2, declaration.get("protocol").getAsInt());
        assertTrue(declaration.get("enabled").getAsBoolean());
        assertTrue(declaration.get("tcpAllowed").getAsBoolean());
        assertTrue(declaration.get("disableBrutal").getAsBoolean());
        assertTrue(declaration.get("tokenRequired").getAsBoolean());
    }

    @Test
    void disabledOrMissingRunningConfigDoesNotDeclare() {
        assertNull(StatusDeclaration.fromRunning(null));
        VelocityConfig disabled = new VelocityConfig();
        assertNull(StatusDeclaration.fromRunning(disabled));
    }

    @Test
    void augmentPreservesEveryOtherFieldAndDoesNotMutateInput() {
        JsonObject status = new JsonObject();
        status.addProperty("description", "A Minecraft Server");
        status.addProperty("favicon", "data:image/png;base64,AAAA");
        JsonObject players = new JsonObject();
        players.addProperty("max", 20);
        players.addProperty("online", 1);
        status.add("players", players);
        JsonArray modInfo = new JsonArray();
        modInfo.add("hyperconduit");
        status.add("modinfo", modInfo);

        JsonObject declaration = new JsonObject();
        declaration.addProperty("protocol", 2);
        declaration.addProperty("enabled", true);
        declaration.addProperty("tcpAllowed", true);
        declaration.addProperty("disableBrutal", false);
        declaration.addProperty("tokenRequired", false);

        JsonObject augmented = StatusJson.augment(status, declaration);

        assertEquals("A Minecraft Server", augmented.get("description").getAsString());
        assertEquals("data:image/png;base64,AAAA", augmented.get("favicon").getAsString());
        assertEquals(20, augmented.getAsJsonObject("players").get("max").getAsInt());
        assertEquals("hyperconduit", augmented.getAsJsonArray("modinfo").get(0).getAsString());
        assertEquals(2, augmented.getAsJsonObject("hyperconduit").get("protocol").getAsInt());
        assertFalse(status.has("hyperconduit"), "the original status object was mutated");
    }

    @Test
    void augmentHandlesMissingInputs() {
        assertNull(StatusJson.augment(null, new JsonObject()));
        JsonObject status = new JsonObject();
        assertSame(status, StatusJson.augment(status, null));
    }
}