package com.blackn0va.baumeister.town;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.companion.CompanionWalk;

import org.junit.jupiter.api.Test;

/**
 * Feststecken (Paper-Probe 2026-11-21, Spur greta): Sprung auf der Stelle (y 93,0–94,25) und waagrechtes Wackeln um
 * 0,2–0,4 Blöcke, 10 Blöcke vom Teilziel. CompanionWalk sah darin Fortschritt und teleportierte nie; Progress misst
 * die Annäherung ans Ziel.
 */
class ProgressTest {

    /** Wackelspur wie bei Greta: Position pendelt, Abstand zum Ziel bleibt bei ~10. */
    private static double[] wobble(int tick) {
        double dx = (tick % 4 < 2 ? 0.3 : -0.3) * (tick % 3 == 0 ? 0.7 : 1.0);
        double y = 93.0 + (tick % 6 < 3 ? 1.25 * (tick % 3) / 2.0 : 0);
        return new double[] {-3 + dx, y, -20};
    }

    private static final double[] GOAL = {7, 93, -20};

    /**
     * Weg da, Weg weg, Weg da (Suche am Rand ihrer Reichweite): Das Maß wechselt nur einmal auf den Restweg. Vorher
     * jedes Mal – dann zählte das Wackeln nach jedem Wechsel als Annäherung, und er steckte nie fest (live T-035).
     */
    @Test
    void flickeringPathDoesNotHideBeingStuck() {
        Progress p = new Progress();
        p.reset(0);
        int at = -1;
        for (int t = 0; t <= 400 && at < 0; t++) {
            double wobble = t % 2 == 0 ? 0.3 : -0.3;
            double rest = t % 40 < 20 ? Double.NaN : 10.0 + wobble;
            if (p.stuck(rest, 8.0 + wobble, t)) {
                at = t;
            }
        }
        assertTrue(at >= 0, "nie festgesteckt");
        assertTrue(at <= 3 * Progress.STUCK_TICKS, "erst nach " + at + " Takten");
    }

    @Test
    void wobblingInPlaceIsStuckAfterThreeSeconds() {
        Progress p = new Progress();
        p.reset(0);
        boolean stuck = false;
        int at = -1;
        for (int t = 0; t <= 200 && !stuck; t++) {
            double[] b = wobble(t);
            stuck = p.stuck(Math.hypot(Math.hypot(GOAL[0] - b[0], GOAL[2] - b[2]), GOAL[1] - b[1]), t);
            at = t;
        }
        assertTrue(stuck, "Wackeln galt als Fortschritt");
        assertTrue(at <= Progress.STUCK_TICKS + 5, "erst nach " + at + " Ticks");
    }

    /** Dieselbe Spur hält CompanionWalk für Bewegung – deshalb der eigene Zähler (Vera bleibt unverändert). */
    @Test
    void companionWalkSeesTheWobbleAsMovement() {
        CompanionWalk.State s = new CompanionWalk.State();
        int maxStuck = 0;
        for (int t = 0; t < 200; t++) {
            double[] b = wobble(t);
            CompanionWalk.Step step = CompanionWalk.next(s, new CompanionWalk.Body(b[0], b[1], b[2], 0, true, false),
                    GOAL[0], GOAL[1], GOAL[2], 0.16);
            assertFalse(step.teleport(), "CompanionWalk teleportiert hier nie (Tick " + t + ")");
            maxStuck = Math.max(maxStuck, s.stuck());
        }
        assertTrue(maxStuck < CompanionWalk.STUCK_TICKS);
    }

    @Test
    void walkingTowardsTheGoalIsProgress() {
        Progress p = new Progress();
        p.reset(0);
        for (int t = 0; t < 300; t++) {
            assertFalse(p.stuck(30 - t * 0.1, t), "Tick " + t);
        }
    }

    @Test
    void resetStartsCountingAgain() {
        Progress p = new Progress();
        p.reset(0);
        p.stuck(10, 0);
        assertTrue(p.stuck(10, Progress.STUCK_TICKS));
        p.reset(Progress.STUCK_TICKS);
        assertFalse(p.stuck(10, Progress.STUCK_TICKS + 1));
    }
}
