package com.blackn0va.baumeister.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.terrain.ElevationTiles;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fenster-Relief aus groben Kacheln: Ebene mit einem einzelnen hohen Pixel, ohne Netz. */
class TileReliefTest {

    private static final int ZOOM = 10;

    /** Terrarium-PNG: überall base, an Pixel (spikeX, spikeY) der Wert spike (−1 = kein Ausreißer). */
    private static byte[] png(double base, int spikeX, int spikeY, double spike) {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                double v = (x == spikeX && y == spikeY ? spike : base) + 32768;
                int r = (int) Math.floor(v / 256);
                int g = (int) Math.floor(v) - r * 256;
                img.setRGB(x, y, r << 16 | g << 8);
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

    @Test
    void findsANarrowPeakInsideTheWindowAndIgnoresItOutside(@TempDir Path dir) throws Exception {
        // Gipfel an einem Pixel nahe Hepberg; dort 3 000 m, sonst 400 m.
        double peakLat = 48.9;
        double peakLon = 11.5;
        int gx = (int) Math.floor(ElevationTiles.tileX(peakLon, ZOOM) * 256);
        int gy = (int) Math.floor(ElevationTiles.tileY(peakLat, ZOOM) * 256);
        ElevationTiles tiles = new ElevationTiles(dir, ZOOM, 32, (zoom, tx, ty) ->
                tx == gx >> 8 && ty == gy >> 8 ? png(400, gx & 255, gy & 255, 3000) : png(400, -1, -1, 0));
        TileRelief relief = new TileRelief(tiles);
        double pLat = ElevationTiles.pixelLat(gy, ZOOM);
        double pLon = ElevationTiles.pixelLon(gx, ZOOM);
        // Gleich daneben: voll.
        assertEquals(3000, relief.minMax(pLat - 1_000 / 110_540.0, pLon)[1], 1e-3);
        // 10 km südlich: im Fenster, aber mit Abstand abgeschwächt (3 000 − 0,15 · (10 000 − 1 600) = 1 740).
        double[] near = relief.minMax(pLat - 10_000 / 110_540.0, pLon);
        assertEquals(400, near[0], 1e-3);
        assertEquals(1740, near[1], 25);
        // 20 km südlich: im Fenster, aber abgeschwächt unter den Grund (3 000 − 2 760 = 240 < 400).
        double[] far = relief.minMax(pLat - 20_000 / 110_540.0, pLon);
        assertEquals(400, far[0], 1e-3);
        assertEquals(400, far[1], 1e-3);
    }

    /**
     * Gemessen 2026-10-28 vor der Abschwächung: Ein Berg von 3 500 m auf 600 m Grund kippte das Flachland 15 km weiter
     * mit 54,5 % Steigung (Bezug sprang an der Fenstergrenze). Jetzt: höchstens 15 % künstliche Neigung, Gipfel unter
     * der Decke, Flachland jenseits von ~12 km wieder auf echter Meereshöhe.
     */
    @Test
    void mountainTiltsTheLandAtMostFifteenPercent(@TempDir Path dir) throws Exception {
        double peakLat = 47.3;
        double peakLon = 11.0;
        double sigma = 3_000;
        ElevationTiles tiles = new ElevationTiles(dir, ZOOM, 64, (zoom, tx, ty) -> bell(tx, ty, peakLat, peakLon,
                sigma));
        HeightModel model = new HeightModel(new TileRelief(tiles), 200);
        double peakY = 3_500 - model.shift(peakLat, peakLon);
        assertTrue(peakY <= HeightModel.TOP_Y + 1 && peakY > 1_900, "Gipfel bei y " + peakY);
        double step = 100;
        double previous = model.shift(peakLat, peakLon);
        double steepest = 0;
        for (double d = step; d <= 25_000; d += step) {
            double s = model.shift(peakLat - d / 110_540.0, peakLon);
            steepest = Math.max(steepest, Math.abs(s - previous) / step);
            previous = s;
        }
        assertTrue(steepest <= 0.16, "Neigung durch den Bezug " + steepest);
        assertEquals(0, model.shift(peakLat - 15_000 / 110_540.0, peakLon), 1e-9, "Flachland wieder echt");
    }

    /** Glockenberg 600 + 2 900 · exp(−d²/2σ²) als Terrarium-Kachel. */
    private static byte[] bell(int tx, int ty, double lat0, double lon0, double sigma) {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        double cos = Math.cos(Math.toRadians(lat0));
        for (int y = 0; y < 256; y++) {
            double dz = (ElevationTiles.pixelLat(ty * 256 + y, ZOOM) - lat0) * 110_540.0;
            for (int x = 0; x < 256; x++) {
                double dx = (ElevationTiles.pixelLon(tx * 256 + x, ZOOM) - lon0) * 111_320.0 * cos;
                double v = 600 + 2_900 * Math.exp(-(dx * dx + dz * dz) / (2 * sigma * sigma)) + 32768;
                int r = (int) Math.floor(v / 256);
                int g = (int) Math.floor(v) - r * 256;
                img.setRGB(x, y, r << 16 | g << 8);
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

    @Test
    void windowWrapsAcrossTheDateLine(@TempDir Path dir) throws Exception {
        int n = 1 << ZOOM;
        ElevationTiles tiles = new ElevationTiles(dir, ZOOM, 32, (zoom, tx, ty) ->
                tx == n - 1 ? png(1200, -1, -1, 0) : png(10, -1, -1, 0));
        double[] mm = new TileRelief(tiles).minMax(0, -179.99);
        assertEquals(10, mm[0], 1e-3);
        assertEquals(1200, mm[1], 1e-3);
    }
}
