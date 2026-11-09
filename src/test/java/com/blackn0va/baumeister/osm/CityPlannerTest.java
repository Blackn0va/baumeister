package com.blackn0va.baumeister.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.osm.CityModel.P;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Stadt aus echten OSM-Daten (Bad Kreuznach, 120 m um den Kornmarkt, Overpass-Abfrage vom 2026-10-28, gespeichert
 * unter src/test/resources/osm; © OpenStreetMap-Mitwirkende, ODbL).
 */
class CityPlannerTest {

    private static CityModel city() throws Exception {
        try (InputStream in = CityPlannerTest.class.getResourceAsStream("/osm/bad-kreuznach-120m.json")) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return OsmImport.parse(json, "Bad Kreuznach", 49.8451, 7.8676, 120);
        }
    }

    @Test
    void importReadsBuildingsRoadsAndAreasOfTheRealTown() throws Exception {
        CityModel c = city();
        assertEquals(68, c.buildings().size());
        assertEquals(16, c.roads().size());
        // Alles liegt im Umkreis der Abfrage (ein Gebäude darf über den Rand ragen).
        for (CityModel.Building b : c.buildings()) {
            P m = CityPlanner.centroid(b.outline());
            assertTrue(Math.hypot(m.x(), m.z()) < 250, "Gebäude " + b.id() + " bei " + m);
            assertTrue(b.height() >= 3 && b.height() <= OsmImport.MAX_HEIGHT);
        }
    }

    @Test
    void projectionIsOneBlockPerMetre() {
        // 0,001° Breite ≈ 110,5 m nach Norden = −z; 0,001° Länge bei 50° Breite ≈ 71,6 m nach Osten = +x.
        P north = OsmImport.project(50.001, 8.0, 50.0, 8.0, Math.cos(Math.toRadians(50.0)));
        P east = OsmImport.project(50.0, 8.001, 50.0, 8.0, Math.cos(Math.toRadians(50.0)));
        assertEquals(new P(0, -111), north);
        assertEquals(new P(72, 0), east);
    }

    @Test
    void heightsComeFromTagsOrSensibleDefaults() {
        assertEquals(12, OsmImport.height(Map.of("height", "12 m"), "yes"));
        assertEquals(13, OsmImport.height(Map.of("building:levels", "4"), "yes"));
        assertEquals(3, OsmImport.height(Map.of(), "garage"));
        assertEquals(14, OsmImport.height(Map.of(), "church"));
        assertEquals(OsmImport.MAX_HEIGHT, OsmImport.height(Map.of("height", "300"), "yes"));
    }

    @Test
    void fillCoversASquareCompletely() {
        Set<P> s = CityPlanner.fill(List.of(new P(0, 0), new P(9, 0), new P(9, 9), new P(0, 9), new P(0, 0)));
        assertEquals(100, s.size());
        assertTrue(s.contains(new P(0, 0)) && s.contains(new P(9, 9)) && s.contains(new P(5, 5)));
    }

    @Test
    void planHasWallsRoofsAndRoadsAndStaysWithinLimits() throws Exception {
        CityPlanner.Plan plan = CityPlanner.plan(city(), 3_000_000);
        assertTrue(plan.blocks() > 10_000, "zu wenige Blöcke: " + plan.blocks());
        assertTrue(plan.palette().contains("gray_concrete"), "Straßenbelag fehlt");
        assertTrue(plan.palette().contains("glass"), "Fenster fehlen");
        long buildingSections = plan.sections().stream().filter(s -> !s.label().startsWith("Boden")).count();
        assertEquals(68, buildingSections);
        // Boden vor Gebäuden, jeder Abschnitt von unten nach oben.
        boolean seenBuilding = false;
        for (CityPlanner.Section s : plan.sections()) {
            boolean ground = s.label().startsWith("Boden");
            assertFalse(ground && seenBuilding, "Boden nach Gebäude: " + s.label());
            seenBuilding |= !ground;
            for (int i = 1; i < s.cells().size(); i++) {
                assertTrue(s.cells().get(i - 1).y() <= s.cells().get(i).y(), s.label());
            }
        }
        // Höchster Block unter Grenze + Dach.
        int top = plan.sections().stream().flatMap(s -> s.cells().stream()).mapToInt(CityPlanner.Cell::y).max()
                .orElse(0);
        assertTrue(top <= OsmImport.MAX_HEIGHT + 16, "zu hoch: " + top);
    }

    @Test
    void tooLargePlansAreRefused() throws Exception {
        CityModel c = city();
        assertThrows(IllegalStateException.class, () -> CityPlanner.plan(c, 1000));
    }
}
