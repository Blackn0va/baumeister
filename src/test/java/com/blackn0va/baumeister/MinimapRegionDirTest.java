package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regionsordner je Welt für den Plattenscan der Karte – Ordnerbild von 1.21.11 und von 26.x (WorldFolderMigration,
 * gemessen 2026-11-07 im Probelauf der dev01-Sicherung auf Paper 26.2).
 */
class MinimapRegionDirTest {

    private static Path region(Path world, String... parts) throws Exception {
        Path p = world;
        for (String part : parts) {
            p = p.resolve(part);
        }
        Files.createDirectories(p.resolve("region"));
        return p.resolve("region");
    }

    @Test
    void oldLayoutUsesTheRegionFolderOfTheWorld(@TempDir Path world) throws Exception {
        Path plain = region(world);
        assertEquals(plain, Minimap.regionDir(world, "minecraft", "overworld"));
    }

    @Test
    void customDimensionOfOldLayoutIsFoundByItsKey(@TempDir Path world) throws Exception {
        Path erde = region(world, "dimensions", "baumeister", "erde");
        assertEquals(erde, Minimap.regionDir(world, "baumeister", "erde"));
    }

    @Test
    void newLayoutPicksTheWorldsOwnDimensionAmongSeveral(@TempDir Path world) throws Exception {
        Path overworld = region(world, "dimensions", "minecraft", "overworld");
        Path end = region(world, "dimensions", "minecraft", "the_end");
        assertEquals(overworld, Minimap.regionDir(world, "minecraft", "overworld"));
        // Vorher: erster Treffer – das Ende hätte die Karte der Oberwelt bekommen oder umgekehrt.
        assertEquals(end, Minimap.regionDir(world, "minecraft", "the_end"));
    }

    @Test
    void singleUnknownDimensionIsTakenButSeveralAreNotGuessed(@TempDir Path world) throws Exception {
        // Multiverse-Welt „plotwelt“: Schlüssel minecraft:plotwelt, Ordner nach dem Umzug evtl. …/overworld.
        Path only = region(world, "dimensions", "minecraft", "overworld");
        assertEquals(only, Minimap.regionDir(world, "minecraft", "plotwelt"));
        region(world, "dimensions", "minecraft", "the_nether");
        assertNull(Minimap.regionDir(world, "minecraft", "plotwelt"), "mehrdeutig: lieber keine Karte als eine falsche");
    }
}
