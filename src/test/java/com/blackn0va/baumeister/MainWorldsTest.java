package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

/**
 * „hauptwelt“ in den Welt-Listen (2026-11-08): Ein frischer Paper-Server mit level-name=meinewelt warnte dreimal
 * „abenteuer | welt_fehlt“, und keine Tagesaufgabe zählte – die Listen standen fest auf world (dev01).
 */
class MainWorldsTest {

    @Test
    void tokenBecomesTheMainWorldWithNetherAndEnd() {
        assertEquals(Set.of("meinewelt", "meinewelt_nether", "meinewelt_the_end"),
                MainWorlds.expand(List.of("hauptwelt"), "MeineWelt"));
        assertEquals(Set.of("world", "world_nether", "world_the_end", "stadtwelt"),
                MainWorlds.expand(List.of(" HauptWelt ", "stadtwelt", ""), "world"));
    }

    @Test
    void fixedNamesStayAsTheyAre() {
        // dev01 hat die Namen ausdrücklich in seiner config.yml – das bleibt gleich.
        assertEquals(Set.of("world", "world_nether", "world_the_end"),
                MainWorlds.expand(List.of("world", "world_nether", "world_the_end"), "andere"));
        assertEquals(Set.of(), MainWorlds.expand(List.of(), "world"));
    }

    @Test
    void bundledDefaultsUseTheTokenAndTheLabel() throws Exception {
        assertEquals(List.of("hauptwelt"), CompanionSettings.from(ConfigMigrationTest.bundled()).adventureWorlds());
        assertEquals(List.of("hauptwelt"), QuestService.Settings.from(ConfigMigrationTest.bundled()).worlds());
        assertEquals("Hauptwelt", MainWorlds.label(ConfigMigrationTest.bundled()));
        MemoryConfiguration c = new MemoryConfiguration();
        c.set("spielwelt-name", "Farmwelt");
        assertEquals("Farmwelt", MainWorlds.label(c));
        c.set("spielwelt-name", "  ");
        assertEquals("Hauptwelt", MainWorlds.label(c));
        assertEquals("Hauptwelt", MainWorlds.label(null));
    }
}
