package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Wegsuche: um Mauern, Stufe hoch, hinunter, Wasser, kein Weg, keine Ecken schneiden. Boden ist y=63, Füße y=64. */
class CompanionPathTest {

    /** Flache Welt: Boden bei y<=63, darüber Luft; solids/waters zusätzlich. */
    private static class Grid implements CompanionPath.Blocks {
        final Set<String> solids = new HashSet<>();
        final Set<String> waters = new HashSet<>();
        final Set<String> holes = new HashSet<>();

        static String k(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        void wall(int x, int z, int height) {
            for (int y = 64; y < 64 + height; y++) {
                solids.add(k(x, y, z));
            }
        }

        @Override
        public boolean solid(int x, int y, int z) {
            if (holes.contains(k(x, y, z)) || waters.contains(k(x, y, z))) {
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
            return waters.contains(k(x, y, z));
        }
    }

    private static final CompanionPath.Node START = new CompanionPath.Node(0, 64, 0);

    @Test
    void advanceSkipsReachedWaypointsButNotTheLast() {
        List<CompanionPath.Node> p = List.of(new CompanionPath.Node(0, 64, 0), new CompanionPath.Node(1, 64, 0),
                new CompanionPath.Node(2, 64, 0));
        assertEquals(1, CompanionPath.advance(p, 0, 0.5, 64, 0.5));
        assertEquals(2, CompanionPath.advance(p, 0, 1.6, 64, 0.5));
        assertEquals(2, CompanionPath.advance(p, 2, 2.5, 64, 0.5), "letzter bleibt Ziel");
        assertEquals(0, CompanionPath.advance(p, 0, 0.5, 67, 0.5), "andere Höhe zählt nicht als erreicht");
    }

    @Test
    void climbsALadderUpAWall() {
        // Mauer 5 hoch quer, Leiter davor bei x=2 – oben auf der Mauer (y=69) ist das Ziel.
        Grid g = new Grid() {
            @Override
            public boolean climbable(int x, int y, int z) {
                return x == 2 && z == 0 && y >= 64 && y <= 68;
            }
        };
        for (int z = -30; z <= 30; z++) {
            g.wall(3, z, 5);
        }
        List<CompanionPath.Node> p = CompanionPath.find(g, START, new CompanionPath.Node(3, 69, 0));
        assertFalse(p.isEmpty(), "über die Leiter");
        assertTrue(p.contains(new CompanionPath.Node(2, 66, 0)), "senkrecht an der Leiter: " + p);
    }

    @Test
    void walksANarrowWindingTunnel() {
        // Tunnel 1 breit, 2 hoch, mit zwei Kurven, ringsum Stein (Höhle).
        java.util.Set<String> air = new java.util.HashSet<>();
        int[][] cells = {{0, 0}, {1, 0}, {2, 0}, {2, 1}, {2, 2}, {3, 2}, {4, 2}, {4, 1}, {4, 0}, {5, 0}, {6, 0}};
        for (int[] c : cells) {
            air.add(c[0] + "," + c[1]);
        }
        CompanionPath.Blocks cave = new CompanionPath.Blocks() {
            @Override
            public boolean solid(int x, int y, int z) {
                return !passable(x, y, z);
            }

            @Override
            public boolean passable(int x, int y, int z) {
                return (y == 64 || y == 65) && air.contains(x + "," + z);
            }

            @Override
            public boolean water(int x, int y, int z) {
                return false;
            }
        };
        List<CompanionPath.Node> p = CompanionPath.find(cave, START, new CompanionPath.Node(6, 64, 0));
        assertFalse(p.isEmpty(), "durch die Höhle");
        for (CompanionPath.Node n : p) {
            assertTrue(air.contains(n.x() + "," + n.z()), "nur im Tunnel: " + p);
        }
    }

    @Test
    void straightLineOnFlatGround() {
        List<CompanionPath.Node> p = CompanionPath.find(new Grid(), START, new CompanionPath.Node(6, 64, 0));
        assertFalse(p.isEmpty());
        assertTrue(p.size() <= 7, p.toString());
    }

    @Test
    void walksAroundAHighWall() {
        Grid g = new Grid();
        // Mauer quer von z=-5 bis 5 bei x=3, 4 hoch – wie im Parcours, vor dem sie früher nach 2 s teleportierte.
        for (int z = -5; z <= 5; z++) {
            g.wall(3, z, 4);
        }
        List<CompanionPath.Node> p = CompanionPath.find(g, START, new CompanionPath.Node(6, 64, 0));
        assertFalse(p.isEmpty());
        assertTrue(p.stream().anyMatch(n -> Math.abs(n.z()) >= 6), "um die Mauer herum: " + p);
        assertTrue(p.stream().noneMatch(n -> n.x() == 3 && Math.abs(n.z()) <= 5), "nicht durch die Mauer");
    }

    @Test
    void climbsOneBlockButNotTwo() {
        Grid one = new Grid();
        for (int z = -30; z <= 30; z++) {
            one.wall(3, z, 1);
        }
        List<CompanionPath.Node> up = CompanionPath.find(one, START, new CompanionPath.Node(6, 64, 0));
        assertTrue(up.stream().anyMatch(n -> n.y() == 65), "auf den Block gesprungen: " + up);
        Grid two = new Grid();
        for (int z = -30; z <= 30; z++) {
            two.wall(3, z, 2);
        }
        assertTrue(CompanionPath.find(two, START, new CompanionPath.Node(6, 64, 0)).isEmpty(),
                "2 hoch ohne Lücke in Reichweite: kein Weg (dann Teleport)");
    }

    @Test
    void swimsAcrossAPool() {
        Grid g = new Grid();
        for (int x = 2; x <= 4; x++) {
            for (int z = -1; z <= 1; z++) {
                g.holes.add(Grid.k(x, 63, z));
                g.waters.add(Grid.k(x, 62, z));
                g.waters.add(Grid.k(x, 63, z));
                g.holes.add(Grid.k(x, 62, z));
            }
        }
        // Über das Becken (3 breit, also nicht zu überspringen): an der Oberfläche schwimmen – so treibt sie laut
        // Messung (probes/mannequin, Auftrieb +0,04).
        List<CompanionPath.Node> p = CompanionPath.find(g, START, new CompanionPath.Node(6, 64, 0));
        assertFalse(p.isEmpty(), "über das Wasser");
        assertTrue(p.stream().anyMatch(n -> n.x() == 3 && n.y() == 64), "an der Oberfläche: " + p);
    }

    @Test
    void doesNotCutCorners() {
        Grid g = new Grid();
        g.wall(1, 0, 2);
        g.wall(0, 1, 2);
        // Ziel schräg hinter zwei Blöcken: schräg zwischen ihnen durch wäre durch eine Ecke.
        List<CompanionPath.Node> p = CompanionPath.find(g, START, new CompanionPath.Node(2, 64, 2));
        assertFalse(p.isEmpty());
        assertFalse(p.contains(new CompanionPath.Node(1, 64, 1)) && p.indexOf(new CompanionPath.Node(1, 64, 1)) == 1,
                "nicht diagonal durch die Ecke: " + p);
    }

    @Test
    void lavaAndFarGoalsGiveNoPath() {
        Grid g = new Grid() {
            @Override
            public boolean passable(int x, int y, int z) {
                return x != 3 && super.passable(x, y, z);
            }
        };
        assertTrue(CompanionPath.find(g, START, new CompanionPath.Node(6, 64, 0)).isEmpty());
        assertTrue(CompanionPath.find(new Grid(), START, new CompanionPath.Node(60, 64, 0)).isEmpty(), "zu weit");
        assertEquals(List.of(START), CompanionPath.find(new Grid(), START, START));
    }
}
