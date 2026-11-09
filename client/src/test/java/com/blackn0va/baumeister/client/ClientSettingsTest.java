package com.blackn0va.baumeister.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Einstellungsdatei: unbrauchbare Werte werden beim Laden korrigiert (2026-11-01). */
class ClientSettingsTest {

    @TempDir
    Path dir;

    @Test
    void brokenValuesAreCorrectedAndNamed() throws IOException {
        Path file = dir.resolve("baumeister-client.json");
        Files.writeString(file, """
                {"blocksPerSecond": 0, "commandsPerSecond": -5, "mode": " Befehl ", "distanceAhead": -10,
                 "maxExtent": 0, "maxBlocks": -1, "llmUrl": "http://a b"}
                """, StandardCharsets.UTF_8);
        ClientSettings s = ClientSettings.load(file);
        ClientSettings d = new ClientSettings();
        assertEquals(1, s.blocksPerSecond);
        assertEquals(1, s.commandsPerSecond);
        assertEquals("befehl", s.mode);
        assertTrue(s.commandMode());
        assertEquals(0, s.distanceAhead);
        assertEquals(d.maxExtent, s.maxExtent);
        assertEquals(d.maxBlocks, s.maxBlocks);
        assertEquals(d.llmUrl, s.llmUrl);
        // Bereinigt zurückgeschrieben: der nächste Start sieht dieselben Werte.
        assertEquals(1, ClientSettings.load(file).blocksPerSecond);
    }

    @Test
    void sanitizeNamesEveryCorrectedField() {
        ClientSettings s = new ClientSettings();
        s.blocksPerSecond = 5000;
        s.commandsPerSecond = 0;
        s.mode = "befehle";
        s.distanceAhead = -1;
        s.llmUrl = null;
        assertEquals(List.of("blocksPerSecond", "commandsPerSecond", "mode", "distanceAhead", "llmUrl"), s.sanitize());
        assertEquals(1000, s.blocksPerSecond);
        assertEquals("spieler", s.mode, "Tippfehler → Vorgabe");
    }

    @Test
    void validFileStaysUnchanged() throws IOException {
        Path file = dir.resolve("baumeister-client.json");
        ClientSettings first = ClientSettings.load(file);  // legt die Vorgaben an
        first.mode = "befehl";
        first.blocksPerSecond = 40;
        first.save(file);
        String before = Files.readString(file, StandardCharsets.UTF_8);
        ClientSettings s = ClientSettings.load(file);
        assertTrue(s.sanitize().isEmpty());
        assertEquals(before, Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(40, s.blocksPerSecond);
    }

    @Test
    void brokenJsonIsKeptAsKaputtAndDefaultsApply() throws IOException {
        Path file = dir.resolve("baumeister-client.json");
        Files.writeString(file, "{ kaputt", StandardCharsets.UTF_8);
        ClientSettings s = ClientSettings.load(file);
        assertTrue(Files.exists(dir.resolve("baumeister-client.json.kaputt")));
        assertEquals(new ClientSettings().blocksPerSecond, s.blocksPerSecond);
    }

    @Test
    void llmUrlCheck() {
        assertTrue(ClientSettings.validLlmUrl("http://127.0.0.1:8080/v1/chat/completions"));
        assertTrue(ClientSettings.validLlmUrl("https://host.example/v1"));
        assertFalse(ClientSettings.validLlmUrl("ftp://x"));
        assertFalse(ClientSettings.validLlmUrl("http://a b"));
        assertFalse(ClientSettings.validLlmUrl(""));
        assertFalse(ClientSettings.validLlmUrl(null));
        assertFalse(ClientSettings.validLlmUrl("http:///pfad"), "ohne Host");
    }
}
