package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.earth.HeightField;
import com.blackn0va.baumeister.osm.CityModel;
import com.blackn0va.baumeister.osm.CityModel.Building;
import com.blackn0va.baumeister.osm.CityModel.P;
import com.blackn0va.baumeister.osm.CityModel.Road;
import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.CityPlanner.Cell;
import com.blackn0va.baumeister.osm.CityPlanner.Section;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Etappen einer Erdkachel: Geländehöhen entstehen beim Planen im Weltbau-Thread, nicht im Bukkit-Takt. Das Höhenfeld
 * der Erdwelt lädt fehlende Höhenkacheln synchron (Platte, PNG, HTTP mit 60 s Zeitlimit) – im Takt hielte das den
 * Server an (Prüfbefund 2026-10-28).
 */
class WorldTileSourceTest {

    private static final String WORKER = "weltbau-test";
    /** Kachel (3, -2): Mitte bei x = 3·1024 + 512, z = −2·1024 + 512. */
    private static final int BASE_X = 3584;
    private static final int BASE_Z = -1536;

    private static List<P> rect(int x0, int z0, int x1, int z1) {
        return List.of(new P(x0, z0), new P(x1, z0), new P(x1, z1), new P(x0, z1), new P(x0, z0));
    }

    @Test
    void stageComesWithAbsoluteTerrainHeightsComputedOnTheWorkerThread() throws Exception {
        // Haus und Straße in der ersten Etappe (−256 … 0, die Mitte zuerst).
        Building house = new Building(1, rect(-40, -40, -30, -32), 7, "gabled", "house", "Testhaus");
        Road road = new Road(2, List.of(new P(-60, -20), new P(-10, -20)), 4, "residential", "Teststraße");
        CityModel model = new CityModel("Erdkachel 3/-2", 0, 0, 800, List.of(house), List.of(road), List.of(),
                List.of());
        Set<String> threads = ConcurrentHashMap.newKeySet();
        int[] range = {Integer.MAX_VALUE, Integer.MIN_VALUE};
        // Hang nach Osten auf 300 m Höhe – weit über jeder relativen Höhe des Plans (Boden 0, Haus bis ~15).
        HeightField ground = (x, z) -> {
            threads.add(Thread.currentThread().getName());
            synchronized (range) {
                range[0] = Math.min(range[0], x);
                range[1] = Math.max(range[1], x);
            }
            return 300 + Math.floorMod(x, 7);
        };
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, WORKER));
        try {
            WorldTileSource src = new WorldTileSource("Erdkachel 3/-2", model, worker, 5_000_000, ground, BASE_X,
                    BASE_Z);
            assertTrue(src.absolute(), "CityJob muss die Zellen unverändert setzen");
            CityPlanner.Plan plan = src.plan(0).get(30, TimeUnit.SECONDS);
            assertFalse(plan.sections().isEmpty());
            assertEquals(Set.of(WORKER), threads, "Höhenfeld außerhalb des Weltbau-Threads gefragt");
            // Abgefragt rund um die Kachelmitte, nicht um 0/0.
            assertTrue(range[0] >= BASE_X - 512 && range[1] <= BASE_X + 512, range[0] + " … " + range[1]);
            long total = 0;
            for (Section s : plan.sections()) {
                for (Cell c : s.cells()) {
                    assertTrue(c.y() >= 290, "Zelle nicht aufs Gelände gesetzt: " + c + " in " + s.label());
                    total++;
                }
            }
            assertEquals(total, plan.blocks(), "blocks zählt die gesetzten Zellen samt Sockel");
            Section building = plan.sections().stream().filter(Section::building).findFirst().orElseThrow();
            // Eine Basis fürs Haus: das Erdgeschoss liegt auf einer Ebene über dem höchsten Geländepunkt darunter.
            int floor = building.cells().stream().filter(c -> c.mat() != TerrainFit.PLINTH).mapToInt(Cell::y).min()
                    .orElseThrow();
            int highest = 0;
            for (Cell c : building.cells()) {
                highest = Math.max(highest, 300 + Math.floorMod(BASE_X + c.x(), 7));
            }
            assertEquals(highest, floor);
        } finally {
            worker.shutdownNow();
        }
    }
}
