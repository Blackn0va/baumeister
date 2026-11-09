package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Chunks, die ein Bau hält (Anlass: Brücke blieb ohne Spieler 15 min bei 0/444 stehen, Testserver 2026-10-29). */
class BuildJobChunksTest {

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static Set<Long> keys(long[] k) {
        Set<Long> s = new HashSet<>();
        Arrays.stream(k).forEach(s::add);
        return s;
    }

    @Test
    void holdsTheWholeSitePlusOneChunkMargin() {
        // Brücke bei x 3100..3125, z 2995..3005 (Chunks 193..195 / 187), Mitte 3100/3000.
        Set<Long> held = keys(BuildJob.chunkKeys(new int[] {193, 187, 195, 187}, 193, 187));
        assertEquals(5 * 3, held.size());
        assertTrue(held.contains(key(192, 186)));
        assertTrue(held.contains(key(196, 188)));
        assertTrue(held.contains(key(195, 187)));
    }

    @Test
    void negativeChunksSurviveTheKey() {
        long[] k = BuildJob.chunkKeys(new int[] {-188, -188, -187, -187}, -188, -188);
        Set<Long> held = keys(k);
        assertEquals(16, held.size());
        assertTrue(held.contains(key(-189, -189)));
        for (long v : k) {
            int cx = (int) (v >> 32);
            int cz = (int) v;
            assertTrue(cx >= -189 && cx <= -186 && cz >= -189 && cz <= -186, cx + "/" + cz);
        }
    }

    @Test
    void withoutActionsOnlyTheCentreIsHeld() {
        long[] k = BuildJob.chunkKeys(new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE,
            Integer.MIN_VALUE}, 10, 20);
        assertEquals(9, k.length);
    }
}
