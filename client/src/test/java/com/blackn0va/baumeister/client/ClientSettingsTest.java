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

    /** Einstellungen: unsinnige KI-Werte fallen auf die Vorgabe zurück (AK-6, 2026-11-21). */
    @Test
    void nonsenseAiValuesFallBackToTheDefault() {
        ClientSettings d = new ClientSettings();
        assertEquals(600, d.llmTimeoutSeconds, "Vorgabe wie vorher fest in ClientPlanner.ask (10 min)");
        ClientSettings s = new ClientSettings();
        s.temperature = -0.5;
        s.maxTokens = 0;
        s.repairAttempts = -1;
        s.maxOverbuild = -3;
        s.llmTimeoutSeconds = 0;
        assertEquals(List.of("temperature", "maxTokens", "repairAttempts", "maxOverbuild", "llmTimeoutSeconds"),
                s.sanitize());
        assertEquals(d.temperature, s.temperature);
        assertEquals(d.maxTokens, s.maxTokens);
        assertEquals(d.repairAttempts, s.repairAttempts);
        assertEquals(d.maxOverbuild, s.maxOverbuild);
        assertEquals(d.llmTimeoutSeconds, s.llmTimeoutSeconds);
        ClientSettings hot = new ClientSettings();
        hot.temperature = Double.NaN;
        assertEquals(List.of("temperature"), hot.sanitize(), "NaN ist kein Wert");
        ClientSettings far = new ClientSettings();
        far.temperature = 2.5;
        assertEquals(List.of("temperature"), far.sanitize(), "Bereich 0..2 wie in der OpenAI-Schnittstelle");
    }

    @Test
    void sensibleAiValuesStay() {
        ClientSettings s = new ClientSettings();
        s.temperature = 0;
        s.maxTokens = 1;
        s.repairAttempts = 0;
        s.maxOverbuild = 0;
        s.llmTimeoutSeconds = 30;
        assertTrue(s.sanitize().isEmpty(), "0 Nachbesserungen und 0 Überbau sind gewollte Einstellungen");
    }
}
