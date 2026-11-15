package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Gemeinsames Maß für Veras ungefragte Sätze (2026-11-15: „spricht Dinge aus, die wirklich nervig sind“). */
class ChatterBudgetTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void secondRemarkWaitsForTheGapAcrossAllOccasions() {
        ChatterBudget c = new ChatterBudget();
        assertTrue(c.allow(A, ChatterBudget.Kind.NORMAL, 0));
        // Ein anderer Anlass kurz danach (vorher: eigene Sperrzeit je Anlass, also sofort der nächste Spruch).
        assertFalse(c.allow(A, ChatterBudget.Kind.NORMAL, 30_000));
        assertFalse(c.allow(A, ChatterBudget.Kind.NORMAL, ChatterBudget.GAP_NORMAL - 1));
        assertTrue(c.allow(A, ChatterBudget.Kind.NORMAL, ChatterBudget.GAP_NORMAL));
    }

    @Test
    void urgentWarningsComeSoonerButNotEverySecond() {
        ChatterBudget c = new ChatterBudget();
        assertTrue(c.allow(A, ChatterBudget.Kind.NORMAL, 0));
        assertFalse(c.allow(A, ChatterBudget.Kind.URGENT, 5_000));
        assertTrue(c.allow(A, ChatterBudget.Kind.URGENT, ChatterBudget.GAP_URGENT));
        assertFalse(c.allow(A, ChatterBudget.Kind.URGENT, ChatterBudget.GAP_URGENT + 1_000));
    }

    @Test
    void answeringThePlayerResetsTheClockAndPlayersAreSeparate() {
        ChatterBudget c = new ChatterBudget();
        c.spoke(A, 100_000);
        assertFalse(c.allow(A, ChatterBudget.Kind.NORMAL, 100_000 + 60_000),
                "gerade geantwortet – kein Spruch hinterher");
        assertTrue(c.allow(B, ChatterBudget.Kind.NORMAL, 100_000 + 60_000), "anderer Spieler hat sein eigenes Maß");
        c.forget(A);
        assertTrue(c.allow(A, ChatterBudget.Kind.NORMAL, 100_000 + 61_000));
    }
}
