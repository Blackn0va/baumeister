package com.blackn0va.baumeister.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Höhe samt Rohpixeln (ElevationTiles.elevation mit corners): Wert für Wert dasselbe wie die bisherige Rechnung über
 * pixel() – auch an Kachelrändern und über die Datumsgrenze. Die Erdwelt darf sich durch die schnellere Abkürzung
 * (eine Kachel statt vier Zugriffe) um keinen Block verschieben.
 */
class ElevationCornersTest {

    /** Jedes Pixel anders, mit Nachkommaanteil: global (gx, gy) → Höhe. */
    static double value(int gx, int gy) {
        return ((gx * 37 + gy * 101) % 2000) - 700 + ((gx * 7 + gy * 13) % 256) / 256.0;
    }

    static byte[] png(int zoom, int tx, int ty) {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                double v = value(tx * 256 + x, ty * 256 + y) + 32768;
                int r = (int) Math.floor(v / 256);
                int g = (int) Math.floor(v) - r * 256;
                int b = (int) Math.round((v - Math.floor(v)) * 256);
                img.setRGB(x, y, r << 16 | g << 8 | b);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Die Rechnung vor der Abkürzung, Zeile für Zeile. */
    static double reference(ElevationTiles t, double lat, double lon) throws IOException {
        double la = Math.max(-85.0511287798, Math.min(85.0511287798, lat));
        double fx = ElevationTiles.tileX(lon, t.zoom()) * 256 - 0.5;
        double fy = ElevationTiles.tileY(la, t.zoom()) * 256 - 0.5;
        int px = (int) Math.floor(fx);
        int py = (int) Math.floor(fy);
        double ax = fx - px;
        double ay = fy - py;
        double h00 = t.pixel(px, py);
        double h10 = t.pixel(px + 1, py);
        double h01 = t.pixel(px, py + 1);
        double h11 = t.pixel(px + 1, py + 1);
        return (h00 * (1 - ax) + h10 * ax) * (1 - ay) + (h01 * (1 - ax) + h11 * ax) * ay;
    }

    @Test
    void cornersVariantGivesBitIdenticalHeightsEverywhere(@TempDir Path dir) throws IOException {
        ElevationTiles t = new ElevationTiles(dir, 2, 64, ElevationCornersTest::png);
        double[] corners = new double[4];
        int checked = 0;
        for (double lat = -86; lat <= 86; lat += 0.731) {
            for (double lon = -180; lon <= 180; lon += 0.917) {
                double expected = reference(t, lat, lon);
                double got = t.elevation(lat, lon, corners);
                assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(got), lat + "," + lon);
                assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(t.elevation(lat, lon)));
                checked++;
            }
        }
        // Kachelränder bei Zoom 2: Pixelspalte 255/256 jeder Kachel und die Datumsgrenze.
        for (int gx = 250; gx < 1030; gx += 1) {
            double lon = ElevationTiles.pixelLon(gx, 2) + 0.03;
            double lat = ElevationTiles.pixelLat(255, 2) - 0.01;
            assertEquals(Double.doubleToLongBits(reference(t, lat, lon)),
                    Double.doubleToLongBits(t.elevation(lat, lon, corners)), "Rand " + gx);
            checked++;
        }
        assertEquals(true, checked > 40_000);
    }

    @Test
    void cornersAreTheFourRawPixels(@TempDir Path dir) throws IOException {
        ElevationTiles t = new ElevationTiles(dir, 2, 64, ElevationCornersTest::png);
        double lat = ElevationTiles.pixelLat(300, 2) - 0.2;
        double lon = ElevationTiles.pixelLon(700, 2) + 0.1;
        double[] corners = new double[4];
        t.elevation(lat, lon, corners);
        double fx = ElevationTiles.tileX(lon, 2) * 256 - 0.5;
        double fy = ElevationTiles.tileY(lat, 2) * 256 - 0.5;
        int px = (int) Math.floor(fx);
        int py = (int) Math.floor(fy);
        assertArrayEquals(new double[] {t.pixel(px, py), t.pixel(px + 1, py), t.pixel(px, py + 1),
            t.pixel(px + 1, py + 1)}, corners);
    }
}
