package com.blackn0va.baumeister.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.Orientation.Cardinal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Module der Mod (AK-39, 2026-11-22): Schalter persistiert, alte Datei ohne Felder, Gedächtnis-Schalter wirkt. */
class ClientModulesTest {

    @TempDir
    Path dir;

    @Test
    void everyToggleIsSavedAndSurvivesARestart() {
        Path file = dir.resolve("baumeister-client.json");
        ClientSettings s = ClientSettings.load(file);
        for (ClientModules.Module m : ClientModules.ALL) {
            assertTrue(m.on().test(s), m.id() + ": Vorgabe an");
            assertFalse(ClientModules.toggle(s, m, file), m.id());
            assertFalse(m.on().test(ClientSettings.load(file)), m.id() + ": aus nach dem Neuladen");
            assertTrue(ClientModules.toggle(s, m, file), m.id());
            assertTrue(m.on().test(ClientSettings.load(file)), m.id() + ": wieder an nach dem Neuladen");
        }
    }

    @Test
    void togglesDoNotTouchEachOther() {
        Path file = dir.resolve("baumeister-client.json");
        ClientSettings s = ClientSettings.load(file);
        ClientModules.toggle(s, ClientModules.byId("statuszeile").orElseThrow(), file);
        ClientSettings back = ClientSettings.load(file);
        assertFalse(back.hudStatus);
        assertTrue(back.followUps && back.progressBar && back.reportResults);
    }

    @Test
    void oldFileWithoutModuleFieldsKeepsEverythingOn() throws IOException {
        Path file = dir.resolve("baumeister-client.json");
        Files.writeString(file, "{\"mode\":\"befehl\",\"reportResults\":false}", StandardCharsets.UTF_8);
        ClientSettings s = ClientSettings.load(file);
        assertTrue(s.followUps && s.hudStatus && s.progressBar, "neue Felder fehlen → wie vorher an");
        assertFalse(s.reportResults, "bestehender Schlüssel bleibt die Quelle");
        assertFalse(ClientModules.byId("ergebnis-melden").orElseThrow().on().test(s));
    }

    @Test
    void idsAreUniqueAndFollowTheContractPattern() {
        Set<String> seen = new HashSet<>();
        for (ClientModules.Module m : ClientModules.ALL) {
            assertTrue(ServerModules.ID.matcher(m.id()).matches(), m.id());
            assertTrue(seen.add(m.id()), m.id());
        }
        assertEquals(List.of("nachtrag", "statuszeile", "aktionsleiste", "ergebnis-melden"),
                ClientModules.ALL.stream().map(ClientModules.Module::id).toList());
    }

    @Test
    void languageFilesNameEveryModule() throws IOException {
        for (String lang : new String[] {"de_de", "en_us"}) {
            String text;
            try (var in = ClientModulesTest.class.getResourceAsStream("/assets/baumeister-client/lang/" + lang
                    + ".json")) {
                assertNotNull(in, lang);
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (ClientModules.Module m : ClientModules.ALL) {
                assertTrue(text.contains("\"" + m.nameKey() + "\""), lang + " " + m.nameKey());
                assertTrue(text.contains("\"" + m.descriptionKey() + "\""), lang + " " + m.descriptionKey());
            }
            for (String state : ServerModules.STATES) {
                assertTrue(text.contains("\"baumeister-client.state." + state + "\""), lang + " " + state);
            }
        }
    }

    @Test
    void memorySwitchOffMeansFreshPlan() {
        ClientMemory<String> memory = new ClientMemory<>();
        Object level = new Object();
        memory.remember(new ClientMemory.Entry<>(level, 0, 64, 0, Cardinal.NORTH, 0, 3, "{}", "bau ein Haus",
                memory.now(), "zellen"));
        ClientSettings s = new ClientSettings();
        BlockPos here = new BlockPos(2, 64, 2);
        assertNotNull(ClientBuilder.recall(s, memory, level, here, "mach das Dach rot"), "an: Nachtrag");
        s.followUps = false;
        assertNull(ClientBuilder.recall(s, memory, level, here, "mach das Dach rot"), "aus: frisch geplant");
        s.followUps = true;
        assertNotNull(ClientBuilder.recall(s, memory, level, here, "mach das Dach rot"),
                "wieder an: der Bau ist noch gemerkt");
    }
}
