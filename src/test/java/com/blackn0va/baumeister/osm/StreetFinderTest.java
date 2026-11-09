package com.blackn0va.baumeister.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * /bm ort &lt;Stadt&gt; &lt;Straße&gt; [Nr] (Wunsch 2026-10-30 für die Keplerstraße in Eberdingen).
 * Kachel nachgebaut im Overpass-Format „out geom“, Mitte bei 48,88 / 8,96.
 */
class StreetFinderTest {

    private static final double LAT = 48.88;
    private static final double LON = 8.96;

    /** Ein Weg im Overpass-Format; Punkte als {lat, lon}. */
    private static String way(String tags, double[]... pts) {
        StringBuilder g = new StringBuilder();
        for (double[] p : pts) {
            g.append(g.isEmpty() ? "" : ",").append("{\"lat\":").append(p[0]).append(",\"lon\":").append(p[1])
                    .append('}');
        }
        return "{\"type\":\"way\",\"tags\":{" + tags + "},\"geometry\":[" + g + "]}";
    }

    private static final String TILE = "{\"elements\":["
            // Keplerstraße von West nach Ost, etwa 150 m, knapp nördlich der Mitte
            + way("\"highway\":\"residential\",\"name\":\"Keplerstraße\"", new double[] {48.881, 8.959},
                    new double[] {48.881, 8.960}, new double[] {48.881, 8.961})
            + "," + way("\"highway\":\"residential\",\"name\":\"Hauffstraße\"", new double[] {48.875, 8.95},
                    new double[] {48.876, 8.95})
            // Haus Keplerstraße 5 südlich der Straße bei lon 8.9605
            + "," + way("\"building\":\"house\",\"addr:street\":\"Keplerstraße\",\"addr:housenumber\":\"5\"",
                    new double[] {48.8808, 8.9604}, new double[] {48.8808, 8.9606}, new double[] {48.8806, 8.9606},
                    new double[] {48.8806, 8.9604}, new double[] {48.8808, 8.9604})
            + "]}";

    @Test
    void typoAndSpellingFindTheStreet() {
        for (String q : List.of("kepplerstrasse", "Keplerstraße", "keplerstr", "KEPLER STRASSE")) {
            StreetFinder.Hit h = StreetFinder.find(List.of(TILE), LAT, LON, q, null).orElseThrow(() ->
                    new AssertionError(q));
            assertEquals("Keplerstraße", h.street(), q);
        }
    }

    @Test
    void aDifferentRealStreetIsNotGuessed() {
        // „hauptstr“ zu „hauffstr“: Abstand 2 – vorher (1 je 6 Zeichen) wäre das die Hauffstraße geworden.
        assertTrue(StreetFinder.find(List.of(TILE), LAT, LON, "Hauptstrasse", null).isEmpty());
        assertTrue(StreetFinder.find(List.of(TILE), LAT, LON, "Gibtsnichtweg", null).isEmpty());
    }

    @Test
    void streetMiddleLiesOnTheBuiltStreet() {
        StreetFinder.Hit h = StreetFinder.find(List.of(TILE), LAT, LON, "keplerstr", null).orElseThrow();
        // Wie OsmImport.project: z = −(48,881 − 48,88) · 110 540 ≈ −111, x der Mitte (lon 8,960) = 0
        assertEquals(-111, h.z());
        assertEquals(0, h.x());
        assertFalse(h.numberMissing());
    }

    @Test
    void houseNumberLeadsInFrontOfTheHouseLookingAtIt() {
        StreetFinder.Hit h = StreetFinder.find(List.of(TILE), LAT, LON, "kepplerstrasse", "5").orElseThrow();
        assertFalse(h.numberMissing());
        assertEquals("5", h.number());
        // Haus bei lon 8,9605 → x ≈ 37; der nächste Straßenpunkt liegt genau nördlich davon, Blick nach Süden (yaw 0).
        assertEquals(37, h.x(), 1);
        assertEquals(-111, h.z());
        assertEquals(0f, h.yaw(), 1f);
    }

    @Test
    void missingNumberGoesToTheStreetAndSaysSo() {
        StreetFinder.Hit h = StreetFinder.find(List.of(TILE), LAT, LON, "keplerstrasse", "99").orElseThrow();
        assertTrue(h.numberMissing());
        assertEquals(0, h.x());
    }

    @Test
    void brokenTilesAreSkipped() {
        assertTrue(StreetFinder.find(List.of("{kaputt", "{}"), LAT, LON, "keplerstrasse", null).isEmpty());
        assertTrue(StreetFinder.find(List.of("{kaputt", TILE), LAT, LON, "keplerstrasse", null).isPresent());
    }

    /**
     * Speicherregel: Vorher behielt die Suche jeden benannten Weg aller Kacheln einer Stadt – ein /bm ort konnte den
     * Server in einen OutOfMemoryError treiben. Jetzt bleiben nur Wege, deren Name zur Suche passt; das Ergebnis
     * bleibt dasselbe (die übrigen Tests laufen über dieselbe Search).
     */
    @Test
    void searchKeepsOnlyMatchingWays() throws Exception {
        String town;
        try (java.io.InputStream in = StreetFinderTest.class.getResourceAsStream("/osm/bad-kreuznach-120m.json")) {
            town = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        int named = 0;
        for (com.google.gson.JsonElement el : com.google.gson.JsonParser.parseString(town).getAsJsonObject()
                .getAsJsonArray("elements")) {
            com.google.gson.JsonObject e = el.getAsJsonObject();
            if (e.has("geometry") && e.has("tags") && e.getAsJsonObject("tags").has("highway")
                    && e.getAsJsonObject("tags").has("name")) {
                named++;
            }
        }
        StreetFinder.Search search = new StreetFinder.Search(49.8451, 7.8676, "planiger strasse", null);
        search.add(town);
        search.add(TILE);
        assertTrue(named > 2, "Fixture mit mehreren benannten Wegen: " + named);
        assertEquals(2, search.retainedWays(), "nur die beiden Abschnitte der Planiger Straße");
        assertEquals("Planiger Straße", search.result().orElseThrow().street());
        assertEquals(StreetFinder.find(List.of(town, TILE), 49.8451, 7.8676, "planiger strasse", null),
                search.result());
    }
}
