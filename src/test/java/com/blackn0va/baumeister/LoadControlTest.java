package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** Lastregelung des Stadtbaus gegen die auf dev01 gemessenen Ticklängen (2026-10-28). */
class LoadControlTest {

    private static final long MS = 1_000_000L;

    /**
     * dev01 während des Hepberg-Baus: 1 032 von ~12 000 Lücken über 100 ms, längste 289 ms, keine über 1 s – also
     * ~8,6 % der Ticks 100–290 ms, der Rest normal (~50–90 ms auf dem Celeron).
     */
    private static long dev01Gap(Random r) {
        return r.nextInt(1000) < 86 ? (100 + r.nextInt(190)) * MS : (50 + r.nextInt(40)) * MS;
    }

    @Test
    void ordinaryCeleronTicksKeepTheChunkRateUp() {
        LoadControl c = new LoadControl(150 * MS, 8);
        Random r = new Random(28);
        long chunks = 0;
        for (int t = 0; t < 12_000; t++) {
            int a = c.onTick(dev01Gap(r));
            chunks += Math.max(0, a);
        }
        // Mindestens ein neuer Chunk je zwei Ticks im Schnitt – sonst wächst die Stadt nicht über den Spieler hinaus.
        assertTrue(chunks > 6_000, "Chunks in 10 min: " + chunks + ", Rate am Ende " + c.rate());
    }

    @Test
    void realOverloadStillBacksOffToTheFloor() {
        LoadControl c = new LoadControl(150 * MS, 8);
        for (int t = 0; t < 50; t++) {
            assertEquals(-1, c.onTick(400 * MS));
        }
        assertEquals(LoadControl.FLOOR, c.rate(), 1e-9);
        assertEquals(50, c.recoveries());
    }
}
