package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Arbeit je Tick nach Zeit (Prüfphase von BuildJob). */
class TimeSliceTest {

    /** Erfundene Uhr: jeder Schritt kostet stepNanos. */
    private static final class FakeClock {
        long now = 1_000_000_000L;
    }

    @Test
    void cheapChecksUseTheWholeTickBudget() {
        // Konsole/bauenbei: Prüfung ohne Spieler ist nur ein Namensvergleich (~1 µs). Vorher fest 300 je Tick, ein
        // Hochhaus (31 111 Aktionen) wartete 104 Ticks.
        FakeClock c = new FakeClock();
        int n = TimeSlice.run(() -> c.now, 10_000_000L, 32, 50_000, () -> {
            c.now += 1_000;
            return true;
        });
        assertTrue(n >= 9_000, "billige Prüfungen je Tick: " + n);
        assertTrue(n <= 10_000 + TimeSlice.CLOCK_EVERY, "nicht über die Zeit: " + n);
    }

    @Test
    void expensiveChecksStopAtTheTimeLimit() {
        // Mit Spieler: zwei Ereignisse je Block für WorldGuard & Co. – teuer. Dann zählt die Zeit, nicht die Zahl.
        FakeClock c = new FakeClock();
        int n = TimeSlice.run(() -> c.now, 10_000_000L, 32, 50_000, () -> {
            c.now += 500_000;
            return true;
        });
        assertEquals(32, n, "nach dem Mindestmaß an der ersten Uhrprüfung Schluss");
    }

    @Test
    void stepThatFinishesEndsTheSliceAtOnce() {
        FakeClock c = new FakeClock();
        int[] left = {5};
        int n = TimeSlice.run(() -> c.now, 10_000_000L, 32, 50_000, () -> --left[0] > 0);
        assertEquals(5, n);
    }

    @Test
    void neverMoreThanMax() {
        FakeClock c = new FakeClock();
        assertEquals(100, TimeSlice.run(() -> c.now, Long.MAX_VALUE / 4, 32, 100, () -> true));
    }
}
