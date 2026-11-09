package com.blackn0va.baumeister.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.osm.OsmClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Ausgabe von /bm wo und das Speicherformat der letzten Stellen – ohne Server. */
class EarthServiceTest {

    @Test
    void compassNamesMinecraftDirections() {
        assertEquals("−z (Minecraft-Nord)", EarthService.compass(0));
        assertEquals("−z (Minecraft-Nord)", EarthService.compass(-20));
        assertEquals("+x (Minecraft-Ost)", EarthService.compass(90));
        assertEquals("+z (Minecraft-Süd)", EarthService.compass(180));
        assertEquals("+z (Minecraft-Süd)", EarthService.compass(-180));
        assertEquals("−x (Minecraft-West)", EarthService.compass(-90));
        assertEquals("+x −z (Nordost)", EarthService.compass(44));
    }

    @Test
    void whereReportsHeightShiftScaleAndNorth() {
        EarthTerrain.Column c = new EarthTerrain.Column(27.98810, 86.92500, 8848, 8848, 6379, 2030, -6379, false,
                false, true, false);
        List<String> lines = EarthService.whereLines(-9240901.4, 88593.2, c, new EarthMetrics.Local(0.94, 1.01,
                92.1), "airocean");
        assertEquals(4, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("27,98810° N") && lines.get(0).contains("86,92500° O"), lines.get(0));
        assertTrue(lines.get(1).contains("Echte Höhe 8848 m") && lines.get(1).contains("Bezug 6379 m")
                && lines.get(1).contains("gekappt"), lines.get(1));
        assertTrue(lines.get(2).contains("0,940") && lines.get(2).contains("1,010"), lines.get(2));
        assertTrue(lines.get(3).contains("+x (Minecraft-Ost)") && lines.get(3).contains("92,1°"), lines.get(3));
    }

    @Test
    void whereNamesSeaFloorAndGaps() {
        EarthTerrain.Column sea = new EarthTerrain.Column(40, -30, -4500, -200, 0, -200, 0, false, false, false,
                false);
        List<String> lines = EarthService.whereLines(0, 0, sea, new EarthMetrics.Local(1, 1, 10), "airocean");
        assertTrue(lines.get(1).contains("Echte Höhe -4500 m (Meeresboden auf -200 m angehoben)"), lines.get(1));
        EarthTerrain.Column gap = new EarthTerrain.Column(Double.NaN, Double.NaN, -200, -200, 0, -200, 0, true,
                false, false, false);
        List<String> g = EarthService.whereLines(2.9e7, 0, gap, null, "sinus6");
        assertEquals(1, g.size());
        assertTrue(g.get(0).contains("Lücke im Netz (sinus6)"), g.get(0));
    }

    @Test
    void travelTakesCoordinatesUpToThePoles() {
        // Vorher nahm /bm reise nur |lat| ≤ 85 direkt; der Rest ging als Freitext an Nominatim.
        assertEquals(-89.9, EarthService.target("-89.9,0").lat(), 1e-9);
        assertEquals(80.5, EarthService.target("80.5, 16.2").lat(), 1e-9);
        assertEquals(90.0, EarthService.target("90;0").lat(), 1e-9);
        assertEquals(-180.0, EarthService.target("86 -180").lon(), 1e-9);
        assertNull(EarthService.target("91,0"));
        assertNull(EarthService.target("45,181"));
        assertNull(EarthService.target("Hepberg"));
        // Das Gelände je Ort bleibt bei 85° (Mercator-Kacheln) – unverändert.
        assertNull(OsmClient.coordinates("-89.9,0", 85));
        assertTrue(OsmClient.looksLikeCoordinates("91,0"));
        assertFalse(OsmClient.looksLikeCoordinates("Ny-Ålesund"));
    }

    @Test
    void visitsAreStoredAsFiveNumbersPerPlayer() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String json = VisitStore.toJson(Map.of(id, new double[] {1.5, 64, -3.5, 180, 0})).toString();
        assertEquals("{\"00000000-0000-0000-0000-000000000001\":[1.5,64.0,-3.5,180.0,0.0]}", json);
    }
}
