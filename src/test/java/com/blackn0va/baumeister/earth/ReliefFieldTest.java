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

/**
 * Zweistufiger Höhenbezug mit synthetischen Kacheln (Zoom 10 nah, Zoom 7 fern), ohne Netz. Anlass (2026-10-28): Mit
 * nur der nahen Stufe wäre am Grand Canyon der Rand abgesenkt worden, der Fluss nicht – die Schlucht flacher als 1:1.
 */
class ReliefFieldTest {

    private static final double LAT0 = 36.1;
    private static final double LON0 = -112.1;
    private static final double COS0 = Math.cos(Math.toRadians(LAT0));

    /** Hochfläche 2 400 m (60 km Radius) mit einer 6 km breiten Schlucht (750 m) in Nord-Süd-Richtung, sonst 600 m. */
    private static double canyon(double lat, double lon) {
        double dz = (lat - LAT0) * 110_540;
        double dx = (lon - LON0) * 111_320 * COS0;
        if (dx * dx + dz * dz > 60_000.0 * 60_000) {
            return 600;
        }
        return Math.abs(dx) < 3_000 ? 750 : 2_400;
    }

    private static HeightModel model(Path dir, Elevation e, boolean withFar) {
        ElevationTiles near = new ElevationTiles(dir.resolve("nah"), 10, 64, (z, tx, ty) -> png(e, z, tx, ty));
        ElevationTiles far = new ElevationTiles(dir.resolve("fern"), 7, 96, (z, tx, ty) -> png(e, z, tx, ty));
        LocalRelief relief = withFar ? new ReliefField(new TileRelief(near), TileRelief.far(far)) : new TileRelief(near);
        return new HeightModel(relief, 200);
    }

    @Test
    void wideCanyonKeepsItsDepthOneToOne(@TempDir Path dir) throws Exception {
        HeightModel m = model(dir, ReliefFieldTest::canyon, true);
        double rimLon = LON0 + 6_000 / (111_320 * COS0);
        double river = 750 - m.shift(LAT0, LON0);
        double rim = 2_400 - m.shift(LAT0, rimLon);
        double shown = rim - river;
        assertTrue(shown >= 0.95 * 1_650, "Schlucht im Spiel " + shown + " m statt 1 650 m");
        assertTrue(rim <= HeightModel.TOP_Y + 1, "Rand bei y " + rim);
    }

    /** Gegenprobe ohne ferne Stufe: die Schlucht wird sichtbar flacher – genau der Fehler, den die ferne Stufe behebt. */
    @Test
    void withoutTheFarFieldTheCanyonWouldBeFlattened(@TempDir Path dir) throws Exception {
        HeightModel m = model(dir, ReliefFieldTest::canyon, false);
        double rimLon = LON0 + 6_000 / (111_320 * COS0);
        double shown = (2_400 - m.shift(LAT0, rimLon)) - (750 - m.shift(LAT0, LON0));
        assertTrue(shown < 0.95 * 1_650, "ohne ferne Stufe " + shown);
    }

    /** Wie München und die Zugspitze: ein Gipfel 90 km weiter lässt das Flachland auf echter Meereshöhe. */
    @Test
    void lowlandFarFromAPeakKeepsTheRealSeaLevel(@TempDir Path dir) throws Exception {
        Elevation e = HeightModelTest.mountainElevation(LAT0, LON0, 500, 2_800, 2_000);
        HeightModel m = model(dir, e, true);
        double ninetyKmNorth = LAT0 + 90_000 / 110_540.0;
        assertEquals(0, m.shift(ninetyKmNorth, LON0), 1e-6);
        double peak = 2_800 - m.shift(LAT0, LON0);
        assertTrue(peak <= HeightModel.TOP_Y + 1 && peak > 1_900, "Gipfel bei y " + peak);
        // Dazwischen höchstens 1 % künstliche Neigung, sobald die nahe Stufe (12 km) vorbei ist.
        double prev = m.shift(LAT0 + 15_000 / 110_540.0, LON0);
        double steepest = 0;
        for (double d = 15_500; d <= 90_000; d += 500) {
            double s = m.shift(LAT0 + d / 110_540.0, LON0);
            steepest = Math.max(steepest, Math.abs(s - prev) / 500);
            prev = s;
        }
        assertTrue(steepest <= 0.012, "Neigung im Flachland " + steepest);
    }

    /** Terrarium-PNG einer Kachel, jedes Pixel an seiner Mitte aus der Funktion. */
    private static byte[] png(Elevation e, int zoom, int tx, int ty) {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (int y = 0; y < 256; y++) {
                double lat = ElevationTiles.pixelLat(ty * 256 + y, zoom);
                for (int x = 0; x < 256; x++) {
                    double v = e.at(lat, ElevationTiles.pixelLon(tx * 256 + x, zoom)) + 32768;
                    int r = (int) Math.floor(v / 256);
                    int g = (int) Math.floor(v) - r * 256;
                    img.setRGB(x, y, r << 16 | g << 8);
                }
            }
            ImageIO.write(img, "png", out);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }
}
