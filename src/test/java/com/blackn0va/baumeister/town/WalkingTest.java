package com.blackn0va.baumeister.town;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.companion.CompanionPath;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Laufen und Ziele (Vertrag v013, AK-42), die reinen Regeln: Zuruf (Request), Folgen (Follow), Treffpunkte
 * (Gathering), Feststecken (Unstuck), sicherer Boden (SafeGround) und der Restweg (Route.remaining). Den echten Takt
 * prüft TownLifeTest.
 */
class WalkingTest {

    // ---------------------------------------------------------------- Zuruf

    @Test
    void followAndStayAreRecognisedOnlyAsClearCommands() {
        assertEquals(Request.Kind.FOLLOW, Request.parse("komm mit"));
        assertEquals(Request.Kind.FOLLOW, Request.parse("Folge mir!"));
        assertEquals(Request.Kind.FOLLOW, Request.parse("bitte komm mit mir."));
        assertEquals(Request.Kind.STAY, Request.parse("bleib hier"));
        assertEquals(Request.Kind.STAY, Request.parse("Hör auf"));
        assertEquals(Request.Kind.HOME, Request.parse("geh nach Hause"));
        assertEquals(Request.Kind.HOME, Request.parse("geh heim!"));
        // Fragen und längere Sätze bleiben beim Gespräch.
        assertEquals(Request.Kind.NONE, Request.parse("kommst du mit zum Markt?"));
        assertEquals(Request.Kind.NONE, Request.parse("komm mittag vorbei"));
        assertEquals(Request.Kind.NONE, Request.parse("folgende Frage: wie alt bist du"));
        assertEquals(Request.Kind.NONE, Request.parse("ich bleibe hier, und du?"));
        assertEquals(Request.Kind.NONE, Request.parse(""));
        assertEquals(Request.Kind.NONE, Request.parse(null));
        assertFalse(Request.reply(Request.Kind.FOLLOW, 7).isBlank());
    }

    // ---------------------------------------------------------------- Folgen

    @Test
    void followEndsAfterMaxTimeWorldChangeOfflineOrDistance() {
        UUID p = UUID.randomUUID();
        Follow f = new Follow();
        assertEquals(Follow.End.NONE, f.check(true, "welt", 5, 0));
        f.start(p, "welt", 1000);
        assertTrue(f.active());
        assertEquals(Follow.End.NONE, f.check(true, "welt", 5, 1000 + Follow.MAX_MS - 1));
        assertEquals(Follow.End.TIMEOUT, f.check(true, "welt", 5, 1000 + Follow.MAX_MS));
        assertEquals(Follow.End.WORLD, f.check(true, "welt_nether", 5, 2000));
        assertEquals(Follow.End.OFFLINE, f.check(false, null, 0, 2000));
        assertEquals(Follow.End.LOST, f.check(true, "welt", Follow.LOSE + 1, 2000));
        f.stop();
        assertFalse(f.active());
    }

    @Test
    void followPointKeepsDistanceOnTheResidentsSide() {
        double[] b = Follow.behind(0, 0, 10, 0);
        assertEquals(Follow.KEEP, b[0], 1e-9);
        assertEquals(0, b[1], 1e-9);
        assertTrue(Follow.close(Follow.KEEP));
        assertFalse(Follow.close(Follow.KEEP + 2));
    }

    // ---------------------------------------------------------------- Treffpunkte

