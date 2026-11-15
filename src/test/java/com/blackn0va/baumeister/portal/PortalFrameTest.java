package com.blackn0va.baumeister.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Rahmenerkennung nach den Regeln von Minecraft (PortalShape), ohne Server. */
class PortalFrameTest {

    /** Unter y 64 Stein, darüber Luft, dazu gesetzte Obsidian- und Steinblöcke. */
    static final class Grid implements PortalFrame.Blocks {
        final Set<String> obsidian = new HashSet<>();
        final Set<String> stone = new HashSet<>();

        static String k(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        @Override
        public boolean frame(int x, int y, int z) {
            return obsidian.contains(k(x, y, z));
        }

        @Override
        public boolean open(int x, int y, int z) {
            return y >= 64 && !obsidian.contains(k(x, y, z)) && !stone.contains(k(x, y, z));
        }

        /** Rahmen um eine Öffnung width × height ab (x0, y0, z0) entlang axis. */
        Grid frame(PortalFrame.Axis axis, int x0, int y0, int z0, int width, int height, boolean corners) {
            for (int a = -1; a <= width; a++) {
                for (int y = -1; y <= height; y++) {
                    boolean edge = a == -1 || a == width || y == -1 || y == height;
                    boolean corner = (a == -1 || a == width) && (y == -1 || y == height);
                    if (edge && (corners || !corner)) {
                        obsidian.add(axis == PortalFrame.Axis.X ? k(x0 + a, y0 + y, z0) : k(x0, y0 + y, z0 + a));
                    }
                }
            }
            return this;
        }
    }

    @Test
    void aThreeByFourOpeningAlongZIsFoundFromInside() {
        Grid g = new Grid().frame(PortalFrame.Axis.Z, 5, 65, 10, 3, 4, true);
        PortalFrame.Result r = PortalFrame.at(g, 5, 65, 11);
        assertTrue(r.found(), r.reason());
        assertEquals(new PortalFrame.Opening(PortalFrame.Axis.Z, 5, 65, 10, 3, 4), r.opening());
        assertEquals(12, r.opening().cells().size());
        assertTrue(r.opening().contains(5, 68, 12));
        assertFalse(r.opening().contains(5, 69, 12), "oberste Rahmenreihe gehört nicht dazu");
        assertFalse(r.opening().contains(6, 65, 11), "nur die eine Ebene");
    }

    @Test
    void aFiveBySixOpeningAlongXIsFoundFromAnyCell() {
        Grid g = new Grid().frame(PortalFrame.Axis.X, -2, 64, 7, 5, 6, true);
        for (int x = -2; x <= 2; x++) {
            for (int y = 64; y <= 69; y++) {
                PortalFrame.Result r = PortalFrame.at(g, x, y, 7);
                assertTrue(r.found(), x + "," + y + ": " + r.reason());
                assertEquals(new PortalFrame.Opening(PortalFrame.Axis.X, -2, 64, 7, 5, 6), r.opening());
            }
        }
    }

    @Test
    void cornersAreNotNeededLikeInMinecraft() {
        Grid g = new Grid().frame(PortalFrame.Axis.Z, 0, 65, 0, 2, 3, false);
        assertTrue(PortalFrame.at(g, 0, 66, 1).found());
    }

    @Test
    void aGapInTheFrameLeavesItOpen() {
        Grid g = new Grid().frame(PortalFrame.Axis.Z, 5, 65, 10, 3, 4, true);
        g.obsidian.remove(Grid.k(5, 66, 13));
        PortalFrame.Result r = PortalFrame.at(g, 5, 66, 11);
        assertFalse(r.found());
        assertTrue(r.reason().contains("offen"), r.reason());
    }

    @Test
    void stoneInsteadOfObsidianDoesNotHoldAPortal() {
        Grid g = new Grid().frame(PortalFrame.Axis.Z, 5, 65, 10, 3, 4, true);
        g.obsidian.remove(Grid.k(5, 69, 11));
        g.stone.add(Grid.k(5, 69, 11));
        PortalFrame.Result r = PortalFrame.at(g, 5, 66, 11);
        assertFalse(r.found());
        assertTrue(r.reason().contains("5 69 11"), r.reason());
    }

    @Test
    void oneBlockWideIsTooNarrow() {
        Grid g = new Grid().frame(PortalFrame.Axis.Z, 5, 65, 10, 1, 4, true);
        PortalFrame.Result r = PortalFrame.at(g, 5, 66, 10);
        assertFalse(r.found());
        assertTrue(r.reason().contains("zu klein"), r.reason());
    }

    @Test
    void anLShapedOpeningIsNoPortal() {
        Grid g = new Grid().frame(PortalFrame.Axis.Z, 5, 65, 10, 3, 4, true);
        g.obsidian.add(Grid.k(5, 68, 12));
        PortalFrame.Result r = PortalFrame.at(g, 5, 65, 10);
        assertFalse(r.found());
        assertTrue(r.reason().contains("nicht rechteckig"), r.reason());
    }

    @Test
    void twentyTwoWideIsTooLarge() {
        Grid g = new Grid().frame(PortalFrame.Axis.X, 0, 65, 0, 22, 3, true);
        PortalFrame.Result r = PortalFrame.at(g, 10, 66, 0);
        assertFalse(r.found());
        assertTrue(r.reason().contains("größer"), r.reason());
    }

    @Test
    void lookingThroughTheOpeningFromTheHallFindsIt() {
        Grid g = new Grid().frame(PortalFrame.Axis.Z, 5, 65, 10, 3, 4, true);
        // Er steht 4 Blöcke vor dem Rahmen (x 9), Augenhöhe 65 + 1,62, und schaut nach −x leicht nach unten.
        PortalFrame.Result r = PortalFrame.along(g, 9.5, 66.62, 11.5, -1, -0.1, 0, 8);
        assertTrue(r.found(), r.reason());
        assertEquals(5, r.opening().x0());
    }

    @Test
    void aWallStopsTheLook() {
        Grid g = new Grid().frame(PortalFrame.Axis.Z, 5, 65, 10, 3, 4, true);
        for (int y = 64; y <= 70; y++) {
            for (int z = 8; z <= 14; z++) {
                g.stone.add(Grid.k(7, y, z));
            }
        }
        assertFalse(PortalFrame.along(g, 9.5, 66.62, 11.5, -1, 0, 0, 8).found());
    }

    @Test
    void lookingAtTheFrameItselfSaysWhereToLook() {
        Grid g = new Grid().frame(PortalFrame.Axis.Z, 5, 65, 10, 3, 4, true);
        PortalFrame.Result r = PortalFrame.along(g, 9.5, 66.62, 9.5, -1, 0, 0, 8);
        assertFalse(r.found());
        assertTrue(r.reason().contains("schau durch die Öffnung"), r.reason());
    }

    @Test
    void openAirAloneIsNoFrame() {
        PortalFrame.Result r = PortalFrame.at(new Grid(), 0, 70, 0);
        assertFalse(r.found());
        assertTrue(r.reason().contains("offen"), r.reason());
    }
}
