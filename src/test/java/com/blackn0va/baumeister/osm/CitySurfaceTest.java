package com.blackn0va.baumeister.osm;

import static com.blackn0va.baumeister.osm.CityMultipolygonTest.LAT0;
import static com.blackn0va.baumeister.osm.CityMultipolygonTest.LON0;
import static com.blackn0va.baumeister.osm.CityMultipolygonTest.elements;
import static com.blackn0va.baumeister.osm.CityMultipolygonTest.way;
import static com.blackn0va.baumeister.osm.CityMultipolygonTest.world;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Was an die Oberfläche gehört: Tunnel, verdolte Bäche und Gänge in Gebäuden nicht; Fußgängerflächen ganz statt nur
 * als Umriss; Durchgänge (tunnel=building_passage) begehbar durch das Haus. Wie CityMultipolygonTest nur über
 * parse/plan, damit die Gegenprobe gegen den alten Stand übersetzt.
 */
class CitySurfaceTest {

    private static Map<String, String> build(String... elements) {
        CityModel c = OsmImport.parse(elements(elements), "Test", LAT0, LON0, 200);
        return world(CityPlanner.plan(c, 1_000_000));
    }

    private static final String PARK = way(1, "\"leisure\":\"park\"", 0, 0, 29, 0, 29, 29, 0, 29, 0, 0);

    @Test
    void tunnelFootwayUnderAMeadowLeavesNoTrace() {
        Map<String, String> w = build(PARK, way(2, "\"highway\":\"footway\",\"tunnel\":\"yes\",\"layer\":\"-1\"",
                -5, 15, 35, 15));
        assertFalse(w.containsValue("andesite"), "Tunnelweg an der Oberfläche");
        assertEquals("grass_block", w.get("15/0/15"));
    }

    @Test
    void culvertUnderARoadLeavesNoWater() {
        Map<String, String> w = build(way(3, "\"highway\":\"residential\"", -20, 0, 20, 0),
                way(4, "\"waterway\":\"stream\",\"tunnel\":\"culvert\"", 0, -20, 0, 20));
        assertFalse(w.containsValue("water"), "verdolter Bach an der Oberfläche");
        assertEquals("white_concrete", w.get("0/0/0"), "Mittellinie der Straße ungestört");
    }

    @Test
    void pedestrianAreaIsPavedAllOver() {
        Map<String, String> w = build(way(5, "\"highway\":\"pedestrian\",\"area\":\"yes\",\"name\":\"Platz\"",
                0, 0, 19, 0, 19, 19, 0, 19, 0, 0));
        assertEquals("polished_andesite", w.get("10/0/10"), "Platzmitte");
        assertEquals("stone_bricks", w.get("12/0/10"), "Fuge im 4er-Raster");
        assertEquals("stone_bricks", w.get("10/0/8"), "Fuge im 4er-Raster");
    }

    @Test
    void buildingPassageIsWalkableThroughTheHouse() {
        Map<String, String> w = build(
                way(6, "\"building\":\"yes\",\"building:levels\":\"3\",\"roof:shape\":\"flat\"",
                        0, 0, 29, 0, 29, 29, 0, 29, 0, 0),
                way(7, "\"highway\":\"footway\",\"tunnel\":\"building_passage\"", 15, -5, 15, 35));
        for (int z : new int[] {0, 15, 29}) {
            for (int y = 1; y <= 3; y++) {
                assertEquals("air", w.get(15 + "/" + y + "/" + z), "Durchgang bei z=" + z + " y=" + y);
            }
            assertEquals("andesite", w.get(15 + "/0/" + z), "Wegbelag im Durchgang bei z=" + z);
        }
        // Über dem Durchgang bleibt die Fassade (Sturz in y=4).
        assertFalse("air".equals(w.get("15/4/0")) || "air".equals(w.get("15/4/29")), "Sturz über dem Durchgang");
        // Daneben (Abstand 2 > Breite/2) steht die Fassade.
        for (int y = 1; y <= 3; y++) {
            String m = w.get(13 + "/" + y + "/" + 0);
            assertTrue(m != null && !m.equals("air"), "Wand neben dem Durchgang, y=" + y + ": " + m);
        }
    }

    @Test
    void footwayOnLayerMinusOneWithoutTunnelStays() {
        CityModel c = OsmImport.parse(elements(way(8, "\"highway\":\"footway\",\"layer\":\"-1\"", 0, 0, 20, 0)),
                "Test", LAT0, LON0, 200);
        assertEquals(1, c.roads().size(), "layer<0 allein ist kein Tunnel");
    }

    @Test
    void indoorBasementAndNonWaysAreDropped() {
        CityModel c = OsmImport.parse(elements(
                way(9, "\"highway\":\"footway\",\"indoor\":\"yes\"", 0, 0, 20, 0),
                way(10, "\"highway\":\"footway\",\"level\":\"-1;0\"", 0, 5, 20, 5),
                way(11, "\"highway\":\"corridor\"", 0, 10, 20, 10),
                way(12, "\"highway\":\"construction\"", 0, 15, 20, 15),
                way(13, "\"highway\":\"service\",\"tunnel\":\"covered\"", 0, 20, 20, 20),
                way(14, "\"highway\":\"footway\",\"level\":\"1\"", 0, 25, 20, 25)),
                "Test", LAT0, LON0, 200);
        assertEquals(1, c.roads().size(), "nur der Weg im 1. Stock (level=1) bleibt: " + c.roads());
        assertEquals(14, c.roads().getFirst().id());
    }

    @Test
    void closedPlatformBecomesPlazaOpenPlatformStaysAWay() {
        CityModel c = OsmImport.parse(elements(
                way(15, "\"highway\":\"platform\"", 0, 0, 10, 0, 10, 3, 0, 3, 0, 0),
                way(16, "\"highway\":\"platform\"", 0, 10, 30, 10),
                way(17, "\"highway\":\"footway\",\"area\":\"no\"", 0, 20, 10, 20, 10, 25, 0, 25, 0, 20)),
                "Test", LAT0, LON0, 200);
        assertEquals(Set.of("plaza"), Set.copyOf(c.areas().stream().map(CityModel.Area::kind).toList()));
        assertEquals(1, c.areas().size());
        assertEquals(2, c.roads().size());
    }
}
