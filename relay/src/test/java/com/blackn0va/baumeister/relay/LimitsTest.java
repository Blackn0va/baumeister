package com.blackn0va.baumeister.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Byte-Budget aus der Heap-Obergrenze (Limits.budgetFor): 40 % von -Xmx, höchstens 48 MiB (docs/kanal-v1.md,
 * Abschnitt 4). Festgelegte Entscheidung (Gedächtnis des Betriebs, Relay 2026-11-08): Wer den Anteil hebt, lässt das
 * Relay mit -Xmx32m bei gestauten Antworten in OutOfMemoryError laufen, und 18080 wäre bis zum Neustart frei.
 */
class LimitsTest {

    private static final long MIB = 1024L * 1024;

    @Test
    void budgetIsFortyPercentOfTheMaximumHeap() {
        assertEquals(40 * MIB, Limits.budgetFor(100 * MIB));
        // 12,8 MiB, auf ganze Bytes abgerundet.
        assertEquals(13_421_772L, Limits.budgetFor(32 * MIB), "-Xmx32m wie in der Unit auf dev01");
        assertEquals(0, Limits.budgetFor(0));
    }

    @Test
    void budgetNeverExceedsTheSpecification() {
        assertEquals(48 * MIB, Limits.SPEC_BUDGET);
        assertEquals(48 * MIB, Limits.budgetFor(120 * MIB), "genau an der Grenze: 40 % von 120 MiB");
        assertEquals(48 * MIB, Limits.budgetFor(121 * MIB));
        assertEquals(48 * MIB, Limits.budgetFor(8L * 1024 * MIB));
        assertEquals(48 * MIB, Limits.budgetFor(Long.MAX_VALUE), "ohne -Xmx meldet die JVM bis Long.MAX_VALUE");
    }

    @Test
    void standardLimitsUseTheBudgetOfThisJvm() {
        assertEquals(Limits.budgetFor(Runtime.getRuntime().maxMemory()), Limits.standard().byteBudget());
    }
}
