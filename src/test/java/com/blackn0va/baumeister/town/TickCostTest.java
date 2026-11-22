package com.blackn0va.baumeister.town;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Messung der Tickkosten (Vertrag v008, AK-25): Mittel, p99, Höchstwert über ein festes Fenster. */
class TickCostTest {

    @Test
    void meanP99AndMax() {
        TickCost c = new TickCost();
        for (int i = 0; i < 99; i++) {
            c.add(1_000_000);
        }
        c.add(9_000_000);
        double[] m = c.millis();
        assertEquals(1.08, m[0], 1e-9);
        assertEquals(1.0, m[1], 1e-9);
        assertEquals(9.0, m[2], 1e-9);
    }

    @Test
    void windowDropsOldTicks() {
        TickCost c = new TickCost();
        c.add(50_000_000);
        for (int i = 0; i < TickCost.WINDOW; i++) {
            c.add(1_000_000);
        }
        assertEquals(TickCost.WINDOW, c.size());
        assertArrayEquals(new double[] {1, 1, 1}, c.millis(), 1e-9);
        c.clear();
        assertArrayEquals(new double[] {0, 0, 0}, c.millis(), 1e-9);
    }
}
