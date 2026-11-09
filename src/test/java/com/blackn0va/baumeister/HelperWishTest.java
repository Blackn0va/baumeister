package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Helfer für normale Bauten (Nutzerwunsch 2026-10-30): Auftrag → stehender Wunsch → Größenregel. */
class HelperWishTest {

    @Test
    void promptNamesTheHelpers() {
        assertEquals(OptionalInt.of(6), HelperWish.fromPrompt("bau mir eine Burg mit 6 Helfern"));
        assertEquals(OptionalInt.of(3), HelperWish.fromPrompt("Bau einen Turm, mit drei Helfern"));
        assertEquals(OptionalInt.of(1), HelperWish.fromPrompt("bau ein Haus mit einem Helfer"));
        assertEquals(OptionalInt.of(5), HelperWish.fromPrompt("bau eine Brücke mit fünf Helferinnen"));
        assertEquals(OptionalInt.of(0), HelperWish.fromPrompt("bau eine Hütte ohne Helfer"));
        // Über 16 wird gekappt wie /bm helfer im Stadtbau.
        assertEquals(OptionalInt.of(HelperWish.MAX), HelperWish.fromPrompt("bau den Kölner Dom mit 99 Helfern"));
    }

    @Test
    void helperWordsInsideTheBuildAreNoWish() {
        assertEquals(OptionalInt.empty(), HelperWish.fromPrompt("bau ein Haus mit einem Helferzimmer"));
        assertEquals(OptionalInt.empty(), HelperWish.fromPrompt("bau ein kleines Haus aus Fichtenholz"));
        assertEquals(OptionalInt.empty(), HelperWish.fromPrompt(null));
    }

    @Test
    void theLlmGetsTheOrderWithoutTheHelpers() {
        assertEquals("bau mir eine Burg", HelperWish.strip("bau mir eine Burg mit 6 Helfern"));
        assertEquals("Bau einen Turm aus Stein", HelperWish.strip("Bau einen Turm, mit drei Helfern aus Stein"));
        assertEquals("bau ein Haus mit einem Helferzimmer", HelperWish.strip("bau ein Haus mit einem Helferzimmer"));
        // Nur die Angabe: lieber unverändert lassen als einen leeren Auftrag ans LLM schicken.
        assertEquals("mit 4 Helfern", HelperWish.strip(" mit 4 Helfern "));
    }

    @Test
    void promptBeatsStandingWishBeatsSize() {
        assertEquals(6, HelperWish.choose(OptionalInt.of(6), 2, 50, 300, 700));
        assertEquals(0, HelperWish.choose(OptionalInt.of(0), 8, 5000, 300, 700));
        assertEquals(8, HelperWish.choose(OptionalInt.empty(), 8, 50, 300, 700));
        assertEquals(0, HelperWish.choose(OptionalInt.empty(), null, 299, 300, 700));
        assertEquals(1, HelperWish.choose(OptionalInt.empty(), null, 300, 300, 700));
        assertEquals(2, HelperWish.choose(OptionalInt.empty(), null, 700, 300, 700));
    }

    @Test
    void commandTakesNumbersStepsAndAuto() {
        assertEquals(4, HelperWish.parseCommand("4", 0));
        assertEquals(HelperWish.MAX, HelperWish.parseCommand("40", 0));
        assertEquals(6, HelperWish.parseCommand("+2", 4));
        assertEquals(0, HelperWish.parseCommand("-2", 1));
        assertEquals(-1, HelperWish.parseCommand("Auto", 3));
        assertNull(HelperWish.parseCommand("viele", 3));
        assertNull(HelperWish.parseCommand(null, 3));
    }

    @Test
    void sectorsSplitTheSiteEvenly() {
        // 16 Arbeiter um eine Mitte: jeder bekommt Blöcke, keiner fällt aus dem Bereich 0..n-1.
        Set<Integer> seen = new HashSet<>();
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                int s = BuildJob.sector(x, z, 0.5, 0.5, 17);
                assertEquals(true, s >= 0 && s < 17, x + "/" + z + " → " + s);
                seen.add(s);
            }
        }
        assertEquals(17, seen.size());
        assertEquals(0, BuildJob.sector(5, 5, 5.5, 5.5, 1));
    }
}
