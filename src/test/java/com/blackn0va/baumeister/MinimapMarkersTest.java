package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.minimap.MinimapImage;

import org.junit.jupiter.api.Test;

/** Marker der Minikarte: Richtung, Lage, Entfernung, Straßenerkennung beim Lesen. */
class MinimapMarkersTest {

    @Test
    void directionMatchesVanillaAndUpIsEight() {
        assertEquals(8, Minimap.direction(180f), "nach oben (Norden bzw. Blickrichtung)");
        assertEquals(0, Minimap.direction(0f), "nach unten");
        assertEquals(12, Minimap.direction(270f), "wie −90");
        assertEquals(Minimap.direction(-90f), Minimap.direction(270f));
        assertEquals(-170f, Minimap.angle(190f), 1e-4);
        assertEquals(180f, Minimap.angle(-180f), 1e-4);
    }

    @Test
    void markerCoordinatesAreHalfPixelsAroundTheCentre() {
        assertEquals(0, Minimap.cursorCoord(64));
        assertEquals(-128, Minimap.cursorCoord(0));
        assertEquals(127, Minimap.cursorCoord(128), "rechter Rand geklemmt");
        assertEquals(-128, Minimap.cursorCoord(-40), "weit draußen geklemmt");
    }

    @Test
    void distancesReadLikeAWalk() {
        assertEquals("230 m", Minimap.distanceText(230.4));
        assertEquals("1,5 km", Minimap.distanceText(1500));
        assertEquals("haus am see", Minimap.key("  Haus am See "));
    }

    @Test
    void concreteAtGroundIsRoadConcreteOnARoofIsNot() {
        byte[] base = new byte[256];
        short[] height = new short[256];
        boolean[] road = new boolean[256];
        for (int i = 0; i < 256; i++) {
            base[i] = 21;
            height[i] = 64;
        }
        // Feld 0/0: Straße auf Bodenhöhe. Feld 4/0: Hallendach 6 Blöcke höher, daneben Boden.
        road[0] = true;
        road[4] = true;
        height[4] = 70;
        // Feld 8/0 ganz Dach (alle 16 hoch) – dort ist der Feldboden das Dach selbst; der Hohlraum-Test beim Lesen
        // hält es fern, hier steht der Belag ohne Höhenunterschied und gilt als Straße.
        Minimap.markRoads(base, height, road);
        assertTrue((base[0] & MinimapImage.ROAD) != 0, "Fahrbahn");
        assertFalse((base[4] & MinimapImage.ROAD) != 0, "Dach über dem Boden im selben Feld");
        assertFalse((base[1] & MinimapImage.ROAD) != 0, "kein Belag, keine Straße");
        assertEquals(21, base[1] & 0x7F, "Grundfarbe bleibt");
    }

    /**
     * Ganz herausgezoomt (64 Blöcke je Punkt) und schräg gedreht berührt das Bild höchstens ~300 Regionen – weniger als
     * der Speicher hält (512). Der Umkreis der Drehung allein wären über 500 gewesen.
     */
    @Test
    void farOutTheMapTouchesFewerRegionsThanTheStoreHolds() {
        for (float yaw : new float[] {180f, 135f, 100f}) {
            var v = MinimapImage.View.headingUp(0, 0, MinimapSettings.MAX_ZOOM, yaw);
            var regions = Minimap.visibleRegions(v);
            assertTrue(regions.size() <= 320, "Blick " + yaw + ": " + regions.size() + " Regionen");
            assertTrue(regions.size() >= 256, "das Bild selbst ist 16 × 16 Regionen groß");
            assertEquals(java.util.Set.of(MinimapImage.chunkKey(-1, -1), MinimapImage.chunkKey(0, -1),
                    MinimapImage.chunkKey(-1, 0), MinimapImage.chunkKey(0, 0)),
                    new java.util.HashSet<>(regions.subList(0, 4)), "die vier Regionen um die Mitte zuerst");
        }
    }
}
