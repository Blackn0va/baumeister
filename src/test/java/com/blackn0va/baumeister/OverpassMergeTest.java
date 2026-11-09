package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.blackn0va.baumeister.osm.CityModel;
import com.blackn0va.baumeister.osm.OsmImport;

import com.google.gson.JsonParser;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Zwei Overpass-Antworten beiderseits der Datumsgrenze → eine Karte, gemeinsame Elemente nur einmal. */
class OverpassMergeTest {

    /** Westseite (lon 179,99x): ein Haus; dazu eine Straße über die Grenze. */
    private static final String WEST = """
            {"version":0.6,"generator":"Overpass API","osm3s":{"timestamp_osm_base":"2026-10-29T08:00:00Z"},
             "elements":[
              {"type":"way","id":11,"tags":{"building":"yes"},"geometry":[
                {"lat":-16.8000,"lon":179.9990},{"lat":-16.8000,"lon":179.9995},{"lat":-16.8004,"lon":179.9995},
                {"lat":-16.8004,"lon":179.9990},{"lat":-16.8000,"lon":179.9990}]},
              {"type":"way","id":99,"tags":{"highway":"residential"},"geometry":[
                {"lat":-16.8010,"lon":179.9980},{"lat":-16.8010,"lon":-179.9980}]}
             ]}""";

    /** Ostseite (lon −179,99x): ein anderes Haus und dieselbe Straße. */
    private static final String EAST = """
            {"version":0.6,"generator":"Overpass API","osm3s":{"timestamp_osm_base":"2026-10-29T08:00:00Z"},
             "elements":[
              {"type":"way","id":12,"tags":{"building":"yes"},"geometry":[
                {"lat":-16.8000,"lon":-179.9995},{"lat":-16.8000,"lon":-179.9990},{"lat":-16.8004,"lon":-179.9990},
                {"lat":-16.8004,"lon":-179.9995},{"lat":-16.8000,"lon":-179.9995}]},
              {"type":"way","id":99,"tags":{"highway":"residential"},"geometry":[
                {"lat":-16.8010,"lon":179.9980},{"lat":-16.8010,"lon":-179.9980}]}
             ]}""";

    @Test
    void bothSidesEndUpInOneMapWithoutDuplicates() {
        String merged = OverpassMerge.merge(List.of(WEST, EAST));
        assertEquals(3, JsonParser.parseString(merged).getAsJsonObject().getAsJsonArray("elements").size());
        // OsmImport liest das Ergebnis wie jede Antwort: zwei Häuser, eine Straße.
        CityModel m = OsmImport.parse(merged, "Datumsgrenze", -16.8, 180, 600);
        assertEquals(2, m.buildings().size());
        assertEquals(1, m.roads().size());
    }

    @Test
    void singleAnswerKeepsItsElements() {
        String merged = OverpassMerge.merge(List.of(WEST));
        assertEquals(2, JsonParser.parseString(merged).getAsJsonObject().getAsJsonArray("elements").size());
    }

    @Test
    void brokenAnswerIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> OverpassMerge.merge(List.of(WEST, "<html>502</html>")));
    }
}
