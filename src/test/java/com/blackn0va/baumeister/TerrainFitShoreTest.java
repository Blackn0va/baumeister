package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.blackn0va.baumeister.earth.HeightField;
import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.CityPlanner.Cell;
import com.blackn0va.baumeister.osm.CityPlanner.Section;

/** Küstenmarker des Weltbaus → Blöcke am Meeresspiegel (2026-11-01, Sydney: Cockle Bay lag 11,7 m hoch). */
class TerrainFitShoreTest {

    private static final int WATER = 0;
    private static final int SAND = 1;
    private static final int AIR = 2;
    private static final int STONE = 3;
    private static final TerrainFit.Shore SHORE = new TerrainFit.Shore(WATER, SAND, AIR, STONE, true);

    /** Höhenfeld mit fester Oberkante und Meeresspiegel. */
    private static HeightField field(int top, int sea) {
        return new HeightField() {
            @Override
            public int surfaceY(int x, int z) {
                return top;
            }

            @Override
            public int seaLevelY(int x, int z) {
                return sea;
            }
        };
    }

    private static Section one(int mat) {
        return new Section("Boden", 0, 0, List.of(new Cell(0, 0, 0, mat)), false, List.of());
    }

    /** y → Material der Säule. */
    private static Map<Integer, Integer> column(List<Cell> cells) {
        Map<Integer, Integer> out = new TreeMap<>();
        for (Cell c : cells) {
            out.put(c.y(), c.mat());
        }
        return out;
    }

    @Test
    void falseLandInTheHarbourIsCarvedDownToTheSea() {
        // Cockle Bay: Höhenmodell 11 über dem Meer, Meer 3 tief.
        List<Cell> cells = TerrainFit.fit(one(CityPlanner.SEA_MARK - 3), field(11, 0), 0, 0, SHORE);
        Map<Integer, Integer> col = column(cells);
        assertEquals(SAND, col.get(-3), "Meeresboden");
        for (int y = -2; y <= 0; y++) {
            assertEquals(WATER, col.get(y), "Wasser bis zum Meeresspiegel, y=" + y);
        }
        for (int y = 1; y <= 11; y++) {
            assertEquals(AIR, col.get(y), "falsches Land weg, y=" + y);
        }
        assertEquals(15, cells.size());
    }

    @Test
    void seaLevelFollowsTheReliefShift() {
        // Bezug verschoben: Meeresspiegel y = −40.
        Map<Integer, Integer> col = column(TerrainFit.fit(one(CityPlanner.SEA_MARK - 1), field(-38, -40), 0, 0, SHORE));
        assertEquals(SAND, col.get(-41));
        assertEquals(WATER, col.get(-40));
        assertEquals(AIR, col.get(-38));
        assertFalse(col.containsKey(0), "nichts auf y 0");
    }

    @Test
    void cliffAboveTheCarveLimitStays() {
        assertTrue(TerrainFit.fit(one(CityPlanner.SEA_MARK - 2), field(TerrainFit.MAX_CARVE + 1, 0), 0, 0, SHORE)
                .isEmpty(), "ein Kliff wird nicht zur Grube");
    }

    @Test
    void realSeabedBelowIsOnlyFilledWithWater() {
        Map<Integer, Integer> col = column(TerrainFit.fit(one(CityPlanner.SEA_MARK - 2), field(-20, 0), 0, 0, SHORE));
        assertEquals(20, col.size());
        assertTrue(col.values().stream().allMatch(m -> m == WATER));
        assertFalse(col.containsKey(-20), "der Boden selbst bleibt");
    }

    @Test
    void beachOnlyOnLowLandAndLowShoreIsRaised() {
        Map<Integer, Integer> low = column(TerrainFit.fit(one(CityPlanner.BEACH_MARK), field(2, 0), 0, 0, SHORE));
        assertEquals(Map.of(0, SAND, 1, SAND, 2, SAND), low);
        Map<Integer, Integer> under = column(TerrainFit.fit(one(CityPlanner.BEACH_MARK), field(-2, 0), 0, 0, SHORE));
        assertEquals(SAND, under.get(1), "laut Küstenlinie Land: bis 1 über das Meer aufgeschüttet");
        assertEquals(SAND, under.get(-4));
        assertTrue(TerrainFit.fit(one(CityPlanner.BEACH_MARK), field(10, 0), 0, 0, SHORE).isEmpty(), "Hang bleibt");
    }

    @Test
    void rejectedShoreDropsOnlyTheMarkers() {
        Section s = new Section("Boden", 0, 0, List.of(new Cell(0, 0, 0, CityPlanner.SEA_MARK - 2),
                new Cell(1, 0, 0, 7)), false, List.of());
        List<Cell> cells = TerrainFit.fit(s, field(5, 0), 0, 0, new TerrainFit.Shore(WATER, SAND, AIR, STONE, false));
        assertEquals(List.of(new Cell(1, 5, 0, 7)), cells, "die Wiese bleibt, das Meer fällt weg");
    }

    @Test
    void invertedMaskIsRejected() {
        assertTrue(TerrainFit.shoreTrusted(1000, 10, 200, 5), "Hafen wie Sydney: wenig hohes Meer");
        assertFalse(TerrainFit.shoreTrusted(1000, 600, 0, 0), "Stadt als Meer: zu viel hohes Meer");
        assertFalse(TerrainFit.shoreTrusted(0, 0, 100, 80), "Strand mitten im echten Meer");
        assertTrue(TerrainFit.shoreTrusted(10, 10, 5, 5), "zu wenige Säulen für ein Urteil");
    }

    @Test
    void pitAtTheShoreIsFilledAndTheMeadowSitsOnTop() {
        // Sydney 15067/5258: Gras auf y −200 an der Uferlinie. Grube aufschütten, die Wiese (Material 7) obenauf.
        Section s = new Section("Boden", 0, 0, List.of(new Cell(0, -1, 0, CityPlanner.FILL_MARK), new Cell(0, 0, 0, 7)),
                false, List.of());
        List<Cell> cells = TerrainFit.fit(s, field(-200, 0), 0, 0, SHORE);
        Map<Integer, Integer> col = column(cells);
        assertEquals(STONE, col.get(-199), "Grube gefüllt");
        assertEquals(STONE, col.get(0));
        assertEquals(7, col.get(1), "Wiese auf der neuen Oberkante, nicht auf dem Grubenboden");
        assertFalse(cells.stream().anyMatch(c -> c.mat() == 7 && c.y() < 0));
    }

    @Test
    void ordinaryShoreColumnIsNotFilled() {
        Section s = new Section("Boden", 0, 0, List.of(new Cell(0, -1, 0, CityPlanner.FILL_MARK), new Cell(0, 0, 0, 7)),
                false, List.of());
        assertEquals(List.of(new Cell(0, 2, 0, 7)), TerrainFit.fit(s, field(2, 0), 0, 0, SHORE));
    }

    @Test
    void depthGrowsFromTheShore() {
        assertEquals(1, CityPlanner.seaDepth(CityPlanner.SEA_MARK - 1));
        assertTrue(CityPlanner.isSeaMark(CityPlanner.SEA_MARK - CityPlanner.MAX_SEA_DEPTH));
        assertFalse(CityPlanner.isSeaMark(CityPlanner.BEACH_MARK));
        assertFalse(CityPlanner.isSeaMark(TerrainFit.PLINTH));
        assertFalse(CityPlanner.isSeaMark(CityPlanner.FILL_MARK));
    }
}
