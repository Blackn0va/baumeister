package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.earth.HeightField;
import com.blackn0va.baumeister.osm.CityModel;
import com.blackn0va.baumeister.osm.CityModel.P;
import com.blackn0va.baumeister.osm.CityModel.Road;
import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.CityPlanner.Cell;
import com.blackn0va.baumeister.osm.CityPlanner.Section;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Weltbau: Die Schilder einer Etappe kommen mit absoluter Höhe aus der Erdkachel – genau dort, wo TerrainFit den
 * Schild-Block hinsetzt. Ohne das stünden in der Erdwelt leere Schilder (SignWriter fände den Text nicht).
 */
class WorldTileSignsTest {

    private static final int BASE_X = 3584;
    private static final int BASE_Z = -1536;

    @Test
    void signsTravelWithTheStageAtTheirTerrainHeight() throws Exception {
        // Kreuzung in der ersten Etappe (−256 … 0).
        Road a = new Road(1, List.of(new P(-160, -100), new P(-100, -100), new P(-40, -100)), 6, "residential",
                "Bergstraße");
        Road b = new Road(2, List.of(new P(-100, -160), new P(-100, -100), new P(-100, -40)), 6, "residential",
                "Talweg");
        CityModel model = new CityModel("Erdkachel 3/-2", 0, 0, 800, List.of(), List.of(a, b), List.of(), List.of());
        HeightField ground = (x, z) -> 400 + Math.floorMod(x, 5);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            WorldTileSource src = new WorldTileSource("Erdkachel 3/-2", model, worker, 5_000_000, ground, BASE_X,
                    BASE_Z);
            CityPlanner.Plan plan = src.plan(0).get(30, TimeUnit.SECONDS);
            assertEquals(2, plan.signs().size(), "Schilder gingen beim Umrechnen verloren: " + plan.signs());
            for (CityPlanner.Sign s : plan.signs()) {
                int top = ground.surfaceY(BASE_X + s.x(), BASE_Z + s.z());
                assertEquals(top + 3, s.y(), "Schild nicht auf Geländehöhe: " + s);
                // Der Schild-Block liegt in den Zellen genau an dieser Stelle.
                boolean found = false;
                for (Section sec : plan.sections()) {
                    for (Cell c : sec.cells()) {
                        found |= c.x() == s.x() && c.z() == s.z() && c.y() == s.y()
                                && plan.palette().get(c.mat()).equals(s.block());
                    }
                }
                assertTrue(found, "kein Schild-Block an " + s);
            }
        } finally {
            worker.shutdownNow();
        }
    }
}
