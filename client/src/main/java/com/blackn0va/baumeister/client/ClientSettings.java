package com.blackn0va.baumeister.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Einstellungen des Baumeister-Clients in config/baumeister-client.json. Vorgaben wie im Plugin (config.yml): Der Host
 * läuft auf demselben PC wie das Spiel, also direkt 127.0.0.1:8080 – kein Tunnel.
 *
 * Threads: Laden und Speichern auf dem Client-Thread. Fehler: Eine kaputte Datei wird gemeldet und durch die Vorgaben
 * ersetzt (die alte bleibt als .kaputt liegen) – das Spiel startet immer.
 */
final class ClientSettings {

    private static final Logger LOG = Logger.getLogger("Baumeister-Client");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Endpunkt des Baumeister-Hosts (llama-server-Format). */
    String llmUrl = "http://127.0.0.1:8080/v1/chat/completions";
    /** „spieler“ (Rechtsklick wie du, Kreativmodus) oder „befehl“ (/setblock und /fill, braucht OP/Cheats). */
    String mode = "spieler";
    /** Spieler-Bauweise: Blöcke je Sekunde (Reichweite und Laufen bremsen zusätzlich). */
    int blocksPerSecond = 20;
    /** Befehls-Bauweise: Befehle je Sekunde (ein /fill setzt eine ganze Reihe). */
    int commandsPerSecond = 20;
    double temperature = 0.4;
    int maxTokens = 3000;
    int repairAttempts = 1;
    int maxExtent = 192;
    int maxBlocks = 150_000;
    int maxOverbuild = 12;
    /** Wartezeit auf eine LLM-Antwort in Sekunden; vorher fest 10 min in ClientPlanner.ask (AK-6). */
    int llmTimeoutSeconds = 600;
    /** Abstand des Bauplatzes vor den Füßen (wie bau.abstand im Plugin). */
    int distanceAhead = 3;
    /** Ergebnis an die App melden (/baumeister/v1/ergebnis, wie gui.ergebnis-melden im Plugin). */
    boolean reportResults = true;

    boolean commandMode() {
        return "befehl".equals(mode);
    }

    static ClientSettings load(Path file) {
        if (Files.exists(file)) {
            try {
                ClientSettings s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ClientSettings.class);
                if (s != null) {
                    if (!s.sanitize().isEmpty()) {
                        s.save(file);  // bereinigt zurück; was vorher drinstand, steht in den Logzeilen
                    }
                    return s;
                }
            } catch (IOException | JsonParseException e) {
                LOG.log(Level.WARNING, "einstellungen | unlesbar | datei=" + file + " – Vorgaben gelten", e);
                try {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".kaputt"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moveFailed) {
                    LOG.log(Level.WARNING, "einstellungen | sichern_fehlgeschlagen", moveFailed);
                }
            }
        }
        ClientSettings s = new ClientSettings();
        s.save(file);
        return s;
    }

    /**
     * Unbrauchbare Werte aus der Datei auf brauchbare setzen; liefert die korrigierten Felder. Vorher übernahm load()
     * alles ungeprüft: Tempo 0 ließ den Bau still hängen (Budget erreicht nie einen Block, PlayerPlacer/CommandPlacer),
     * „Befehl“ fiel still auf die Spieler-Bauweise zurück, ein negativer Abstand baute auf den Spieler, eine fehlende
     * llmUrl warf in URI.create auf dem Client-Thread. Grenzen nur, wo der Code sie schon hat (/kbau tempo 1..1000)
     * oder der Wert sonst nicht funktioniert – keine erfundenen Obergrenzen.
     */
    List<String> sanitize() {
        ClientSettings d = new ClientSettings();
        List<String> fixed = new ArrayList<>();
        int bps = Math.max(1, Math.min(1000, blocksPerSecond));
        if (bps != blocksPerSecond) {
            fixed.add(corrected("blocksPerSecond", blocksPerSecond, bps));
            blocksPerSecond = bps;
        }
        int cps = Math.max(1, Math.min(1000, commandsPerSecond));
        if (cps != commandsPerSecond) {
            fixed.add(corrected("commandsPerSecond", commandsPerSecond, cps));
            commandsPerSecond = cps;
        }
        String m = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (!m.equals("spieler") && !m.equals("befehl")) {
            m = d.mode;
        }
        if (!m.equals(mode)) {
            fixed.add(corrected("mode", mode, m));
            mode = m;
        }
        if (distanceAhead < 0) {
            fixed.add(corrected("distanceAhead", distanceAhead, 0));
            distanceAhead = 0;
        }
        if (maxExtent <= 0) {
            fixed.add(corrected("maxExtent", maxExtent, d.maxExtent));
            maxExtent = d.maxExtent;
        }
        if (maxBlocks <= 0) {
            fixed.add(corrected("maxBlocks", maxBlocks, d.maxBlocks));
            maxBlocks = d.maxBlocks;
        }
        // KI-Werte (AK-6, 2026-11-21): Vorher ungeprüft – temperature NaN oder negativ ging so an den Host, maxTokens 0
        // ließ jede Antwort leer zurückkommen, ein negatives repairAttempts schaltete die Nachbesserung still ab.
        if (!(temperature >= 0 && temperature <= 2)) {
            fixed.add(corrected("temperature", temperature, d.temperature));
            temperature = d.temperature;
        }
        if (maxTokens <= 0) {
            fixed.add(corrected("maxTokens", maxTokens, d.maxTokens));
            maxTokens = d.maxTokens;
        }
        if (repairAttempts < 0) {
            fixed.add(corrected("repairAttempts", repairAttempts, d.repairAttempts));
            repairAttempts = d.repairAttempts;
        }
        if (maxOverbuild < 0) {
            fixed.add(corrected("maxOverbuild", maxOverbuild, d.maxOverbuild));
            maxOverbuild = d.maxOverbuild;
        }
        if (llmTimeoutSeconds <= 0) {
            fixed.add(corrected("llmTimeoutSeconds", llmTimeoutSeconds, d.llmTimeoutSeconds));
            llmTimeoutSeconds = d.llmTimeoutSeconds;
        }
        if (!validLlmUrl(llmUrl)) {
            fixed.add(corrected("llmUrl", llmUrl, d.llmUrl));
            llmUrl = d.llmUrl;
        } else if (!llmUrl.equals(llmUrl.trim())) {
            fixed.add(corrected("llmUrl", llmUrl, llmUrl.trim()));
            llmUrl = llmUrl.trim();
        }
        return fixed;
    }

    private static String corrected(String field, Object was, Object now) {
        LOG.warning(() -> "einstellungen | korrigiert | feld=" + field + " alt=" + was + " neu=" + now);
        return field;
    }

    /** http(s) mit Host und parsebar – wie ClientPlanner.ask und ResultSender sie mit URI.create brauchen. */
    static boolean validLlmUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            java.net.URI u = new java.net.URI(url.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && u.getHost() != null;
        } catch (java.net.URISyntaxException e) {
            return false;  // Prüfergebnis, kein Fehlerfall: der Aufrufer meldet bzw. setzt die Vorgabe
        }
    }

    void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "einstellungen | speichern_fehlgeschlagen | datei=" + file, e);
        }
    }
}
