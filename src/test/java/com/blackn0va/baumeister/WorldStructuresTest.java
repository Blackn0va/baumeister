package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Bauwerksgrenzen für den Tunnel quer über die Karte (Wunsch 2026-11-16): Grenzen einschließlich – so reichen Spigot
 * 1.21.11 und Paper 26.2 die Vanilla-BoundingBox durch (javap, 2026-11-16) –, je Chunk nur einmal gelesen.
 */
class WorldStructuresTest {

    @Test
    void theLastRowOfAPieceStillCounts() {
        WorldStructures.Box corridor = new WorldStructures.Box("mineshaft", 10, -30, 20, 12, -28, 40);
        WorldStructures s = new WorldStructures((cx, cz) -> corridor.touchesChunk(cx, cz) ? List.of(corridor)
                : List.of());
        assertEquals("mineshaft", s.diggableAt(10, -30, 20), "Ecke unten");
        assertEquals("mineshaft", s.diggableAt(12, -28, 40), "Ecke oben – Maximum zählt mit");
        assertNull(s.diggableAt(13, -28, 40), "eins daneben");
        assertNull(s.diggableAt(12, -27, 40), "eins darüber");
        assertNull(s.diggableAt(12, -28, 41), "eins dahinter");
    }

    @Test
    void eachChunkIsReadOnceAndOnlyTouchingPiecesCount() {
        List<String> reads = new ArrayList<>();
        WorldStructures.Box across = new WorldStructures.Box("mineshaft", 14, 0, 0, 17, 2, 2);
        WorldStructures s = new WorldStructures((cx, cz) -> {
            reads.add(cx + "," + cz);
            return across.touchesChunk(cx, cz) ? List.of(across) : List.of();
        });
        assertEquals("mineshaft", s.diggableAt(15, 1, 1));
        assertEquals("mineshaft", s.diggableAt(16, 1, 1), "über die Chunkgrenze");
        assertEquals("mineshaft", s.diggableAt(14, 0, 0));
        assertNull(s.diggableAt(-1, 0, 0));
        assertEquals(List.of("0,0", "1,0", "-1,0"), reads, "je Chunk einmal");
        assertTrue(across.touchesChunk(0, 0));
        assertTrue(across.touchesChunk(1, 0));
        assertFalse(across.touchesChunk(2, 0));
        assertFalse(across.touchesChunk(0, 1));
    }
}
