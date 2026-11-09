package com.blackn0va.baumeister.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/** Höhendaten ohne Netz: Terrarium-Formel, Kachelrechnung. */
class TerrainTest {

    /** Terrarium: Höhe = R·256 + G + B/256 − 32768. */
    @Test
    void terrariumPixelsDecodeToMetres() throws Exception {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        int[][] cases = {{2500, 0}, {-100, 1}, {8848, 2}, {0, 3}};
        for (int[] c : cases) {
            int v = c[0] + 32768;
            img.setRGB(c[1], 0, (v / 256) << 16 | (v % 256) << 8);
        }
        img.setRGB(4, 0, (128 << 16) | (0 << 8) | 128);  // 32768 + 0,5 − 32768 = 0,5 m
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        float[] h = ElevationTiles.decode(out.toByteArray());
        assertEquals(2500f, h[0], 1e-3);
        assertEquals(-100f, h[1], 1e-3);
        assertEquals(8848f, h[2], 1e-3);
        assertEquals(0f, h[3], 1e-3);
        assertEquals(0.5f, h[4], 1e-3);
    }

    @Test
    void tileCoordinatesFollowWebMercator() {
        assertEquals(1 << 14, ElevationTiles.tileX(0, 15), 1e-9);
        assertEquals(1 << 14, ElevationTiles.tileY(0, 15), 1e-9);
        // Grand Canyon Village 36,0544 N 112,1401 W, Zoom 15, von Hand gerechnet:
        // x = (−112,1401 + 180) / 360 · 32768 = 6176,8; y = (1 − ln(tan φ + sec φ)/π) / 2 · 32768 = 12 861,7.
        assertEquals(6176, (int) Math.floor(ElevationTiles.tileX(-112.1401, 15)));
        assertEquals(12861, (int) Math.floor(ElevationTiles.tileY(36.0544, 15)));
    }
}
