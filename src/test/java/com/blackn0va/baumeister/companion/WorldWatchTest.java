package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/** Nachtwache, Kisten-Verlauf und Reparieren: die Teile ohne Server. */
class WorldWatchTest {

    @Test
    void nightIsWhenMonstersSpawn() {
        assertFalse(WorldWatch.night(6000));
        assertTrue(WorldWatch.night(13000));
        assertTrue(WorldWatch.night(18000 + 24000 * 5));
        assertFalse(WorldWatch.night(23000));
    }

    @Test
    void monstersAreCountedInWords() {
        Map<String, Integer> m = new TreeMap<>(Map.of("zombie", 3, "creeper", 1));
        assertEquals("3 Zombies und 1 Creeper", WorldWatch.monsters(m));
        assertEquals("1 Skelett", WorldWatch.monsters(Map.of("skeleton", 1)));
        // Gleichstand: alphabetisch nach Sorte (slime vor zombie).
        assertEquals("4 Spinnen, 2 Hexen und 1 Schleim und weitere",
                WorldWatch.monsters(Map.of("spider", 4, "witch", 2, "zombie", 1, "slime", 1)));
    }

    @Test
    void chestHistoryNewestFirstWithVerbs() {
        long now = 10L * 24 * 3600_000;
        String s = WorldWatch.chestHistory(List.of(
                new WorldWatch.ChestEntry("Steve", false, "diamond", 5, now - 2 * 3600_000),
                new WorldWatch.ChestEntry("Alex", true, "oak_log", 32, now - 5 * 60_000),
                new WorldWatch.ChestEntry("Steve", false, "iron_ingot", 1, now - 3L * 24 * 3600_000)), now, 2);
        assertEquals("Alex hat vor 5 Min. 32 oak log hineingelegt; Steve hat vor 2 Std. 5 diamond genommen. "
                + "(und 1 ältere Einträge)", s);
        assertEquals("An dieser Kiste war laut Protokoll niemand.", WorldWatch.chestHistory(List.of(), now, 5));
    }

    @Test
    void repairNeverTouchesWhatPlayersDid() {
        // Nur Verursacher mit „#“ – CoreProtect führt Spieler ohne. Ein Spielername hier würde dessen Abbau zurücksetzen.
        assertTrue(WorldWatch.DAMAGE_CAUSES.stream().allMatch(c -> c.startsWith("#")));
        assertTrue(WorldWatch.DAMAGE_CAUSES.containsAll(List.of("#creeper", "#ghast", "#fire", "#lightning")),
                "gemessen in co_user auf dev01");
    }
}
