package com.blackn0va.baumeister.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.Test;

/** Ausprobieren: Gray-Code-Plan, Formel aus Messwerten, Gedächtnis erkennen. */
class RedstoneExperimentTest {

    private static final List<String> AB = List.of("A", "B");
    private static final List<String> ABC = List.of("A", "B", "C");

    /** Messwerte wie vom Server, aus einer Funktion der Kombination. */
    private static boolean[][] measure(int inputs, IntPredicate f) {
        int[] plan = RedstoneExperiment.plan(inputs);
        boolean[][] r = new boolean[plan.length][1];
        for (int s = 0; s < plan.length; s++) {
            r[s][0] = f.test(plan[s]);
        }
        return r;
    }

    @Test
    void planVisitsEveryCombinationOnceTogglingOneLeverEachStep() {
        int[] p = RedstoneExperiment.plan(3);
        assertEquals(9, p.length);
        assertEquals(8, java.util.Arrays.stream(p, 0, 8).distinct().count());
        for (int i = 1; i < p.length - 1; i++) {
            assertEquals(1, Integer.bitCount(p[i] ^ p[i - 1]), "nur ein Hebel je Schritt");
        }
        assertEquals(0, p[8], "zum Schluss die Anfangsstellung");
    }

    @Test
    void findsTheGate() {
        assertEquals("A und B", one(2, AB, c -> c == 3));
        assertEquals("A oder B", one(2, AB, c -> c != 0));
        assertEquals("nicht A", one(2, AB, c -> (c & 1) == 0));
        assertEquals("(A und nicht B) oder (nicht A und B)", one(2, AB, c -> Integer.bitCount(c) == 1));
        assertEquals("immer an", one(2, AB, c -> true));
        assertEquals("immer aus", one(2, AB, c -> false));
    }

    @Test
    void namesUnusedLevers() {
        List<RedstoneExperiment.Output> o = RedstoneExperiment.analyse(measure(3, c -> (c & 1) == 1), 3, ABC,
                List.of("Lampe"));
        assertEquals("A", o.get(0).formula());
        assertEquals(List.of("B", "C"), RedstoneExperiment.unused(o, ABC));
    }

    @Test
    void detectsMemory() {
        // Speicher: Am Ende der Runde ist die Lampe an, am Anfang (gleiche Stellung) war sie aus.
        boolean[][] r = measure(2, c -> c == 3);
        r[r.length - 1][0] = true;
        assertTrue(RedstoneExperiment.analyse(r, 2, AB, List.of("Q")).get(0).memory());
        assertFalse(RedstoneExperiment.analyse(measure(2, c -> c == 3), 2, AB, List.of("Q")).get(0).memory());
    }

    private static String one(int inputs, List<String> names, IntPredicate f) {
        return RedstoneExperiment.analyse(measure(inputs, f), inputs, names, List.of("Lampe")).get(0).formula();
    }
}
