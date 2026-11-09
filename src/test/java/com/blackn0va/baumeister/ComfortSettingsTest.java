package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

/** komfort: in der Config – Vorgaben im Code gleich der Vorlage, Unsinn begrenzt. */
class ComfortSettingsTest {

    @Test
    void missingSectionBehavesLikeTheTemplate() {
        // dev01 behält seine alte config.yml: ohne komfort: muss alles wie in der Vorlage laufen.
        ComfortSettings c = ComfortSettings.from(new MemoryConfiguration());
        assertEquals(ComfortSettings.defaults(), c);
        assertTrue(c.bossBar());
        assertEquals(10, c.queueMax());
        assertEquals(2, c.queuePerPlayer());
        assertEquals(120, c.previewSeconds());
        assertTrue(c.undoAcrossRestart());
    }

    @Test
    void valuesAreClamped() {
        MemoryConfiguration m = new MemoryConfiguration();
        m.set("komfort.bossbar", false);
        m.set("komfort.warteschlange-max", -5);
        m.set("komfort.warteschlange-je-spieler", 0);
        m.set("komfort.vorschau-sekunden", 1);
        m.set("komfort.rückgängig-max-tage", 100_000);
        m.set("komfort.rückgängig-über-neustart", false);
        ComfortSettings c = ComfortSettings.from(m);
        assertFalse(c.bossBar());
        assertEquals(0, c.queueMax());
        assertEquals(1, c.queuePerPlayer());
        assertEquals(10, c.previewSeconds());
        assertEquals(365, c.undoMaxDays());
        assertFalse(c.undoAcrossRestart());
    }

    /** Per Muster wie BaumeisterCommandTest – snakeyaml fehlt im Testpfad. */
    @Test
    void templateCarriesEveryKeyWithTheCodeDefault() throws IOException {
        String yml = Files.readString(Path.of("src/main/resources/config.yml"), StandardCharsets.UTF_8);
        int start = yml.indexOf("\nkomfort:\n");
        assertTrue(start >= 0, "Abschnitt komfort: fehlt in config.yml");
        String section = yml.substring(start);
        ComfortSettings d = ComfortSettings.defaults();
        assertEquals(String.valueOf(d.bossBar()), value(section, "bossbar"));
        assertEquals(String.valueOf(d.bossBarRadius()), value(section, "bossbar-umkreis"));
        assertEquals(String.valueOf(d.queueMax()), value(section, "warteschlange-max"));
        assertEquals(String.valueOf(d.queuePerPlayer()), value(section, "warteschlange-je-spieler"));
        assertEquals(String.valueOf(d.previewSeconds()), value(section, "vorschau-sekunden"));
        assertEquals(String.valueOf(d.undoAcrossRestart()), value(section, "rückgängig-über-neustart"));
        assertEquals(String.valueOf(d.undoMaxDays()), value(section, "rückgängig-max-tage"));
        assertEquals(String.valueOf(d.undoMaxMb()), value(section, "rückgängig-max-mb"));
    }

    private static String value(String section, String key) {
        Matcher m = Pattern.compile("(?m)^  " + Pattern.quote(key) + ": (\\S+)\\s*$").matcher(section);
        assertTrue(m.find(), "Schlüssel fehlt: " + key);
        return m.group(1);
    }
}
