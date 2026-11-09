package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.earth.HeightField;
import com.blackn0va.baumeister.osm.CityModel;
import com.blackn0va.baumeister.osm.CityModel.Building;
import com.blackn0va.baumeister.osm.CityModel.P;
import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.CityPlanner.Cell;
import com.blackn0va.baumeister.osm.CityPlanner.Section;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Kirchtürme als eigene Gebäudeteile (building:part) am Hang (Gedächtnis „Offen“ bis 2026-10-29): Schiff, Turm und Helm
 * stehen auf einer gemeinsamen Basis; der Turm bekommt seinen eigenen Sockel bis dorthin.
 */
class ChurchTowerSlopeTest {

    /** Hang nach Osten: y = 60 + x. Die Kirche liegt bei x 0…24, der Turm im Westen – also talseitig. */
    private static final HeightField SLOPE = (x, z) -> 60 + x;
    private static final int BASE_X = 100;
    private static final int BASE_Z = 0;

    private static List<P> rect(int x0, int z0, int x1, int z1) {
        return List.of(new P(x0, z0), new P(x1, z0), new P(x1, z1), new P(x0, z1), new P(x0, z0));
    }

    private static Map<String, List<Cell>> fitted() {
        Building nave = new Building(1, rect(0, 0, 24, 9), 14, "gabled", "church", "Schiff", "", "", 0, false);
        Building tower = new Building(2, rect(0, 2, 5, 7), 40, "flat", "tower", "Turm", "", "", 0, true);
        Building helm = new Building(3, rect(1, 3, 4, 6), 52, "pyramidal", "roof", "Helm", "", "", 40, true);
        CityModel city = new CityModel("Test", 0, 0, 100, List.of(nave, tower, helm), List.of(), List.of(),
                List.of());
        CityPlanner.Plan p = CityPlanner.plan(city, 1_000_000);
        Map<String, List<Cell>> out = new HashMap<>();
        for (Section s : p.sections()) {
            if (s.building()) {
                out.put(s.label(), TerrainFit.fit(s, SLOPE, BASE_X, BASE_Z));
            }
        }
        return out;
    }

    private static int lowest(List<Cell> cells) {
        return cells.stream().filter(c -> c.mat() != TerrainFit.PLINTH).mapToInt(Cell::y).min().orElseThrow();
    }

    @Test
    void towerAndNaveShareOneBaseOnTheSlope() {
        Map<String, List<Cell>> f = fitted();
        int nave = lowest(f.get("Schiff"));
        // Basis = höchster Punkt der ganzen Gruppe: x = 24 → 60 + 100 + 24.
        assertEquals(184, nave);
        assertEquals(nave, lowest(f.get("Turm")), "Turmboden auf anderer Höhe als das Schiff");
    }

    @Test
    void helmSitsExactlyOnTopOfTheTower() {
        Map<String, List<Cell>> f = fitted();
        int tower = lowest(f.get("Turm"));
        assertEquals(tower + 40, lowest(f.get("Helm")), "Helm steckt im Turm oder schwebt");
        assertFalse(f.get("Helm").stream().anyMatch(c -> c.mat() == TerrainFit.PLINTH), "Helm braucht keinen Sockel");
    }

    @Test
    void towerGetsItsOwnPlinthDownTheSlope() {
        List<Cell> tower = fitted().get("Turm");
        long plinth = tower.stream().filter(c -> c.mat() == TerrainFit.PLINTH).count();
        assertTrue(plinth > 0, "Turm ohne Sockel");
        // Unter der Westwand (x = 0, Gelände 160) reicht der Sockel von 161 bis 183.
        assertTrue(tower.stream().anyMatch(c -> c.mat() == TerrainFit.PLINTH && c.x() == 0 && c.y() == 161));
        assertTrue(tower.stream().anyMatch(c -> c.mat() == TerrainFit.PLINTH && c.x() == 0 && c.y() == 183));
    }

    @Test
    void lonePartWithoutABuildingKeepsItsOwnBase() {
        // Turm ohne umgebendes Gebäude: keine Gruppe, Basis aus dem eigenen Grundriss wie bisher.
        Building tower = new Building(2, rect(40, 2, 45, 7), 40, "flat", "tower", "Turm", "", "", 0, true);
        CityModel city = new CityModel("Test", 0, 0, 100, List.of(tower), List.of(), List.of(), List.of());
        Section s = CityPlanner.plan(city, 1_000_000).sections().stream().filter(Section::building).findFirst()
                .orElseThrow();
        assertTrue(s.basis().isEmpty());
        assertEquals(60 + BASE_X + 45, lowest(TerrainFit.fit(s, SLOPE, BASE_X, BASE_Z)));
    }
}
