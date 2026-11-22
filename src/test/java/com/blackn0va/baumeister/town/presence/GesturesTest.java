package com.blackn0va.baumeister.town.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.LongUnaryOperator;

import org.junit.jupiter.api.Test;

/** Arm schwingen bei der Arbeit, schleichen nur im Stehen. */
class GesturesTest {

    /** Zufall immer 0: jeweils die kürzeste Spanne. */
    private static final LongUnaryOperator MIN = n -> 0;

    @Test
    void workerSwingsEverySecondOrTwoEvenWhenWatched() {
        Gestures.State s = new Gestures.State();
        int swings = 0;
        for (long t = 0; t < 12_000; t += 50) {
            if (Gestures.next(s, Gestures.Activity.WORK, t, MIN).swing()) {
                swings++;
            }
        }
        // 12 s mit 1,2 s Abstand: 10 Schläge.
        assertEquals(10, swings);
    }

    @Test
    void onlyWorkersSwing() {
        Gestures.State s = new Gestures.State();
        for (Gestures.Activity a : new Gestures.Activity[] {Gestures.Activity.WALK, Gestures.Activity.IDLE,
                Gestures.Activity.TALK}) {
            for (long t = 0; t < 5_000; t += 50) {
                assertFalse(Gestures.next(s, a, t, MIN).swing(), a.name());
            }
        }
    }

    @Test
    void idleResidentSneaksBrieflyAndStandsUpAgain() {
        Gestures.State s = new Gestures.State();
        Boolean first = null;
        long downAt = -1;
        long upAt = -1;
        for (long t = 0; t < 40_000; t += 50) {
            Boolean sneak = Gestures.next(s, Gestures.Activity.IDLE, t, MIN).sneak();
            if (sneak != null && first == null) {
                first = sneak;
                downAt = t;
            } else if (sneak != null && upAt < 0) {
                assertFalse(sneak);
                upAt = t;
            }
        }
        assertEquals(Boolean.TRUE, first);
        assertEquals(Gestures.SNEAK_FIRST_MIN_MS, downAt, "frühestens 10 s nach dem Erscheinen");
        assertEquals(downAt + Gestures.SNEAK_MIN_MS, upAt);
    }

    @Test
    void walkingStopsSneakingAtOnceAndWorkersNeverSneak() {
        Gestures.State s = new Gestures.State();
        for (long t = 0; t <= Gestures.SNEAK_FIRST_MIN_MS; t += 50) {
            Gestures.next(s, Gestures.Activity.IDLE, t, MIN);
        }
        assertTrue(s.sneaking());
        assertEquals(Boolean.FALSE, Gestures.next(s, Gestures.Activity.WALK, Gestures.SNEAK_FIRST_MIN_MS + 50, MIN)
                .sneak());
        Gestures.State w = new Gestures.State();
        for (long t = 0; t < 120_000; t += 50) {
            assertNull(Gestures.next(w, Gestures.Activity.WORK, t, MIN).sneak());
        }
    }
}
