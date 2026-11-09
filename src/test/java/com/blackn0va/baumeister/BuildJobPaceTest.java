package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Schnellgang ab der Zieldauer (bau.zieldauer-sekunden, Vorgabe 120 s): Die Schätzung muss das Laufen der Arbeiter
 * enthalten. Messwerte Paper-Testkopie 2026-10-30, 3 Arbeiter, ticks-pro-block 1.
 */
class BuildJobPaceTest {

    private static final long TARGET = 120 * 20L;

    @Test
    void theMeasuredSlowBuildsNowGoFast() {
        // Bunker: 6 302 Aktionen, Block für Block 330,9 s. Taschenrechner: 2 545 Aktionen, 140,4 s.
        assertTrue(BuildJob.normalTicks(6302, 1, 3) > TARGET);
        assertTrue(BuildJob.normalTicks(2545, 1, 3) > TARGET);
    }

    @Test
    void smallBuildsStayBlockByBlock() {
        // Haus aus dem System-Prompt: 235 Aktionen, 3 Arbeiter – sichtbar Block für Block wie ein Spieler.
        assertTrue(BuildJob.normalTicks(235, 1, 3) < TARGET);
        // Mehr Helfer halten auch größere Bauten im Spielertempo: 1 500 Aktionen mit 16 Arbeitern.
        assertTrue(BuildJob.normalTicks(1500, 1, 16) < TARGET);
    }
}
