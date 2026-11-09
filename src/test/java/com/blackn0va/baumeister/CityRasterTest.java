package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.CityPlanner.Cell;
import com.blackn0va.baumeister.osm.CityPlanner.Section;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Draufsicht einer Etappe für die Stadtansicht im Host (Schnittstelle /baumeister/v1/stadt, Version 1). */
class CityRasterTest {

    private static final List<String> PALETTE = List.of("air", "grass_block", "gray_concrete", "water",
            "oak_leaves[persistent=true]", "rail[shape=north_south]", "white_terracotta", "lantern[hanging=false]",
            "polished_andesite", "spruce_planks");

    private static CityPlanner.Plan plan(Section... s) {
        return new CityPlanner.Plan(PALETTE, List.of(s), 0, 0, 0, 0, 0, 0, 0, List.of());
    }

    private static byte at(CityRaster.Raster r, int worldX, int worldZ) {
        return r.cells()[(worldZ - r.z0()) / r.cell() * r.w() + (worldX - r.x0()) / r.cell()];
    }

    @Test
    void categoriesFollowTheBlockAndTheBuildingFlag() {
        Section ground = new Section("Boden", 0, 0, List.of(new Cell(0, 0, 0, 1), new Cell(1, 0, 0, 2),
                new Cell(2, 0, 0, 3), new Cell(3, 0, 0, 5), new Cell(4, 0, 0, 8), new Cell(5, 3, 0, 4),
                new Cell(6, 0, 0, 0)));
        Section house = new Section("Haus", 0, 0, List.of(new Cell(7, 1, 0, 6)), true);
        CityRaster.Raster r = CityRaster.of(plan(ground, house), 100, 200);
        assertEquals(100, r.x0());
        assertEquals(200, r.z0());
        assertEquals(1, r.cell());
        assertEquals(8, r.w());
        assertEquals(1, r.h());
        assertEquals(CityRaster.GREEN, at(r, 100, 200));
        assertEquals(CityRaster.ROAD, at(r, 101, 200));
        assertEquals(CityRaster.WATER, at(r, 102, 200));
        assertEquals(CityRaster.RAIL, at(r, 103, 200));
        assertEquals(CityRaster.PATH, at(r, 104, 200));
        assertEquals(CityRaster.TREE, at(r, 105, 200));
        assertEquals(CityRaster.EMPTY, at(r, 106, 200), "Luft bleibt leer");
        assertEquals(CityRaster.BUILDING, at(r, 107, 200));
    }

    @Test
    void higherRankWinsInsideOneCell() {
        // Laterne (sonstiges) über der Straße darf die Straße nicht überdecken; ein Gebäude schlägt alles.
        Section ground = new Section("Boden", 0, 0, List.of(new Cell(0, 0, 0, 2), new Cell(0, 1, 0, 7),
                new Cell(1, 0, 0, 1), new Cell(1, 1, 0, 4)));
        Section house = new Section("Haus", 0, 0, List.of(new Cell(2, 0, 0, 1)), true);
        Section grass = new Section("Boden", 0, 0, List.of(new Cell(2, 0, 0, 1)));
        CityRaster.Raster r = CityRaster.of(plan(ground, grass, house), 0, 0);
        assertEquals(CityRaster.ROAD, at(r, 0, 0));
        assertEquals(CityRaster.TREE, at(r, 1, 0), "Baum vor Wiese");
        assertEquals(CityRaster.BUILDING, at(r, 2, 0));
    }

    @Test
    void raisedDeckOverWaterIsABridgeButStreetLevelIsNot() {
        Section s = new Section("Boden", 0, 0, List.of(new Cell(0, -1, 0, 3), new Cell(0, 2, 0, 2),
                new Cell(1, 0, 0, 2), new Cell(2, -1, 0, 3), new Cell(2, 2, 0, 9)));
        CityRaster.Raster r = CityRaster.of(plan(s), 0, 0);
        assertEquals(CityRaster.BRIDGE, at(r, 0, 0));
        assertEquals(CityRaster.ROAD, at(r, 1, 0));
        assertEquals(CityRaster.BRIDGE, at(r, 2, 0), "Holzdeck über Wasser");
    }

    @Test
    void largeStagesGetCoarserCellsAndStayWithinTheLimit() {
        List<Cell> cells = new ArrayList<>();
        for (int x = 0; x < 1300; x += 7) {
            cells.add(new Cell(x, 0, x % 600, 1));
        }
        CityRaster.Raster r = CityRaster.of(plan(new Section("Boden", 0, 0, cells)), -500, 40);
        assertEquals(4, r.cell(), "1294 Blöcke breit → 4 je Zelle (höchstens 360 Zellen)");
        assertTrue(r.w() <= CityRaster.MAX_SIDE && r.h() <= CityRaster.MAX_SIDE);
        assertEquals(r.w() * r.h(), r.cells().length);
        assertEquals(CityRaster.GREEN, at(r, -500 + 14, 40 + 14));
    }

    @Test
    void emptyStageHasNoRaster() {
        assertNull(CityRaster.of(plan(new Section("Boden", 0, 0, List.of())), 0, 0));
    }

    @Test
    void plinthUnderHousesOnASlopeNeverBreaksTheRaster() {
        // Weltbau 2026-10-29: TerrainFit.PLINTH (−1) ließ CityRaster.of werfen, jede Etappe der Kachel blieb bei 0.
        Section house = new Section("Haus", 0, 0, List.of(new Cell(0, -2, 0, TerrainFit.PLINTH),
                new Cell(0, 0, 0, 6)), true);
        Section ground = new Section("Boden", 0, 0, List.of(new Cell(1, -1, 0, TerrainFit.PLINTH),
                new Cell(2, 0, 0, 999)));
        CityRaster.Raster r = CityRaster.of(plan(house, ground), 0, 0);
        assertEquals(CityRaster.BUILDING, at(r, 0, 0));
        assertEquals(CityRaster.OTHER, at(r, 1, 0));
        assertEquals(CityRaster.EMPTY, at(r, 2, 0), "Nummer außerhalb der Palette gilt als leer");
    }

    @Test
    void stagePrepBuildsEvenWhenTheRasterFails() {
        // Plan, an dem das Raster scheitern MUSS (null in der Palette → NullPointerException) – gebaut wird trotzdem.
        CityPlanner.Plan broken = new CityPlanner.Plan(java.util.Arrays.asList((String) null),
                List.of(new Section("Boden", 0, 0,
                List.of(new Cell(0, 0, 0, 0)))), 0, 0, 1, 0, 0, 0, 0, List.of());
        StagePrep.Prepared prepared = StagePrep.prepare(broken, 0, 0, true);
        assertEquals(1, prepared.plan().sections().size());
        assertNull(prepared.raster());
    }

    @Test
    void cellSizeGrowsWithTheTownSoTheHostLimitHolds() {
        // Eberdingen (GPU-PC 2026-10-29): 2449 Etappen bei 1 Block je Zelle ≈ 160 Mio. – der Host lehnte ab Etappe ~244
        // jede weitere mit 400 ab. Jetzt bleibt der ganze Ort unter CityRaster.JOB_CELLS.
        assertEquals(1, CityRaster.cellFor(1));
        assertEquals(1, CityRaster.cellFor(16));
        assertEquals(5, CityRaster.cellFor(2449));
        for (int stages : new int[] {1, 16, 122, 500, 2449, 3849, 10_000}) {
            int cell = CityRaster.cellFor(stages);
            long side = (256 + cell - 1) / cell;
            assertTrue(stages * side * side <= CityRaster.JOB_CELLS + stages * 2L * side, stages + " Etappen");
        }
    }
}
