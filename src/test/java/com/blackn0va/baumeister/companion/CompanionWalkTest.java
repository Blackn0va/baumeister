package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Laufen statt Teleportieren: Tempo, Ankommen, Springen, Wasser, Festhängen (Messwerte in CompanionWalk). */
class CompanionWalkTest {

    private static CompanionWalk.Body ground(double x, double z) {
        return new CompanionWalk.Body(x, 64, z, -0.08, true, false);
    }

    @Test
    void walksTowardGoalWithoutOvershooting() {
        CompanionWalk.State s = new CompanionWalk.State();
        CompanionWalk.Step far = CompanionWalk.next(s, ground(0, 0), 5, 64, 0, CompanionWalk.SPEED);
        assertEquals(CompanionWalk.SPEED, far.vx(), 1.0e-9);
        assertEquals(0, far.vz(), 1.0e-9);
        assertFalse(far.teleport());
        CompanionWalk.Step diagonal = CompanionWalk.next(new CompanionWalk.State(), ground(0, 0), 3, 64, 4, 0.22);
        assertEquals(0.22, Math.hypot(diagonal.vx(), diagonal.vz()), 1.0e-9, "schräg nicht schneller");
        assertEquals(0.6, diagonal.vx() / 0.22, 1.0e-9);
    }

    @Test
    void arrivesAndStops() {
        CompanionWalk.Step s = CompanionWalk.next(new CompanionWalk.State(), ground(0, 0), 0.2, 64, 0.1, 0.22);
        assertTrue(s.arrived());
        assertFalse(s.moving());
    }

    @Test
    void jumpsWhenBlockedOnGround() {
        CompanionWalk.State s = new CompanionWalk.State();
        CompanionWalk.Step first = CompanionWalk.next(s, ground(0, 0), 5, 64, 0, 0.22);
        assertEquals(-0.08, first.vy(), 1.0e-9, "erst laufen, nicht springen");
        // Nächster Tick am selben Fleck: die Wand hat sie aufgehalten.
        CompanionWalk.Step second = CompanionWalk.next(s, ground(0, 0), 5, 64, 0, 0.22);
        assertEquals(CompanionWalk.JUMP, second.vy(), 1.0e-9);
    }

    @Test
    void jumpsEarlyWhenTheNextStepIsHigher() {
        // Bergauf: Wegpunkt einen Block höher, 1 Block weit – springen, bevor sie anstößt (vorher ein Tick Stillstand
        // je Stufe).
        CompanionWalk.Step s = CompanionWalk.next(new CompanionWalk.State(), ground(0, 0), 1, 65, 0, 0.22);
        assertEquals(CompanionWalk.JUMP, s.vy(), 1.0e-9);
    }

    @Test
    void climbsLaddersAndClimbingCountsAsProgress() {
        CompanionWalk.State s = new CompanionWalk.State();
        CompanionWalk.Step up = null;
        for (int t = 0; t <= CompanionWalk.STUCK_TICKS + 5; t++) {
            up = CompanionWalk.next(s, new CompanionWalk.Body(0.5, 64 + t * 0.1, 0.5, 0.2, false, false, true), 0.5,
                    70, 0.5, 0.22);
            assertFalse(up.teleport(), "Steigen ist Fortschritt (Tick " + t + ")");
        }
        assertEquals(CompanionWalk.CLIMB, up.vy(), 1.0e-9);
        CompanionWalk.Step down = CompanionWalk.next(new CompanionWalk.State(),
                new CompanionWalk.Body(0.5, 68, 0.5, 0, false, false, true), 0.5, 64, 0.5, 0.22);
        assertTrue(down.vy() < 0);
    }

    @Test
    void doesNotJumpInTheAirOrWhileMoving() {
        CompanionWalk.State s = new CompanionWalk.State();
        CompanionWalk.next(s, ground(0, 0), 5, 64, 0, 0.22);
        CompanionWalk.Step air = CompanionWalk.next(s, new CompanionWalk.Body(0, 64.5, 0, 0.1, false, false), 5, 64, 0,
                0.22);
        assertEquals(0.1, air.vy(), 1.0e-9);
        CompanionWalk.State t = new CompanionWalk.State();
        CompanionWalk.next(t, ground(0, 0), 5, 64, 0, 0.22);
        CompanionWalk.Step moving = CompanionWalk.next(t, ground(0.22, 0), 5, 64, 0, 0.22);
        assertEquals(-0.08, moving.vy(), 1.0e-9);
    }

