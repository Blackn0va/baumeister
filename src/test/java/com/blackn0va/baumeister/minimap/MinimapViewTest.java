package com.blackn0va.baumeister.minimap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

/** Gedrehte Karte (Blickrichtung oben): Ausrichtung, Rückrechnung, Straßen, Rahmen, Füllmuster. */
class MinimapViewTest {

    private static final double EPS = 1e-9;

    @Test
    void lookingNorthIsTheNorthUpMap() {
        MinimapImage.View v = MinimapImage.View.headingUp(100, 100, 1, 180f);
        assertEquals(1, v.rightX(), EPS);
        assertEquals(0, v.rightZ(), EPS);
        MinimapImage.View n = MinimapImage.View.northUp(100, 100, 1);
        for (int p = 0; p < MinimapImage.SIZE; p += 7) {
            assertEquals(n.worldX(p, 30), v.worldX(p, 30));
            assertEquals(n.worldZ(p, 30), v.worldZ(p, 30));
        }
    }

    @Test
    void theTopOfTheMapIsWhereThePlayerLooks() {
        // Blick nach Süden (yaw 0): oben im Bild ist +z; Blick nach Osten (yaw −90): oben ist +x.
        MinimapImage.View south = MinimapImage.View.headingUp(0, 0, 1, 0f);
        assertTrue(south.worldZ(64, 10) > 40, "oben = Süden");
        assertTrue(south.worldX(120, 64) < -40, "rechts = Westen, wenn man nach Süden schaut");
        MinimapImage.View east = MinimapImage.View.headingUp(0, 0, 1, -90f);
        assertTrue(east.worldX(64, 10) > 40, "oben = Osten");
        assertTrue(east.worldZ(120, 64) > 40, "rechts = Süden, wenn man nach Osten schaut");
    }

    @Test
    void worldAndScreenAreInversesInEveryDirection() {
        for (float yaw = -180; yaw < 180; yaw += 17) {
            for (double b : new double[] {0.25, 1, 3}) {
                MinimapImage.View v = MinimapImage.View.headingUp(1234.7, -88.2, b, yaw);
                for (int px = 5; px < MinimapImage.SIZE; px += 23) {
                    for (int py = 3; py < MinimapImage.SIZE; py += 29) {
                        double u = px - MinimapImage.SIZE / 2 + 0.5;
                        double w = py - MinimapImage.SIZE / 2 + 0.5;
                        double x = v.centerX() + (u * v.rightX() + w * v.downX()) * b;
                        double z = v.centerZ() + (u * v.rightZ() + w * v.downZ()) * b;
                        assertEquals(px + 0.5, v.screenX(x, z), 1e-6, "yaw " + yaw);
                        assertEquals(py + 0.5, v.screenY(x, z), 1e-6, "yaw " + yaw);
                    }
                }
            }
        }
    }

    @Test
    void roadsAreWhiteAndUnshaded() {
        byte[] base = new byte[256];
        short[] height = new short[256];
        for (int i = 0; i < 256; i++) {
            base[i] = (byte) (25 | MinimapImage.ROAD);
            height[i] = (short) (64 + i / 16);  // Hang nach Süden
        }
        MinimapImage.Tile t = new MinimapImage.Tile(base, height);
        byte[] img = MinimapImage.compose((cx, cz) -> cx == 0 && cz == 0 ? t : null, 8, 8, 1, (byte) 0,
                new HashSet<>());
        assertEquals(MinimapImage.ROAD_BASE * 4 + 2, img[64 * MinimapImage.SIZE + 64] & 0xFF);
    }

    @Test
    void rotatedPlotOutlineIsDrawnAroundThePlayer() {
        byte[] img = new byte[MinimapImage.SIZE * MinimapImage.SIZE];
        MinimapImage.View v = MinimapImage.View.headingUp(100, 100, 1, 135f);  // schräg
        MinimapImage.outline(img, v, 80, 80, 119, 119, (byte) 50);
        int drawn = 0;
        for (byte b : img) {
            drawn += b == 50 ? 1 : 0;
        }
        // 4 Seiten von Randmitte zu Randmitte 39 Blöcke, um 45° gedreht: Bresenham setzt je Seite max(|dx|, |dy|) + 1 =
        // 39 · cos 45° + 1 ≈ 28 Pixel, vier Seiten mit gemeinsamen Ecken ≈ 4 · 28 − 4 = 108 … 112.
        assertTrue(drawn >= 106 && drawn <= 114, "Pixel im Rahmen: " + drawn);
        assertEquals(0, img[64 * MinimapImage.SIZE + 64], "Mitte (Spieler) bleibt frei");
    }

    @Test
    void unknownPixelsBecomeACheckerSoTheMapAlwaysFillsItsFrame() {
        byte[] img = new byte[MinimapImage.SIZE * MinimapImage.SIZE];
        img[5] = 33;
        MinimapImage.fillUnknown(img, (byte) 0, (byte) 10, (byte) 11);
        assertEquals(33, img[5], "bekannte Pixel bleiben");
        assertEquals(10, img[0]);
        assertEquals(11, img[4], "nach 4 Pixeln wechselt das Muster");
        for (byte b : img) {
            assertNotEquals(0, b, "kein durchsichtiges Pixel mehr");
        }
    }
}
