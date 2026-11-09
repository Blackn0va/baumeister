package com.blackn0va.baumeister.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Höhenbezug (shift) der Erdwelt mit synthetischem Gelände – ohne Kacheln und Netz. */
class HeightModelTest {

    /** Ein Gipfel als Glocke über einer Ebene; Fenster wie TileRelief (15 km), abgetastet alle 250 m. */
    static LocalRelief mountain(double peakLat, double peakLon, double base, double peak, double sigmaM) {
        Elevation e = mountainElevation(peakLat, peakLon, base, peak, sigmaM);
        return (lat, lon) -> {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            double cos = Math.cos(Math.toRadians(lat));
            for (double dz = -15_000; dz <= 15_000; dz += 250) {
                for (double dx = -15_000; dx <= 15_000; dx += 250) {
                    if (dx * dx + dz * dz > 15_000.0 * 15_000) {
                        continue;
                    }
                    double v = e.at(lat + dz / 110_540, lon + dx / (111_320 * cos));
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
            }
            return new double[] {min, max};
        };
    }

    static Elevation mountainElevation(double peakLat, double peakLon, double base, double peak, double sigmaM) {
        return (lat, lon) -> {
            double dz = (lat - peakLat) * 110_540;
            double dx = (lon - peakLon) * 111_320 * Math.cos(Math.toRadians(peakLat));
            return base + (peak - base) * Math.exp(-(dx * dx + dz * dz) / (2 * sigmaM * sigmaM));
        };
    }

    @Test
    void flatlandKeepsTheRealSeaLevel() throws Exception {
        assertEquals(0, HeightModel.shiftFor(300, 600));
        assertEquals(0, HeightModel.shiftFor(-200, 1990));
        HeightModel m = new HeightModel((lat, lon) -> new double[] {380, 520}, 200);
        assertEquals(0, m.shift(48.82, 11.46));
    }

    @Test
    void alpsAreLoweredUntilThePeakSitsAt1990() {
        // Zugspitze 2 962 m, Loisachtal 700 m im Fenster.
        double s = HeightModel.shiftFor(700, 2962);
        assertEquals(972, s, 1e-9);
        assertEquals(1990, 2962 - s, 1e-9);
        assertTrue(700 - s >= HeightModel.BOTTOM_Y);
    }

    @Test
    void everestReliefTakesTheMiddleAndClipsBothEnds() {
        // 4 848 m Relief im Fenster > 3 890 m Platz: Mitte zwischen „Gipfel auf 1 990“ und „Tal auf −1 900“.
        double s = HeightModel.shiftFor(4000, 8848);
        assertEquals((6858 + 5900) / 2.0, s, 1e-9);
        assertEquals(1990 + 479, 8848 - s, 1e-9);
        assertEquals(-1900 - 479, 4000 - s, 1e-9);
    }

    @Test
    void seaFloorIsRaisedAndTheSeaKeepsLevelZero() {
        HeightModel m = new HeightModel((lat, lon) -> new double[] {-5000, 30}, 200);
        assertEquals(-200, m.seabed(-5000));
        assertEquals(-50, m.seabed(-50));
        assertEquals(0, HeightModel.shiftFor(m.seabed(-5000), 30));
    }

    @Test
    void shiftIsContinuousAcrossTheGrid() throws Exception {
        HeightModel m = new HeightModel(mountain(47.3, 11.0, 600, 3500, 3000), 200);
        double previous = m.shift(47.3, 10.6);
        double maxStep = 0;
        double peakShift = 0;
        // Ost-West durch den Gipfel, alle 0,0005° (≈ 38 m): keine Stufe, nur Rampen zwischen den Gitterpunkten.
        for (double lon = 10.6005; lon <= 11.4; lon += 0.0005) {
            double s = m.shift(47.3, lon);
            maxStep = Math.max(maxStep, Math.abs(s - previous));
            peakShift = Math.max(peakShift, s);
            previous = s;
        }
        assertTrue(maxStep < 40, "größter Sprung " + maxStep + " m auf 38 m");
        assertEquals(1510, peakShift, 60);
        assertEquals(0, m.shift(47.3, 10.6), 1e-9, "30 km vom Gipfel: Flachland");
        // Genau an einer Gitterlinie stimmen beide Seiten überein.
        double onLine = 11.0 + HeightModel.GRID_DEG / 2;
        double lineLat = Math.round(47.31 / HeightModel.GRID_DEG) * HeightModel.GRID_DEG;
        assertEquals(m.shift(lineLat - 1e-9, onLine), m.shift(lineLat + 1e-9, onLine), 1e-3);
    }

    @Test
    void peakEndsUpBelowTheCeiling() throws Exception {
        Elevation e = mountainElevation(47.3, 11.0, 600, 3500, 3000);
        HeightModel m = new HeightModel(mountain(47.3, 11.0, 600, 3500, 3000), 200);
        double y = e.at(47.3, 11.0) - m.shift(47.3, 11.0);
        assertTrue(y <= HeightModel.TOP_Y + 1, "Gipfel bei y " + y);
        assertTrue(y > 1900, "Gipfel bei y " + y);
    }

    @Test
    void longitudeWrapsAtTheDateLine() throws Exception {
        HeightModel m = new HeightModel((lat, lon) -> new double[] {0, lon > 179 || lon < -179 ? 2500 : 0}, 200);
        assertEquals(m.shift(10, 180), m.shift(10, -180), 1e-9);
        assertEquals(510, m.shift(10, 179.999), 1e-6);
    }
}
