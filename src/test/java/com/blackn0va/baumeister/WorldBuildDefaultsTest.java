package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Wunsch 2026-11-04. Die Automatik baut ohne Auftrag Kacheln rund um die
 * Spieler – Standard ist aus, an nur ausdrücklich (/bm weltbau auto an oder weltbau.auto: true). Wer die Vorgabe
 * umstellt, bekommt diesen Test rot.
 */
class WorldBuildDefaultsTest {

    @Test
    void automaticWorldBuildIsOffByDefault() throws Exception {
        try (var in = new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            YamlConfiguration c = YamlConfiguration.loadConfiguration(in);
            assertTrue(c.contains("weltbau.auto"), "Schlüssel muss in der Vorlage stehen");
            assertFalse(c.getBoolean("weltbau.auto", true), "Vorlage: weltbau.auto muss false sein");
        }
    }
}
