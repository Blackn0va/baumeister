package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Speicherwächter und Chunk-Grenze für hohe Welten (Anlass: OutOfMemoryError beim Weltbau, 2026-10-28). */
class HeapGuardTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Test
    void budgetGrowsWithTheHeap() {
        assertEquals(1433, HeapGuard.budget(3 * GB));
        assertEquals(3822, HeapGuard.budget(8 * GB));
    }

    @Test
    void newTileWaitsWhileTheLastOneIsStillLoaded() {
        // Eine Weltbau-Kachel hält bis zu 300 Chunks (MAX_HELD_TALL); zwei Spieler mit Sichtweite 10 je 441.
        assertFalse(HeapGuard.tight(300 + 2 * 441, 3 * GB));
        // Kurz nach einer Kachel mit 900 gehaltenen Chunks und Ring, alles noch geladen.
        assertTrue(HeapGuard.tight(1_800, 3 * GB));
        assertFalse(HeapGuard.tight(1_800, 8 * GB));
    }

    @Test
    void tallWorldsHoldAThirdOfTheChunks() {
        assertEquals(900, CityJob.maxHeld(384));
        assertEquals(300, CityJob.maxHeld(4064));
    }
}