    @Test
    void groupsHaveTwoToFourMembersFromNearbyResidents() {
        List<Gathering.Member> free = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            free.add(new Gathering.Member(i, i * 2.0, 0));
        }
        // Weit weg: kommt in keine Gruppe mit den anderen.
        free.add(new Gathering.Member(99, 500, 500));
        for (int pick = 0; pick < 6; pick++) {
            List<List<Integer>> groups = Gathering.groups(free, pick);
            Set<Integer> seen = new HashSet<>();
            assertFalse(groups.isEmpty());
            for (List<Integer> g : groups) {
                assertTrue(g.size() >= Gathering.MIN && g.size() <= Gathering.MAX, g.toString());
                assertFalse(g.contains(99), "Fernster in einer Gruppe: " + g);
                for (int id : g) {
                    assertTrue(seen.add(id), "doppelt: " + id);
                }
            }
        }
        assertTrue(Gathering.groups(List.of(new Gathering.Member(1, 0, 0)), 0).isEmpty(), "allein keine Gruppe");
    }

    @Test
    void groupAssemblesWhenAllArriveOrAfterWaitingWithTwo() {
        assertFalse(Gathering.assembled(1, 3, Gathering.WAIT_MS * 2));
        assertFalse(Gathering.assembled(2, 3, Gathering.WAIT_MS - 1));
        assertTrue(Gathering.assembled(2, 3, Gathering.WAIT_MS));
        assertTrue(Gathering.assembled(3, 3, 0));
        double[] a = Gathering.slot(0, 0, 0, 4);
        double[] b = Gathering.slot(0, 0, 2, 4);
        assertEquals(2 * Gathering.SLOT, Math.hypot(a[0] - b[0], a[1] - b[1]), 1e-9, "gegenüber im Kreis");
        long s = Gathering.stay(500);
        assertTrue(s >= Gathering.STAY_MIN && s <= Gathering.STAY_MAX);
    }

    // ---------------------------------------------------------------- Feststecken

    @Test
    void stuckResidentTeleportsOnlyLastAndNeverWhenWatched() {
        Unstuck u = new Unstuck();
        assertEquals(Unstuck.Action.REPLAN, u.next(false));
        assertEquals(Unstuck.Action.SIDESTEP, u.next(false));
        assertEquals(Unstuck.Action.DETOUR, u.next(false));
        assertEquals(Unstuck.Action.TELEPORT, u.next(false));
        // Gesehen: nie Teleport, auch nach mehreren Runden.
        for (int round = 0; round < 3; round++) {
            assertEquals(Unstuck.Action.REPLAN, u.next(true));
            assertEquals(Unstuck.Action.SIDESTEP, u.next(true));
            assertEquals(Unstuck.Action.DETOUR, u.next(true));
            assertEquals(Unstuck.Action.PAUSE, u.next(true));
        }
        u.next(true);
        u.progressed();
        assertEquals(Unstuck.Action.REPLAN, u.next(true), "nach Fortschritt von vorn");
    }

    // ---------------------------------------------------------------- Boden

    /** Boden y≤0; Wasser bei x=3, Lava bei x=-3, Grube 6 tief bei z=3, Stufe bei z=-3. */
    static final CompanionPath.Blocks TERRAIN = new CompanionPath.Blocks() {
        @Override
        public boolean solid(int x, int y, int z) {
            if (z == 3) {
                return y <= -6;
            }
            if (x == 3 || x == -3) {
                return y <= -1;
            }
            return y <= 0 || z == -3 && y == 1;
        }

        @Override
        public boolean passable(int x, int y, int z) {
            if (x == -3 && y == 0) {
                return false;
            }
            return !solid(x, y, z);
        }

        @Override
        public boolean water(int x, int y, int z) {
            return x == 3 && y == 0;
        }
    };

    @Test
    void residentDoesNotStepIntoWaterLavaOrDeepDrop() {
        assertFalse(SafeGround.safeAhead(TERRAIN, 2.5, 1, 0.5, 1, 0), "Wasser");
        assertFalse(SafeGround.safeAhead(TERRAIN, -1.5, 1, 0.5, -1, 0), "Lava");
        assertFalse(SafeGround.safeAhead(TERRAIN, 0.5, 1, 2.5, 0, 1), "6 tief");
        assertTrue(SafeGround.safeAhead(TERRAIN, 0.5, 1, -2.5, 0, -1), "Stufe hoch ist erlaubt");
        assertTrue(SafeGround.safeAhead(TERRAIN, 0.5, 1, 0.5, 1, 1), "ebener Boden");
    }

    @Test
    void dryWorldHasNoPathThroughWater() {
        // Wasserrinne bei x=5, 1 tief, von z=-30 bis 30: Vera schwimmt hindurch, der Bewohner findet keinen Weg.
        CompanionPath.Blocks river = new CompanionPath.Blocks() {
            @Override
            public boolean solid(int x, int y, int z) {
                return x == 5 ? y <= -1 : y <= 0;
            }

            @Override
            public boolean passable(int x, int y, int z) {
                return !solid(x, y, z);
            }

            @Override
            public boolean water(int x, int y, int z) {
                return x == 5 && y == 0;
            }
        };
        CompanionPath.Node from = new CompanionPath.Node(0, 1, 0);
        CompanionPath.Node to = new CompanionPath.Node(10, 1, 0);
        assertFalse(CompanionPath.find(river, from, to).isEmpty(), "Vera schwimmt");
        assertTrue(CompanionPath.find(SafeGround.dry(river), from, to).isEmpty(), "Bewohner schwimmt");
    }

    @Test
    void sidestepFindsFreeGroundBesides() {
        double[] s = SafeGround.sidestep(TERRAIN, 0.5, 1, 0.5, 1, 0, true);
        assertNotNull(s);
        assertEquals(0.5, s[0], 1e-9, "seitlich, nicht vorwärts");
        assertTrue(Math.abs(s[2] - 0.5) >= 2, "mindestens 2 Blöcke zur Seite");
        CompanionPath.Blocks walled = new CompanionPath.Blocks() {
            @Override
            public boolean solid(int x, int y, int z) {
                return y <= 0 || z != 0;
            }

            @Override
            public boolean passable(int x, int y, int z) {
                return !solid(x, y, z);
            }

            @Override
            public boolean water(int x, int y, int z) {
                return false;
            }
        };
        assertNull(SafeGround.sidestep(walled, 0.5, 1, 0.5, 1, 0, true), "im Gang kein Ausweichplatz");
    }

    @Test
    void remainingFollowsThePathNotTheBeeline() {
        // Mauer bei x=2, z -3..3: Der Weg geht außen herum, Restweg > Luftlinie.
        CompanionPath.Blocks wall = new CompanionPath.Blocks() {
            private boolean w(int x, int y, int z) {
                return x == 2 && z >= -3 && z <= 3 && y >= 1 && y <= 3;
            }

            @Override
            public boolean solid(int x, int y, int z) {
                return y <= 0 || w(x, y, z);
            }

            @Override
            public boolean passable(int x, int y, int z) {
                return y >= 1 && !w(x, y, z);
            }

            @Override
            public boolean water(int x, int y, int z) {
                return false;
            }
        };
        Route r = new Route();
        assertTrue(Double.isNaN(r.remaining(0.5, 1, 0.5)), "ohne Weg kein Restweg");
        r.next(wall, 0.5, 1, 0.5, 4.5, 1, 0.5, 0, 0, true);
        assertTrue(r.hasPath());
        double rest = r.remaining(0.5, 1, 0.5);
        assertTrue(rest > 4.0 + 2, "Restweg " + rest + " misst den Umweg");
    }
}
