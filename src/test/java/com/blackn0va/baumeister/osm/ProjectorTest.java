package com.blackn0va.baumeister.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.osm.CityModel.Building;
import com.blackn0va.baumeister.osm.CityModel.P;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * OsmImport mit Projector (Weltbau): Die Vorgabe muss genau das alte Verhalten liefern, eine fremde Projektion muss
 * jeden Punkt erreichen (Gebäude, Höfe, Wege, Bäume), und Punkte ohne Stelle auf der Karte fallen weg.
 */
class ProjectorTest {

    private static final double LAT0 = 49.8451;
    private static final double LON0 = 7.8676;

    private static String badKreuznach() throws Exception {
        try (InputStream in = ProjectorTest.class.getResourceAsStream("/osm/bad-kreuznach-120m.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Ein Baum, ein Haus (Way) und ein Weg – genug, um jede Stelle zu treffen, an der der Import projiziert. */
    private static final String SMALL = "{\"elements\":["
            + "{\"type\":\"node\",\"id\":1,\"lat\":50.0005,\"lon\":8.0005,\"tags\":{\"natural\":\"tree\"}},"
            + "{\"type\":\"way\",\"id\":2,\"tags\":{\"building\":\"house\"},\"geometry\":["
            + "{\"lat\":50.0,\"lon\":8.0},{\"lat\":50.0,\"lon\":8.0002},{\"lat\":50.0001,\"lon\":8.0002},"
            + "{\"lat\":50.0001,\"lon\":8.0},{\"lat\":50.0,\"lon\":8.0}]},"
            + "{\"type\":\"way\",\"id\":3,\"tags\":{\"highway\":\"residential\"},\"geometry\":["
            + "{\"lat\":49.9995,\"lon\":7.999},{\"lat\":49.9995,\"lon\":8.001}]}]}";

    @Test
    void defaultProjectorGivesExactlyTheOldModel() throws Exception {
        String json = badKreuznach();
        CityModel old = OsmImport.parse(json, "Bad Kreuznach", LAT0, LON0, 120);
        CityModel viaProjector = OsmImport.parse(json, "Bad Kreuznach", LAT0, LON0, 120, OsmImport.around(LAT0, LON0));
        assertFalse(old.buildings().isEmpty());
        assertEquals(old, viaProjector);
    }

    @Test
    void defaultProjectorKeepsTheKnownMetres() {
        Projector p = OsmImport.around(50.0, 8.0);
        // 0,001° Breite = 110,54 m nach Norden (z negativ), 0,001° Länge bei 50° = 71,55 m nach Osten.
        assertEquals(new P(0, -111), p.project(50.001, 8.0));
        assertEquals(new P(72, 0), p.project(50.0, 8.001));
        assertEquals(new P(0, 0), p.project(50.0, 8.0));
    }

    @Test
    void foreignProjectorReachesEveryPoint() throws Exception {
        String json = badKreuznach();
        Projector base = OsmImport.around(LAT0, LON0);
        Projector shifted = (lat, lon) -> {
            P q = base.project(lat, lon);
            return new P(q.x() + 1000, q.z() - 500);
        };
        CityModel a = OsmImport.parse(json, "X", LAT0, LON0, 120);
        CityModel b = OsmImport.parse(json, "X", LAT0, LON0, 120, shifted);
        assertEquals(a.buildings().size(), b.buildings().size());
        assertEquals(a.roads().size(), b.roads().size());
        assertEquals(a.areas().size(), b.areas().size());
        for (int i = 0; i < a.buildings().size(); i++) {
            Building ba = a.buildings().get(i);
            Building bb = b.buildings().get(i);
            for (int k = 0; k < ba.outline().size(); k++) {
                assertEquals(ba.outline().get(k).x() + 1000, bb.outline().get(k).x());
                assertEquals(ba.outline().get(k).z() - 500, bb.outline().get(k).z());
            }
            assertEquals(ba.holes().size(), bb.holes().size());
        }
        for (int i = 0; i < a.roads().size(); i++) {
            assertEquals(a.roads().get(i).line().getFirst().x() + 1000, b.roads().get(i).line().getFirst().x());
        }
    }

    @Test
    void treesUseTheProjectorToo() {
        CityModel m = OsmImport.parse(SMALL, "X", 50.0, 8.0, 100, (lat, lon) -> new P(7, 9));
        assertEquals(1, m.trees().size());
        assertEquals(7, m.trees().getFirst().x());
        assertEquals(9, m.trees().getFirst().z());
    }

    @Test
    void pointsOffTheMapAreDropped() {
        // Alles westlich von 8.0 liegt „in einer Netzlücke“: Der Weg verliert seinen Anfang (ein Punkt bleibt, zu
        // wenig für eine Linie), Haus und Baum liegen ganz auf der Karte und bleiben.
        Projector base = OsmImport.around(50.0, 8.0);
        CityModel m = OsmImport.parse(SMALL, "X", 50.0, 8.0, 100, (lat, lon) -> lon < 8.0 ? null
                : base.project(lat, lon));
        assertEquals(1, m.trees().size());
        assertEquals(1, m.buildings().size());
        assertTrue(m.roads().isEmpty(), m.roads().toString());
        CityModel none = OsmImport.parse(SMALL, "X", 50.0, 8.0, 100, (lat, lon) -> null);
        assertTrue(none.trees().isEmpty() && none.buildings().isEmpty() && none.roads().isEmpty());
    }
}
