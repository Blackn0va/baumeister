package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Folgen: auf Veras Seite statt „hinter dem Rücken“, in Gängen auf der Spur, ohne Weg der NEUESTE erreichbare
 * Spur-Punkt (Wunsch 2026-11-07). Füße bei y=64.
 */
class CompanionFollowTest {

    /** rock=false: Boden y<=63 (außer holes), darüber Luft plus solids. rock=true: alles Fels außer air. */
    private static final class Grid implements CompanionPath.Blocks {
        final boolean rock;
        final Set<String> solids = new HashSet<>();
        final Set<String> air = new HashSet<>();
        final Set<String> holes = new HashSet<>();

        Grid(boolean rock) {
            this.rock = rock;
        }

        static String k(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        /** Gang 2 hoch auf Fußhöhe 64 von (x1,z) bis (x2,z). */
        void tunnelX(int x1, int x2, int z) {
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                air.add(k(x, 64, z));
                air.add(k(x, 65, z));
            }
        }

        void tunnelZ(int x, int z1, int z2) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                air.add(k(x, 64, z));
                air.add(k(x, 65, z));
            }
        }

        /** Grube: Boden fehlt bis tief hinunter. */
        void hole(int x, int z) {
            for (int y = 40; y <= 63; y++) {
                holes.add(k(x, y, z));
            }
        }

        @Override
        public boolean solid(int x, int y, int z) {
            if (rock) {
                return !air.contains(k(x, y, z));
            }
            if (holes.contains(k(x, y, z))) {
                return false;
            }
            return y <= 63 || solids.contains(k(x, y, z));
        }

        @Override
        public boolean passable(int x, int y, int z) {
            return !solid(x, y, z);
        }

        @Override
        public boolean water(int x, int y, int z) {
            return false;
        }
    }

    private static void walkX(CompanionFollow.Trail t, int from, int to, int z) {
        int step = from <= to ? 1 : -1;
        for (int x = from; x != to + step; x += step) {
            t.record(x + 0.5, 64, z + 0.5);
        }
    }

    private static void walkZ(CompanionFollow.Trail t, int x, int from, int to) {
        int step = from <= to ? 1 : -1;
        for (int z = from; z != to + step; z += step) {
            t.record(x + 0.5, 64, z + 0.5);
        }
    }

    @Test
    void openFieldGoalIsOnVerasSideNotBehindTheBack() {
        Grid w = new Grid(false);
        CompanionFollow.Trail t = new CompanionFollow.Trail();
        walkX(t, 0, 10, 0);
        // Vera westlich hinter dem Spieler: Ziel 2,5 Blöcke Richtung Vera.
        assertArrayEquals(new double[] {8.5, 64, 0.5}, CompanionFollow.goal(w, 10.5, 64, 0.5, 4.5, 0.5, 2.5, t),
                1.0e-9);
        // Spieler hat umgedreht, Vera steht nun östlich von ihm: Ziel bleibt auf IHRER Seite. Vorher lag es nach der
        // Blickrichtung hinter seinem Rücken – sie lief an ihm vorbei zurück.
        assertArrayEquals(new double[] {13.5, 64, 0.5}, CompanionFollow.goal(w, 10.5, 64, 0.5, 15.5, 0.5, 2.5, t),
                1.0e-9);
    }

    @Test
    void spotMoreThanOneBlockUpOrDownIsNotTaken() {
        Grid w = new Grid(false);
        // Mauer 2 hoch auf Veras Seite: oben drauf wäre Platz, aber hinkommen kann sie nicht.
        w.solids.add(Grid.k(8, 64, 0));
        w.solids.add(Grid.k(8, 65, 0));
        assertNull(CompanionFollow.toward(w, 10.5, 64, 0.5, 4.5, 0.5, 2.5, null));
    }

    @Test
    void tunnelGoalFollowsTheTrailInsteadOfTheRock() {
        Grid w = new Grid(true);
        w.tunnelX(0, 12, 0);
        w.tunnelZ(12, 0, 6);
        CompanionFollow.Trail t = new CompanionFollow.Trail();
        walkX(t, 0, 12, 0);
        walkZ(t, 12, 1, 6);
        // Spieler um die Ecke (12, 6), Vera noch im Gang Richtung Westen: Richtung Vera liegt Fels.
        double[] g = CompanionFollow.goal(w, 12.5, 64, 6.5, 6.5, 0.5, 2.5, t);
        assertTrue(CompanionPath.standable(w, (int) Math.floor(g[0]), 64, (int) Math.floor(g[2])), "auf der Spur");
        assertEquals(12.5, g[0], 1.0e-9);
        assertTrue(g[2] < 6.5, "hinter dem Spieler auf seinem Weg: " + g[2]);
    }

    @Test
    void spotInTheNeighbourTunnelBehindAWallIsNotTaken() {
        Grid w = new Grid(true);
        w.tunnelX(0, 12, 0);
        w.tunnelX(0, 12, -2);
        CompanionFollow.Trail t = new CompanionFollow.Trail();
        walkX(t, 0, 12, 0);
        // Vera im Nachbargang (z=-2): Richtung Vera wäre dort Platz, aber Fels dazwischen (z=-1).
        double[] g = CompanionFollow.goal(w, 12.5, 64, 0.5, 12.5, -1.5, 2.5, t);
        assertEquals(0.5, g[2], 1.0e-9, "Spur im Gang des Spielers statt Nachbargang hinter der Wand");
    }

    @Test
    void withoutTrailAndWithoutRoomTheGoalIsThePlayer() {
        Grid w = new Grid(true);
        w.tunnelX(0, 3, 0);
        assertArrayEquals(new double[] {3.5, 64, 0.5}, CompanionFollow.goal(w, 3.5, 64, 0.5, 3.5, 5.5, 2.5, null),
                1.0e-9);
    }

    @Test
    void goalIsThePlayerOrHisNewestStandpoint() {
        Grid w = new Grid(false);
        CompanionFollow.Trail t = new CompanionFollow.Trail();
        walkX(t, 0, 10, 0);
        assertArrayEquals(new double[] {10.5, 64, 0.5}, CompanionFollow.goalPlayer(w, 10.5, 64, 0.5, t), 1.0e-9,
                "steht er, ist er das Ziel");
        // Im Sprung (zwei Blöcke über dem Boden): sein neuester Standpunkt, nicht die Luft.
        assertArrayEquals(new double[] {10.5, 64, 0.5}, CompanionFollow.goalPlayer(w, 10.9, 66.2, 0.5, t), 1.0e-9);
        // Spur veraltet (er ist weit weg in der Luft): dann eben er selbst – nie ein alter Punkt.
        assertArrayEquals(new double[] {40.5, 70, 0.5}, CompanionFollow.goalPlayer(w, 40.5, 70, 0.5, t), 1.0e-9);
    }

    @Test
    void staleTrailIsNeverTheGoal() {
        // Log dev01 2026-11-07 17:15: Spur endet am Start (16 Punkte), Spieler 48 Blöcke weiter, Vera stand GENAU auf
        // ihrem Ziel – dem Spur-Punkt hinter dem letzten Standpunkt am Start.
        Grid w = new Grid(true);
        w.tunnelX(0, 50, 0);
        CompanionFollow.Trail t = new CompanionFollow.Trail();
        walkX(t, 0, 10, 0);
        assertTrue(t.fresh(10.5, 64, 0.5));
        assertFalse(t.fresh(48.5, 64, 0.5), "Spieler weit weg vom letzten Punkt: Spur schreibt nicht mehr mit");
        // Richtung Vera (Nachbargang weit nördlich, Fels dazwischen) ist kein Platz → früher behind(2,5) am Start.
        double[] g = CompanionFollow.goal(w, 48.5, 64, 0.5, 48.5, 20.5, 2.5, t);
        assertArrayEquals(new double[] {48.5, 64, 0.5}, g, 1.0e-9, "zum Spieler, nicht zurück an den Start");
        assertNull(t.ifFresh(48.5, 64, 0.5));
    }

    @Test
    void behindMeasuresWalkedLengthAndAJumpStartsANewTrail() {
        CompanionFollow.Trail t = new CompanionFollow.Trail();
        walkX(t, 0, 10, 0);
        assertArrayEquals(new double[] {7.5, 64, 0.5}, t.behind(2.5), 1.0e-9);
        assertArrayEquals(new double[] {0.5, 64, 0.5}, t.behind(100), 1.0e-9, "kürzer als gefragt: ältester");
        t.record(40.5, 64, 0.5);
        assertEquals(1, t.size(), "Teleport des Spielers: alte Spur weg");
        t.record(40.6, 64, 0.5);
        assertEquals(1, t.size(), "unter 0,8 Block kein neuer Punkt");
    }

    @Test
    void withoutPathVeraTakesTheNewestReachablePointNeverTheOldStretch() {
        // Nutzer 2026-11-07: hin nach Osten, zurück, dann einen anderen Weg nach Norden. Vera steht auf dem doppelt
        // begangenen Stück. Vorher: nächster Spur-Punkt (vom HINWEG) und von dort weiter – nach Osten, weg vom Spieler.
        Grid w = new Grid(false);
        CompanionFollow.Trail t = new CompanionFollow.Trail();
        walkX(t, 0, 10, 0);
        walkX(t, 9, 0, 0);
        walkZ(t, 0, -1, -8);
        double[] r = t.reachable(w, 5.4, 64, 0.5);
        assertArrayEquals(new double[] {0.5, 64, -7.5}, r, 1.0e-9, "neuester sichtbarer Punkt = beim Spieler");
    }

    @Test
    void reachableDoesNotCrossAHoleAndWithoutSightGetsBackOnTheTrailOnly() {
        Grid w = new Grid(false);
        CompanionFollow.Trail t = new CompanionFollow.Trail();
        walkX(t, 0, 4, 0);
        // Spieler springt über eine Grube bei x=5 (Spur ohne Punkt darin), weiter bis x=10.
        walkX(t, 6, 10, 0);
        for (int z = -3; z <= 3; z++) {
            w.hole(5, z);
        }
        double[] r = t.reachable(w, 2.5, 64, 0.5);
        assertEquals(4.5, r[0], 1.0e-9, "nur bis vor die Grube");
        // Ohne freie Sicht auf irgendeinen Punkt (Fels, Vera im Nachbargang): nächster in 2,5 Blöcken, nicht weiter.
        Grid rock = new Grid(true);
        rock.tunnelX(0, 10, 0);
        rock.tunnelX(0, 10, 2);
        CompanionFollow.Trail u = new CompanionFollow.Trail();
        walkX(u, 0, 10, 0);
        assertArrayEquals(new double[] {3.5, 64, 0.5}, u.reachable(rock, 3.5, 64, 2.5), 1.0e-9);
        assertNull(u.reachable(rock, 3.5, 64, 8.5), "weit neben der Spur");
    }

    @Test
    void plannerTreatsAOnePointPathAsArrivedNotAsNoPath() {
        Grid w = new Grid(false);
        CompanionFollow.Trail t = new CompanionFollow.Trail();
        walkX(t, 0, 10, 0);
        walkX(t, 9, 0, 0);
        CompanionFollow.Planner pl = new CompanionFollow.Planner();
        // Vera innerhalb 1 Block vom Ziel: die Wegsuche liefert nur ihren eigenen Punkt.
        double[] next = pl.next(w, 6.5, 64, 0.5, 6.9, 64, 0.5, t, 100, 0);
        assertEquals("ziel", pl.mode());
        assertArrayEquals(new double[] {6.9, 64, 0.5}, next, 1.0e-9, "Ziel direkt, nicht die Spur entlang");
    }

    @Test
    void plannerUsesThePathAndGoesStraightWithoutPathOrTrail() {
        Grid w = new Grid(false);
        w.solids.add(Grid.k(3, 64, 0));
        w.solids.add(Grid.k(3, 65, 0));
        CompanionFollow.Planner pl = new CompanionFollow.Planner();
        double[] next = pl.next(w, 0.5, 64, 0.5, 8.5, 64, 0.5, null, 100, 0);
        assertEquals("weg", pl.mode());
        assertTrue(pl.waypoint() != null && next[0] >= 0.5);
        // Ziel außerhalb einer geschlossenen Kammer: kein Weg, keine Spur → geradeaus.
        Grid box = new Grid(true);
        box.tunnelX(0, 2, 0);
        CompanionFollow.Planner p2 = new CompanionFollow.Planner();
        p2.next(box, 0.5, 64, 0.5, 30.5, 64, 0.5, null, 100, 0);
        assertEquals("direkt", p2.mode());
        assertNull(p2.waypoint());
    }

    @Test
    void walkingHasHysteresis() {
        assertFalse(CompanionFollow.shouldWalk(false, 3.9, 2.5), "steht: erst ab 4,0 los");
        assertTrue(CompanionFollow.shouldWalk(false, 4.1, 2.5));
        assertTrue(CompanionFollow.shouldWalk(true, 3.2, 2.5), "läuft: erst ab 3,0 halten");
        assertFalse(CompanionFollow.shouldWalk(true, 2.9, 2.5));
    }

    @Test
    void headingIsTheWalkingDirection() {
        CompanionFollow.Trail t = new CompanionFollow.Trail();
        assertNull(t.heading());
        walkZ(t, 0, 0, 5);
        double[] h = t.heading();
        assertEquals(0, h[0], 1.0e-9);
        assertEquals(1, h[1], 1.0e-9);
    }

    @Test
    void lineClearSeesTheWallBetween() {
        Grid w = new Grid(false);
        w.solids.add(Grid.k(5, 65, 0));
        assertTrue(CompanionFollow.lineClear(w, 2.5, 64, 0.5, 4.5, 0.5));
        assertFalse(CompanionFollow.lineClear(w, 2.5, 64, 0.5, 7.5, 0.5), "Kopfhöhe zählt");
    }
}
