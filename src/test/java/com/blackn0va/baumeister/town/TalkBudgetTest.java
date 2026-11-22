package com.blackn0va.baumeister.town;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Stadtleben: KI-Budget wird eingehalten, Bau hat Vorrang (Vertrag v008, AK-24). */
class TalkBudgetTest {

    private static final long GAP = 45_000;

    @Test
    void atMostOneRequestPerGap() {
        TalkBudget b = new TalkBudget(GAP);
        assertTrue(b.begin(0, false, false));
        b.end(800, true);
        assertFalse(b.begin(GAP - 1, false, false));
        assertTrue(b.begin(GAP, false, false));
        assertEquals(2, b.asked());
    }

    @Test
    void neverTwoAtOnce() {
        TalkBudget b = new TalkBudget(0);
        assertTrue(b.begin(0, false, false));
        assertFalse(b.begin(100_000, false, false), "erste Anfrage läuft noch");
        b.end(500, false);
        assertTrue(b.begin(100_001, false, false));
    }

    @Test
    void noRequestWhileABuildPlanThinks() {
        TalkBudget b = new TalkBudget(GAP);
        assertFalse(b.begin(0, true, false));
        assertEquals(0, b.asked());
        assertTrue(b.begin(0, false, false));
    }

    @Test
    void noRequestWhileVeraThinks() {
        TalkBudget b = new TalkBudget(GAP);
        assertFalse(b.begin(0, false, true));
        assertEquals(0, b.asked());
    }

    @Test
    void refusalDoesNotStartTheClock() {
        TalkBudget b = new TalkBudget(GAP);
        assertFalse(b.begin(0, true, false));
        // Gleich nach dem Bau darf gefragt werden – die Absage hat keine Lücke verbraucht.
        assertTrue(b.begin(1, false, false));
    }

    @Test
    void countsAnswersFallbacksAndTimes() {
        TalkBudget b = new TalkBudget(0);
        b.begin(0, false, false);
        b.end(1000, true);
        b.begin(1, false, false);
        b.end(3000, false);
        assertEquals(1, b.answered());
        assertEquals(1, b.fallbacks());
        assertArrayEquals(new long[] {2000, 3000}, b.timesMeanMax());
        assertFalse(b.lastOk());
    }

    @Test
    void defaultGapIsWithinTheContract() {
        TownSettings s = TownSettings.defaults();
        assertEquals(45_000, s.talkGapMillis());
        assertEquals(15, TownSettings.MIN_GAP_SECONDS);
        assertEquals(600, TownSettings.MAX_GAP_SECONDS);
    }
}
