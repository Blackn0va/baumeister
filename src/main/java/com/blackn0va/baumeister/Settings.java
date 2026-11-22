package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.PlanReview;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

import org.bukkit.configuration.file.FileConfiguration;

/** Werte aus config.yml, geprüft und auf sinnvolle Bereiche begrenzt. */
record Settings(
        String npcName,
        URI llmUrl,
        Duration llmTimeout,
        int maxTokens,
        double temperature,
        int repairAttempts,
        int ticksPerBlock,
        int maxBlocks,
        int maxExtent,
        int distanceAhead,
        String fallbackBlock,
        boolean checkPermissions,
        int undoDepth,
        boolean savePlans,
        int maxOverbuild,
        int secondHelperFrom,
        int thirdHelperFrom,
        int targetSeconds,
        PlanReview.LintMode lintMode) {

    static Settings from(FileConfiguration c) {
        String name = c.getString("npc.name", "Baumeister").trim();
        if (!name.matches("[A-Za-z0-9_ÄÖÜäöüß]{2,24}")) {
            throw new IllegalArgumentException("npc.name muss 2–24 Buchstaben/Ziffern haben: " + name);
        }
        URI url = URI.create(c.getString("llm.url", "http://127.0.0.1:18080/v1/chat/completions"));
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("llm.url muss http(s) sein: " + url);
        }
        return new Settings(
                name,
                url,
                Duration.ofSeconds(Math.clamp(c.getLong("llm.timeout-sekunden", 900), 10, 1800)),
                Math.clamp(c.getInt("llm.max-tokens", 3000), 256, 8192),
                Math.clamp(c.getDouble("llm.temperatur", 0.4), 0.0, 1.5),
                Math.clamp(c.getInt("llm.reparatur-versuche", 1), 0, 3),
                Math.clamp(c.getInt("bau.ticks-pro-block", 3), 1, 40),
                // Vorher höchstens 50 000 Blöcke und 128 je Richtung: Ein 40-stöckiges Hochhaus (16 810 Blöcke in
                // einem Schritt, 120+ Blöcke hoch) wurde auf dev01 verworfen (2026-10-28). Große Pläne baut BuildJob
                // jetzt im Schnellbau, die Grenze schützt nur noch vor Unsinn.
                Math.clamp(c.getInt("bau.max-blöcke", 150_000), 10, 1_000_000),
                Math.clamp(c.getInt("bau.max-ausdehnung", 192), 4, 380),
                Math.clamp(c.getInt("bau.abstand-vor-spieler", 3), 1, 16),
                c.getString("bau.ersatzblock", "stone").toLowerCase(Locale.ROOT),
                c.getBoolean("bau.rechte-prüfen", true),
                Math.clamp(c.getInt("bau.undo-tiefe", 50), 1, 50),
                c.getBoolean("pläne-speichern", true),
                Math.clamp(c.getInt("bau.max-überbauen", 12), 0, 1000),
                Math.max(1, c.getInt("helfer.zweiter-ab-blöcken", 300)),
                Math.max(1, c.getInt("helfer.dritter-ab-blöcken", 700)),
                Math.clamp(c.getInt("bau.zieldauer-sekunden", 120), 10, 3600),
                // Einmal beim Laden statt je LLM-Antwort: Ein unbekannter Wert warnt so einmal je (Neu-)Laden.
                PlanReview.LintMode.parse(c.getString("llm.plan-prüfer", "streng")));
    }

    /**
     * Dieselben Werte mit anderer HTTP-Frist. Für den Client der Bewohner (TownLife.clientSettings): Er erbte vorher
     * llm.timeout-sekunden (Vorgabe 900 s) und gab nur über ein abhängiges Future nach 20 s auf – die Anfrage rechnete
     * am Host weiter (Paper-Probe 2026-11-21: Plugin 20,04 s, llama bis 21,07 s), und die nächste stapelte sich davor.
     */
    Settings withLlmTimeout(Duration timeout) {
        return new Settings(npcName, llmUrl, timeout, maxTokens, temperature, repairAttempts, ticksPerBlock, maxBlocks,
                maxExtent, distanceAhead, fallbackBlock, checkPermissions, undoDepth, savePlans, maxOverbuild,
                secondHelperFrom, thirdHelperFrom, targetSeconds, lintMode);
    }
}
