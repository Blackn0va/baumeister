package com.blackn0va.baumeister.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.osm.CityModel.Building;
import com.blackn0va.baumeister.osm.CityModel.P;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Gebäude-Relationen (Multipolygon) mit Innenhof: Blockrandbebauung wie in der Münchner Innenstadt. Vorher las der
 * Import nur die outer-Ways, jeden einzeln – zwei offene Hälften wurden zwei Gebäude mit Sehne quer durch den Block, der
 * Hof bekam ein Dach. Die Tests gehen bewusst über OsmImport.parse und CityPlanner.plan (nicht über die neuen Helfer),
 * damit sie auch gegen den alten Planer übersetzen – so ist die Gegenprobe gegen den alten Stand möglich.
 */
class CityMultipolygonTest {

    static final double LAT0 = 48.137;
    static final double LON0 = 11.575;
    private static final double COS = Math.cos(Math.toRadians(LAT0));

    /** Außen 30 × 30 aus zwei offenen Ways (der zweite läuft andersherum), innen ein geschlossener Hof 10 × 10. */
    private static CityModel courtyard() {
        String outerA = member("outer", 0, 0, 15, 0, 29, 0, 29, 29);
        String outerB = member("outer", 0, 0, 0, 29, 15, 29, 29, 29);
        String inner = member("inner", 10, 10, 19, 10, 19, 19, 10, 19, 10, 10);
        String json = elements(relation(500, "\"building\":\"apartments\",\"building:levels\":\"3\","
                + "\"roof:shape\":\"flat\"", outerA, outerB, inner));
        return OsmImport.parse(json, "Test", LAT0, LON0, 200);
    }

    @Test
    void courtyardRelationOfTwoOpenWaysBecomesOneBuildingWithOneHole() {
        CityModel c = courtyard();
        assertEquals(1, c.buildings().size(), "Gebäude: " + c.buildings().size());
        Building b = c.buildings().getFirst();
        assertEquals(500, b.id());
        assertEquals(1, b.holes().size());
        assertEquals(b.outline().getFirst(), b.outline().getLast(), "Außenring geschlossen");
        assertTrue(b.outline().containsAll(List.of(new P(0, 0), new P(29, 0), new P(29, 29), new P(0, 29))));
    }

    @Test
    void courtyardStaysOpenAndIsWalledIn() {
        CityPlanner.Plan plan = CityPlanner.plan(courtyard(), 1_000_000);
        Map<String, String> world = world(plan);
        // Grundfläche = Hausboden in y=0: 30 × 30 minus der Hof ohne seine Wand (11..18 = 8 × 8).
        long floor = plan.sections().stream().filter(s -> !s.label().startsWith("Boden"))
                .flatMap(s -> s.cells().stream()).filter(cell -> cell.y() == 0).count();
        assertEquals(900 - 64, floor);
        for (int y = 1; y <= OsmImport.MAX_HEIGHT + 16; y++) {
            assertFalse(world.containsKey(15 + "/" + y + "/" + 15), "Block über dem Hof in y=" + y);
        }
        // Hofwand: der Innenring selbst steht, Geschoss für Geschoss Wand oder Glas.
        int top = 10;  // 3 Geschosse × 3 + 1
        for (int y = 1; y < top; y++) {
            String m = world.get(10 + "/" + y + "/" + 15);
            assertTrue(m != null && !m.equals("air") && !m.equals("spruce_planks"), "Hofwand bei y=" + y + ": " + m);
        }
        String rim = world.get(19 + "/" + 2 + "/" + 14);
        assertTrue(rim != null && !rim.equals("air"), "Hofwand Ost: " + rim);
    }

    @Test
    void outerThatCannotBeClosedIsDropped() {
        String json = elements(relation(501, "\"building\":\"yes\"", member("outer", 0, 0, 15, 0, 29, 0, 29, 29)));
        CityModel c = OsmImport.parse(json, "Test", LAT0, LON0, 200);
        assertEquals(0, c.buildings().size(), "offener Ring darf kein Gebäude werden");
    }

    // ------------------------------------------------------------------ Hilfen (auch für CitySurfaceTest)

    /** Blockkoordinaten → Overpass-geometry; die Projektion von OsmImport rundet exakt auf dieselben Zahlen zurück. */
    static String geometry(int... xz) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i + 1 < xz.length; i += 2) {
            double lon = LON0 + xz[i] / (COS * 111_320.0);
            double lat = LAT0 - xz[i + 1] / 110_540.0;
            sb.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT, "{\"lat\":%.9f,\"lon\":%.9f}", lat, lon));
        }
        return sb.append(']').toString();
    }

    static String way(long id, String tags, int... xz) {
        return "{\"type\":\"way\",\"id\":" + id + ",\"tags\":{" + tags + "},\"geometry\":" + geometry(xz) + "}";
    }

    static String member(String role, int... xz) {
        return "{\"type\":\"way\",\"role\":\"" + role + "\",\"geometry\":" + geometry(xz) + "}";
    }

    static String relation(long id, String tags, String... members) {
        return "{\"type\":\"relation\",\"id\":" + id + ",\"tags\":{" + tags + "},\"members\":["
                + String.join(",", members) + "]}";
    }

    static String elements(String... elements) {
        return "{\"elements\":[" + String.join(",", elements) + "]}";
    }

    /** Plan → „x/y/z“ → Block, in Bauzeitfolge (spätere Abschnitte überschreiben frühere wie in der Welt). */
    static Map<String, String> world(CityPlanner.Plan plan) {
        Map<String, String> w = new HashMap<>();
        for (CityPlanner.Section s : plan.sections()) {
            for (CityPlanner.Cell c : s.cells()) {
                w.put(c.x() + "/" + c.y() + "/" + c.z(), plan.palette().get(c.mat()));
            }
        }
        return w;
    }
}
