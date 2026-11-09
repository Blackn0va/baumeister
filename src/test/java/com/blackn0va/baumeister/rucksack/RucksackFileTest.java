package com.blackn0va.baumeister.rucksack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rucksack-Datei: kaputt heißt zu (fail-closed), Schreiben atomar mit Vorversion. */
class RucksackFileTest {

    @TempDir
    Path dir;

    @Test
    void missingFileIsANewEmptyRucksack() {
        assertEquals(RucksackFile.Status.MISSING, RucksackFile.load(dir.resolve("x.yml")).status());
    }

    @Test
    void unreadableFileIsBrokenNotEmpty() throws Exception {
        Path f = dir.resolve("x.yml");
        Files.writeString(f, "version: 1\nseiten: [kaputt\n");
        RucksackFile.Loaded l = RucksackFile.load(f);
        assertEquals(RucksackFile.Status.BROKEN, l.status());
        assertNotNull(l.reason());
    }

    @Test
    void unknownVersionIsBroken() throws Exception {
        Path f = dir.resolve("x.yml");
        Files.writeString(f, "version: 7\n");
        assertEquals(RucksackFile.Status.BROKEN, RucksackFile.load(f).status());
    }

    @Test
    void entryThatIsNoItemBlocksOpening() throws Exception {
        // Unbekannte Sorte nach einem Rückschritt der Serverfassung: Der Eintrag ist dann kein Gegenstand. Öffnen und
        // Speichern würde ihn löschen – deshalb zu.
        YamlConfiguration yc = new YamlConfiguration();
        yc.loadFromString("version: 1\nseiten:\n  0:\n    3: Text\n");
        assertEquals("kein_gegenstand=0/3", RucksackFile.check(yc, Integer.class, 4500));
        yc.loadFromString("version: 1\nseiten:\n  0:\n    3: 5\n");
        assertNull(RucksackFile.check(yc, Integer.class, 4500));
    }

    @Test
    void fewerPagesThanUsedBlocksOpening() throws Exception {
        YamlConfiguration yc = new YamlConfiguration();
        yc.loadFromString("version: 1\nseiten:\n  20:\n    0: 5\n");
        assertTrue(RucksackFile.check(yc, Integer.class, 10 * RucksackLogic.PER_PAGE).startsWith("platz_außerhalb"));
        assertNull(RucksackFile.check(yc, Integer.class, 100 * RucksackLogic.PER_PAGE));
    }

    @Test
    void writeKeepsThePreviousVersion() throws Exception {
        Path f = dir.resolve("sub").resolve("x.yml");
        RucksackFile.write(f, "version: 1\n");
        RucksackFile.write(f, "version: 1\nseiten: {}\n");
        assertEquals("version: 1\nseiten: {}\n", Files.readString(f));
        assertEquals("version: 1\n", Files.readString(f.resolveSibling("x.yml.vorher")));
        assertTrue(Files.notExists(f.resolveSibling("x.yml.neu")));
    }

    @Test
    void templateHasSafeDefaults() throws Exception {
        try (var in = new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            YamlConfiguration c = YamlConfiguration.loadConfiguration(in);
            assertEquals(100, c.getInt("rucksack.seiten"));
            assertTrue(c.getBoolean("rucksack.an"));
            assertTrue(!c.getStringList("rucksack.spielmodi").contains("CREATIVE"), "Kreativ darf nicht öffnen");
        }
        try (var in = new InputStreamReader(getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8)) {
            YamlConfiguration p = YamlConfiguration.loadConfiguration(in);
            assertEquals("baumeister.rucksack", p.getString("commands.rucksack.permission"));
            assertTrue(p.getStringList("commands.rucksack.aliases").contains("bp"));
        }
    }
}
