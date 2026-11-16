package com.blackn0va.baumeister.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Alle leeren Rahmen eines Bereichs finden und ordnen (/bm portal alle, 2026-11-16). */
class PortalScanTest {

    @Test
    void everyValidOpeningIsFoundOnceAndABrokenOneNot() {
        PortalFrameTest.Grid g = new PortalFrameTest.Grid()
                .frame(PortalFrame.Axis.Z, 5, 65, 10, 3, 4, true)
                .frame(PortalFrame.Axis.X, -2, 64, 20, 5, 6, false)
                .frame(PortalFrame.Axis.Z, -8, 65, 0, 3, 4, true);
        g.obsidian.remove(PortalFrameTest.Grid.k(-8, 67, 3));  // dritter Rahmen mit Lücke
        List<PortalFrame.Opening> found = PortalScan.openings(g, -20, 60, -20, 20, 75, 30);
        assertEquals(2, found.size(), found.toString());
        assertEquals(List.of(new PortalFrame.Opening(PortalFrame.Axis.Z, 5, 65, 10, 3, 4),
                new PortalFrame.Opening(PortalFrame.Axis.X, -2, 64, 20, 5, 6)), found.stream()
                        .sorted(java.util.Comparator.comparingInt(PortalFrame.Opening::z0)).toList());
    }

    @Test
    void largeOpeningsComeFirstThenClockwiseFromTheView() {
        PortalFrame.Opening big = new PortalFrame.Opening(PortalFrame.Axis.X, -2, 64, -30, 5, 6);   // im Norden
        PortalFrame.Opening west = new PortalFrame.Opening(PortalFrame.Axis.Z, -20, 64, -1, 3, 4);
        PortalFrame.Opening south = new PortalFrame.Opening(PortalFrame.Axis.X, -1, 64, 20, 3, 4);
        PortalFrame.Opening east = new PortalFrame.Opening(PortalFrame.Axis.Z, 20, 64, -1, 3, 4);
        // Blick nach Süden (yaw 0): rechts herum Süden, Westen, (Norden), Osten – das große Portal zuerst.
        assertEquals(List.of(big, south, west, east), PortalScan.order(List.of(east, west, big, south), 0.5, 0.5, 0));
        // Blick nach Westen (yaw 90): Westen, Norden, Osten, Süden.
        assertEquals(List.of(big, west, east, south), PortalScan.order(List.of(east, south, west, big), 0.5, 0.5, 90));
    }

    @Test
    void clockwiseFollowsMinecraftYaw() {
        PortalFrame.Opening south = new PortalFrame.Opening(PortalFrame.Axis.X, -1, 64, 20, 3, 4);
        PortalFrame.Opening west = new PortalFrame.Opening(PortalFrame.Axis.Z, -20, 64, -1, 3, 4);
        assertEquals(0, PortalScan.clockwise(south, 0.5, 0.5, 0), 1);
        assertEquals(90, PortalScan.clockwise(west, 0.5, 0.5, 0), 1);
        assertEquals(270, PortalScan.clockwise(south, 0.5, 0.5, 90), 1);
    }
}
