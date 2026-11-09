package com.blackn0va.baumeister.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.blackn0va.baumeister.osm.CityModel.Line;
import com.blackn0va.baumeister.osm.CityModel.P;

/**
 * Küstenlinie beim Import: Land muss nach der Projektion links liegen – ob die Projektion spiegelt (Erdwelt, Nord = −z)
 * oder nicht. Fiktive Küste bei 10° O: Laufrichtung Ost, Land also im Norden (OSM-Regel).
 */
class OsmImportCoastTest {

    private static final String JSON = "{\"elements\":[{\"type\":\"way\",\"id\":7,\"tags\":{\"natural\":\"coastline\"},"
            + "\"geometry\":[{\"lat\":50.0,\"lon\":9.99},{\"lat\":50.0,\"lon\":10.0},{\"lat\":50.0,\"lon\":10.01}]}]}";

    private static SeaMask maskOf(CityModel m) {
        List<List<P>> coasts = m.lines().stream().filter(l -> l.kind().equals("coastline")).map(Line::points).toList();
        assertEquals(1, coasts.size());
        return SeaMask.of(coasts, -400, -400, 400, 400);
    }

    private static void landIsNorth(Projector proj) {
        CityModel m = OsmImport.parse(JSON, "Test", 50.0, 10.0, 500, proj);
        SeaMask mask = maskOf(m);
        P north = proj.project(50.001, 10.0);
        P south = proj.project(49.999, 10.0);
        assertFalse(mask.sea(north.x(), north.z()), "Norden ist Land");
        assertTrue(mask.sea(south.x(), south.z()), "Süden ist Meer");
    }

    @Test
    void mirroredProjectionLikeTheEarthWorld() {
        // Nord = −z wie in der Erdwelt: spiegelt.
        Projector earthLike = (lat, lon) -> new P((int) Math.round((lon - 10.0) * 71_000),
                (int) Math.round(-(lat - 50.0) * 111_000));
        assertTrue(OsmImport.mirrored(earthLike, 50.0, 10.0));
        landIsNorth(earthLike);
    }

    @Test
    void unmirroredProjection() {
        Projector math = (lat, lon) -> new P((int) Math.round((lon - 10.0) * 71_000),
                (int) Math.round((lat - 50.0) * 111_000));
        assertFalse(OsmImport.mirrored(math, 50.0, 10.0));
        landIsNorth(math);
    }

    @Test
    void coastlineIsNoAreaAndNoBarrier() {
        CityModel m = OsmImport.parse(JSON, "Test", 50.0, 10.0, 500, OsmImport.around(50.0, 10.0));
        assertTrue(m.areas().isEmpty());
        assertEquals(List.of("coastline"), m.lines().stream().map(Line::kind).toList());
    }
}
