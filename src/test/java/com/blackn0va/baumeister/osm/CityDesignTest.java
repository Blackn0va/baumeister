package com.blackn0va.baumeister.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.osm.CityModel.Building;
import com.blackn0va.baumeister.osm.CityModel.P;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * „Die ganze Stadt, mit Design und Kirchen“ (Nutzerwunsch 2026-10-28): Ortsausdehnung aus Nominatim, Farben und
 * Baustoffe aus OSM, Kirchtürme als Gebäudeteile mit eigener Höhe und spitzem Helm.
 */
class CityDesignTest {

    @Test
    void wholePlaceRadiusCoversTheNominatimBoundingBox() {
        // Hepberg laut Nominatim etwa 48.818–48.838 N, 11.450–11.476 O; Mitte 48.8281/11.4624.
        var o = JsonParser.parseString("{\"boundingbox\":[\"48.818\",\"48.838\",\"11.450\",\"11.476\"]}")
                .getAsJsonObject();
        int r = OsmClient.extent(o, 48.8281, 11.4624);
        // Ecke NO: 0,0099° N ≈ 1094 m, 0,0136° O ≈ 997 m → ≈ 1480 m
        assertTrue(r > 1400 && r < 1600, "Radius " + r);
        assertEquals(0, OsmClient.extent(JsonParser.parseString("{}").getAsJsonObject(), 48.8, 11.4));
    }

    @Test
    void coloursAndMaterialsBecomeMatchingBlocks() {
        assertEquals("bricks", OsmMaterials.wall(Map.of("building:material", "brick")));
        assertEquals("oxidized_cut_copper", OsmMaterials.roof(Map.of("roof:material", "copper", "roof:colour", "red")));
        assertEquals("deepslate_tiles", OsmMaterials.roof(Map.of("roof:material", "slate")));
        assertEquals("white_concrete", OsmMaterials.wall(Map.of("building:colour", "#ffffff")).replace("quartz_block",
                "white_concrete"));
        String beige = OsmMaterials.wall(Map.of("building:colour", "beige"));
        assertTrue(beige.equals("smooth_sandstone") || beige.equals("white_terracotta"), "beige → " + beige);
        assertEquals("red_terracotta", OsmMaterials.roof(Map.of("roof:colour", "#a0402e")));
        // Farbe schlägt Baustoff; Unbekanntes bleibt leer (Planer-Vorgabe)
        assertFalse(OsmMaterials.wall(Map.of("building:colour", "#202020", "building:material", "brick")).equals("bricks"));
        assertEquals("", OsmMaterials.wall(Map.of("building:colour", "kariert")));
    }

    @Test
    void buildingPartsAreReadAsTowersWithMinHeight() {
        String json = "{\"elements\":[{\"type\":\"way\",\"id\":7,\"tags\":{\"building:part\":\"yes\",\"tower:type\":"
                + "\"bell_tower\",\"height\":\"60\",\"min_height\":\"20\",\"roof:shape\":\"pyramidal\","
                + "\"roof:material\":\"copper\"},\"geometry\":[" + square(49.0, 8.0, 0.00006) + "]}]}";
        CityModel c = OsmImport.parse(json, "Test", 49.0, 8.0, 100);
        Building t = c.buildings().getFirst();
        assertTrue(t.part());
        assertEquals("tower", t.kind());
        assertEquals(60, t.height());
        assertEquals(20, t.minHeight());
        assertEquals("oxidized_cut_copper", t.roofBlock());
    }

    @Test
    void churchTowerStandsAboveTheNaveWithAPointedCopperSpire() {
        // Kirchenschiff 20 × 10, Turm 6 × 6 vorn, 40 m hoch, Pyramidenhelm aus Kupfer.
        Building nave = new Building(1, rect(0, 0, 19, 9), 14, "gabled", "church", "St. Test", "", "", 0, false);
        Building tower = new Building(2, rect(0, 2, 5, 7), 40, "pyramidal", "tower", "", "", "oxidized_cut_copper",
                0, true);
        CityModel city = new CityModel("Test", 0, 0, 100, List.of(nave, tower), List.of(), List.of(), List.of());
        CityPlanner.Plan p = CityPlanner.plan(city, 1_000_000);
        // Turm nach dem Schiff (überschreibt es), und er überragt es samt Helm deutlich.
        List<String> labels = p.sections().stream().map(CityPlanner.Section::label).toList();
        assertTrue(labels.indexOf("St. Test") < labels.size() - 1, "Turm als letzter Abschnitt: " + labels);
        CityPlanner.Section towerSection = p.sections().getLast();
        int top = towerSection.cells().stream().mapToInt(CityPlanner.Cell::y).max().orElse(0);
        assertTrue(top >= 40 + 6, "Helm spitz: oberster Block bei " + top);
        long copper = towerSection.cells().stream().filter(c -> p.palette().get(c.mat()).equals("oxidized_cut_copper"))
                .count();
        assertTrue(copper > 30, "Kupferhelm: " + copper);
    }

    private static List<P> rect(int x0, int z0, int x1, int z1) {
        return List.of(new P(x0, z0), new P(x1, z0), new P(x1, z1), new P(x0, z1), new P(x0, z0));
    }

    private static String square(double lat, double lon, double d) {
        return String.format(java.util.Locale.ROOT, "{\"lat\":%f,\"lon\":%f},{\"lat\":%f,\"lon\":%f},{\"lat\":%f,"
                + "\"lon\":%f},{\"lat\":%f,\"lon\":%f},{\"lat\":%f,\"lon\":%f}", lat, lon, lat, lon + d, lat + d,
                lon + d, lat + d, lon, lat, lon);
    }
}