    /**
     * Schwimmen auf die Höhe des Ziels (2026-11-07). Vorher: +0,04 je Tick nach oben, außer das Ziel lag tiefer – dann
     * sank sie nur (SwimProbe tauchen: Tiefenfehler 2,5 Blöcke, 2 Teleports; neu 0,3 und 0).
     */
    @Test
    void swimsToTheDepthOfTheGoal() {
        CompanionWalk.Body wet = new CompanionWalk.Body(0, 60, 0, -0.02, false, true);
        assertEquals(0.3, CompanionWalk.next(new CompanionWalk.State(), wet, 5, 62, 0, 0.22).vy(), 1.0e-9,
                "zwei höher: mit voller Steiggeschwindigkeit");
        assertEquals(-0.25, CompanionWalk.next(new CompanionWalk.State(), wet, 5, 57, 0, 0.22).vy(), 1.0e-9,
                "Spieler taucht: hinterher, nicht nur sinken");
        assertEquals(CompanionWalk.SWIM_UP, CompanionWalk.next(new CompanionWalk.State(), wet, 5, 60, 0, 0.22).vy(),
                1.0e-9, "gleiche Höhe: an der Oberfläche halten wie bisher");
        assertEquals(-0.16, CompanionWalk.swim(-1), 1.0e-9);
    }

    /** Ufer mit 1 Block hohem Rand (SwimProbe ufer: vorher nur per Teleport an Land, neu von selbst nach 0,8 s). */
    @Test
    void climbsOutAtALedgeThatBlocksHer() {
        CompanionWalk.State s = new CompanionWalk.State();
        CompanionWalk.Body atWall = new CompanionWalk.Body(0, 60, 0, 0.0, false, true);
        CompanionWalk.next(s, atWall, 3, 62, 0, 0.22);                        // schiebt gegen die Kante
        CompanionWalk.Step out = CompanionWalk.next(s, atWall, 3, 62, 0, 0.22); // kam nicht weiter
        assertEquals(CompanionWalk.CLIMB_OUT, out.vy(), 1.0e-9);
        assertTrue(out.vx() > 0, "weiter auf das Ufer zu");
    }

    @Test
    void teleportsWhenFarAway() {
        assertTrue(CompanionWalk.next(new CompanionWalk.State(), ground(0, 0), 25, 64, 0, 0.22).teleport());
        assertTrue(CompanionWalk.next(new CompanionWalk.State(), ground(0, 0), 3, 90, 0, 0.22).teleport());
        assertFalse(CompanionWalk.next(new CompanionWalk.State(), ground(0, 0), 15, 64, 0, 0.22).teleport());
    }

    @Test
    void teleportsAfterBeingStuck() {
        CompanionWalk.State s = new CompanionWalk.State();
        CompanionWalk.Step last = null;
        for (int t = 0; t <= CompanionWalk.STUCK_TICKS; t++) {
            last = CompanionWalk.next(s, ground(0, 0), 5, 64, 0, 0.22);
            if (last.teleport()) {
                break;
            }
        }
        assertTrue(last.teleport(), "nach " + CompanionWalk.STUCK_TICKS + " Ticks ohne Fortschritt");
        assertEquals(0, s.stuck(), "danach von vorn");
    }

    @Test
    void progressResetsTheStuckCounter() {
        CompanionWalk.State s = new CompanionWalk.State();
        double x = 0;
        for (int t = 0; t < 3 * CompanionWalk.STUCK_TICKS; t++) {
            // Abwechselnd hängen und weiterkommen (Zaunpfosten, Blumen): nie Teleport.
            x += t % 2 == 0 ? 0 : 0.2;
            assertFalse(CompanionWalk.next(s, ground(x, 0), x + 10, 64, 0, 0.22).teleport());
        }
    }

    @Test
    void standingBelowAPlayerOnATowerCountsAsStuck() {
        CompanionWalk.State s = new CompanionWalk.State();
        boolean teleported = false;
        for (int t = 0; t <= CompanionWalk.STUCK_TICKS && !teleported; t++) {
            teleported = CompanionWalk.next(s, ground(0, 0), 0.1, 70, 0, 0.22).teleport();
        }
        assertTrue(teleported);
    }

    @Test
    void catchesUpSmoothlyWhenBehind() {
        assertEquals(CompanionWalk.SPEED, CompanionWalk.speedFor(1.5), 1.0e-9);
        assertEquals(CompanionWalk.SPEED, CompanionWalk.speedFor(2), 1.0e-9);
        double mid = CompanionWalk.speedFor(3.5);
        assertTrue(mid > CompanionWalk.SPEED && mid < CompanionWalk.CATCH_UP, "dazwischen gleitend: " + mid);
        assertEquals(CompanionWalk.CATCH_UP, CompanionWalk.speedFor(CompanionWalk.CATCH_UP_FROM), 1.0e-9);
        assertEquals(CompanionWalk.CATCH_UP, CompanionWalk.speedFor(8), 1.0e-9);
    }
}
