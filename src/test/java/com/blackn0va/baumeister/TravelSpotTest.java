package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.osm.CityPlanner.Section;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Reisepunkt einer Stadt (dev01 2026-10-30, Eberdingen: Nominatim-Mitte im Acker, Dörfer 1,4–1,8 km entfernt).
 */
class TravelSpotTest {

    private static Section house(int x, int z) {
        return new Section("Haus", x, z, List.of(), true, List.of());
    }

    private static Section ground(int x, int z) {
        return new Section("Boden", x, z, List.of());
    }

    private static List<Section> stage(Section... s) {
        return new ArrayList<>(List.of(s));
    }

    @Test
    void spotMovesToTheStageWithTheMostBuildings() {
        TravelSpot t = new TravelSpot(0);
        assertTrue(t.offer(stage(ground(0, 0), ground(32, 0))).isEmpty(), "Acker ohne Gebäude: Punkt bleibt");
        TravelSpot.Spot village = t.offer(stage(ground(900, 900), house(-1000, 1200), house(-1100, 1300),
                house(-1060, 1240))).orElseThrow();
        assertEquals(new TravelSpot.Spot(-1053, 1247, 3), village, "Schwerpunkt der Gebäude, nicht des Bodens");
        assertTrue(t.offer(stage(house(500, 500), house(520, 500))).isEmpty(), "Weiler mit 2 schlägt Dorf mit 3 nicht");
        TravelSpot.Spot core = t.offer(stage(house(0, 0), house(10, 0), house(20, 0), house(30, 0))).orElseThrow();
        assertEquals(4, core.buildings());
        assertEquals(4, t.best());
    }

    @Test
    void afterARestartOnlyABetterStageMovesTheSpot() {
        TravelSpot t = new TravelSpot(10);
        assertTrue(t.offer(stage(house(1, 1), house(2, 2), house(3, 3))).isEmpty(),
                "der Ortskern von vor dem Neustart bleibt Reisepunkt");
    }
}
