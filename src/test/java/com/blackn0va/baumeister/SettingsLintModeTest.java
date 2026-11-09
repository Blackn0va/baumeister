package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.blackn0va.baumeister.plan.PlanReview.LintMode;

import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;

/**
 * llm.plan-prüfer wird wie alle llm.*-Schlüssel einmal in Settings gelesen (vorher je LLM-Antwort aus der Config,
 * mit Warnung bei jeder Antwort). Die Config auf dev01 wird nicht ersetzt – ohne Schlüssel gilt die Vorgabe streng.
 */
class SettingsLintModeTest {

    /** FileConfiguration ohne YAML: snakeyaml liegt nicht auf dem Test-Klassenpfad, gelesen wird nur aus dem Speicher. */
    private static FileConfiguration config() {
        return new FileConfiguration() {
            @Override
            public String saveToString() {
                return "";
            }

            @Override
            public void loadFromString(String contents) {
                throw new UnsupportedOperationException("nur Speicher");
            }
        };
    }

    @Test
    void missingKeyIsStrict() {
        assertEquals(LintMode.STRICT, Settings.from(config()).lintMode());
    }

    @Test
    void configuredValueIsUsed() {
        FileConfiguration c = config();
        c.set("llm.plan-prüfer", "aus");
        assertEquals(LintMode.OFF, Settings.from(c).lintMode());
        c.set("llm.plan-prüfer", "reparieren");
        assertEquals(LintMode.REPAIR, Settings.from(c).lintMode());
    }
}
