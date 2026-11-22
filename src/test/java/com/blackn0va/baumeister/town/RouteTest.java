package com.blackn0va.baumeister.town;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.companion.CompanionPath;
import com.blackn0va.baumeister.companion.CompanionWalk;

import org.junit.jupiter.api.Test;

/**
 * Stadtleben: Bewohner erreichen Ziele ihres Tagesablaufs (Vertrag v008, AK-23) – über Teilstücke (Route.leg) und um
 * eine Mauer herum, mit gedrosselter Wegsuche (AK-25). Ebene ohne Höhen: Die Physik (Sprung, Schwerkraft) prüfen
 * CompanionWalkTest und die Live-Probe; hier geht es um Zielwahl und Drossel.
 */
class RouteTest {

    /** Boden y=0, Füße auf y=1; Mauer bei x=20 von z=-10 bis z=10, 3 hoch. */
    private static final CompanionPath.Blocks WALL = new CompanionPath.Blocks() {
        private boolean wall(int x, int y, int z) {
            return x == 20 && z >= -10 && z <= 10 && y >= 1 && y <= 3;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return y <= 0 || wall(x, y, z);
        }

        @Override
        public boolean passable(int x, int y, int z) {
            return y >= 1 && !wall(x, y, z);
        }

        @Override
        public boolean water(int x, int y, int z) {
            return false;
        }
    };

    @Test
    void legIsAtMostFourteenBlocks() {
        assertArrayEquals(new double[] {14, 0}, Route.leg(0, 0, 40, 0), 1e-9);
        assertArrayEquals(new double[] {5, 5}, Route.leg(0, 0, 5, 5), 1e-9);
        assertTrue(Route.LEG < 24, "unter CompanionPath.RANGE");
    }

    @Test
    void residentReachesAFarGoalBehindAWall() {
        double[] r = walk(new double[] {0.5, 1, 0.5}, new double[] {40.5, 1, 0.5}, 1, 2000);
        assertTrue(r[3] < TownLifeArrive.ARRIVE, "Ziel nicht erreicht, Abstand " + r[3]);
        assertEquals(0, (int) r[4], "Teleports");
    }

    @Test
    void withoutPlanPermitTheRouteDoesNotSearch() {
        Route route = new Route();
        route.next(WALL, 0.5, 1, 0.5, 10.5, 1, 0.5, 0, 0, false);
        assertEquals(0, route.searches());
        assertTrue(route.wantsPlan(10.5, 1, 0.5, 1, 0));
        route.next(WALL, 0.5, 1, 0.5, 10.5, 1, 0.5, 1, 0, true);
        assertEquals(1, route.searches());
        // Gleiches Ziel, frischer Weg: keine neue Suche.
        assertFalse(route.wantsPlan(10.5, 1, 0.5, 2, 0));
        assertTrue(route.wantsPlan(10.5, 1, 0.5, 1 + Route.MIN_GAP, 10), "festgehangen → neu planen");
    }

    /**
     * Paper-Probe 2026-11-21: Ein festhängender Bewohner suchte ~400 Mal je Minute. Jetzt höchstens eine Suche je
     * MIN_GAP Ticks, auch wenn er festhängt und das Ziel sich bewegt – außer nach reset().
     */
    @Test
    void searchesAreSpacedEvenWhenStuck() {
        Route route = new Route();
        int searches = 0;
        for (int t = 0; t < 1200; t++) {
            double gx = 10.5 + (t % 2) * 3;
            boolean plan = route.wantsPlan(gx, 1, 0.5, t, 50);
            route.next(WALL, 0.5, 1, 0.5, gx, 1, 0.5, t, 50, plan);
            searches = route.searches();
        }
        assertTrue(searches <= 1200 / Route.MIN_GAP + 1, "Suchen in 60 s: " + searches);
        route.reset();
        assertTrue(route.wantsPlan(10.5, 1, 0.5, 1200, 0), "nach reset sofort");
    }

    /**
     * Einfacher Lauf: Position += Geschwindigkeit, Höhe fest. plansEvery = Planerlaubnis alle n Ticks. Liefert
     * {x, y, z, Restabstand, Teleports, Suchen}.
     */
    static double[] walk(double[] start, double[] goal, int plansEvery, int ticks) {
        Route route = new Route();
        CompanionWalk.State state = new CompanionWalk.State();
        double x = start[0];
        double z = start[2];
        double[] leg = null;
        int teleports = 0;
        for (int t = 0; t < ticks; t++) {
            double rest = Math.hypot(goal[0] - x, goal[2] - z);
            if (rest < TownLifeArrive.ARRIVE) {
                break;
            }
            if (leg == null || Math.hypot(leg[0] - x, leg[1] - z) < 2.0 && (leg[0] != goal[0] || leg[1] != goal[2])) {
                leg = Route.leg(x, z, goal[0], goal[2]);
            }
            double[] w = route.next(WALL, x, 1, z, leg[0], 1, leg[1], t, state.stuck(), t % plansEvery == 0);
            CompanionWalk.Step s = CompanionWalk.next(state, new CompanionWalk.Body(x, 1, z, 0, true, false), w[0], 1,
                    w[2], 0.16);
            if (s.teleport()) {
                teleports++;
                x = leg[0];
                z = leg[1];
                route.reset();
                continue;
            }
            double nx = x + s.vx();
            double nz = z + s.vz();
            // Mauer: Bewegung in die Mauer hinein bleibt stehen (Kollision).
            if (!WALL.passable((int) Math.floor(nx), 1, (int) Math.floor(nz))) {
                continue;
            }
            x = nx;
            z = nz;
        }
        return new double[] {x, 1, z, Math.hypot(goal[0] - x, goal[2] - z), teleports, route.searches()};
    }

    /** ARRIVE aus TownLife (Wurzelpaket, paketweit) – hier gespiegelt. */
    static final class TownLifeArrive {
        static final double ARRIVE = 1.2;
    }
}
