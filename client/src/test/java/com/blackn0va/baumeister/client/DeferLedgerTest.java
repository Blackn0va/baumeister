package com.blackn0va.baumeister.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Zurückgestellte Stellen zählen je Bau und Durchgang (2026-11-01). */
class DeferLedgerTest {

    @Test
    void countsOnlyAfterTenTicks() {
        DeferLedger<String> ledger = new DeferLedger<>();
        assertEquals(1, ledger.count("x", 0));
        assertEquals(2, ledger.count("x", 10));
        assertEquals(3, ledger.count("x", 20));
        assertEquals(3, ledger.count("x", 20), "gleicher Tick zählt nicht");
        assertEquals(3, ledger.count("x", 29), "unter 10 Ticks zählt nicht");
    }

    @Test
    void nextPassStartsAgainAtTheFirstTry() {
        DeferLedger<String> ledger = new DeferLedger<>();
        Object job = new Object();
        // Wie PlayerPlacer.tick: Rücksetzen, sobald Bau oder Durchgang wechseln.
        Object lastJob = null;
        int lastPass = 0;
        int tick = 0;
        for (int i = 0; i < 3; i++, tick += 10) {
            if (DeferLedger.needsReset(lastJob, lastPass, job, 0)) {
                ledger.reset();
                lastJob = job;
                lastPass = 0;
            }
            ledger.count("x", tick);
        }
        if (DeferLedger.needsReset(lastJob, lastPass, job, 1)) {
            ledger.reset();
        }
        // Ohne Rücksetzen wäre das Versuch 4 – die Stelle sofort ausgelassen, gerade im Nachbesserungsdurchgang.
        assertEquals(1, ledger.count("x", tick));
    }

    @Test
    void resetEmptiesTheCounts() {
        DeferLedger<String> ledger = new DeferLedger<>();
        ledger.count("x", 0);
        ledger.count("x", 10);
        ledger.count("x", 20);
        ledger.reset();
        assertEquals(1, ledger.count("x", 21), "nach reset() zählt der erste Versuch sofort");
    }

    @Test
    void resetRuleNewJobOrNextPass() {
        Object a = new Object();
        Object b = new Object();
        assertTrue(DeferLedger.needsReset(a, 0, b, 0), "neuer Bau");
        assertTrue(DeferLedger.needsReset(a, 0, a, 1), "nächster Durchgang");
        assertTrue(DeferLedger.needsReset(null, 0, a, 0), "erster Bau der Sitzung");
        assertFalse(DeferLedger.needsReset(a, 1, a, 1), "gleicher Bau, gleicher Durchgang");
    }
}
