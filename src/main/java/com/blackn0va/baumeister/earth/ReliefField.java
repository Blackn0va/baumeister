package com.blackn0va.baumeister.earth;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wirksames Relief aus zwei Stufen (siehe TileRelief): nah genau, fern grob und weit. Gilt das Stärkere – tiefster
 * Wert aus beiden Minima, höchster aus beiden Maxima.
 *
 * Warum die ferne Stufe ein eigenes, grobes Gitter hat: Ein Fenster von 700 km liest rund 0,6 Mio. Pixel. Auf dem
 * feinen Gitter des HeightModel (0,02°) wären das Hunderte Fenster je Quadratkilometer Erkundung. Die ferne Stufe
 * neigt um höchstens 1 %, zwischen Gitterpunkten im Abstand von 0,25° (≈ 28 km) bilinear gemischt ist sie stetig und
 * weicht um wenige Meter von der genauen Rechnung ab – die Decke schützt die nahe Stufe, nicht diese.
 *
 * Threads: threadsicher (ConcurrentHashMap; doppelte Rechnung desselben Gitterpunkts ist harmlos).
 */
public final class ReliefField implements LocalRelief {

    /** Gitterweite der fernen Stufe in Grad. */
    public static final double FAR_GRID_DEG = 0.25;
    private static final int CACHE_LIMIT = 50_000;

    private final LocalRelief near;
    private final LocalRelief far;
    private final Map<Long, double[]> farGrid = new ConcurrentHashMap<>();

    public ReliefField(LocalRelief near, LocalRelief far) {
        this.near = near;
        this.far = far;
    }

    @Override
    public double[] minMax(double lat, double lon) throws IOException {
        double[] n = near.minMax(lat, lon);
        double[] f = farAt(lat, lon);
        return new double[] {Math.min(n[0], f[0]), Math.max(n[1], f[1])};
    }

    /** Ferne Stufe an (lat, lon), bilinear zwischen den vier umgebenden 0,25°-Gitterpunkten. */
    double[] farAt(double lat, double lon) throws IOException {
        double fi = lat / FAR_GRID_DEG;
        double fj = lon / FAR_GRID_DEG;
        int i = (int) Math.floor(fi);
        int j = (int) Math.floor(fj);
        double a = fi - i;
        double b = fj - j;
        double[] s00 = farNode(i, j);
        double[] s01 = farNode(i, j + 1);
        double[] s10 = farNode(i + 1, j);
        double[] s11 = farNode(i + 1, j + 1);
        double[] out = new double[2];
        for (int k = 0; k < 2; k++) {
            out[k] = (s00[k] * (1 - b) + s01[k] * b) * (1 - a) + (s10[k] * (1 - b) + s11[k] * b) * a;
        }
        return out;
    }

    private double[] farNode(int i, int j) throws IOException {
        int steps = (int) Math.round(90 / FAR_GRID_DEG);
        int lonSteps = (int) Math.round(360 / FAR_GRID_DEG);
        int ci = Math.max(-steps, Math.min(steps, i));
        int cj = Math.floorMod(j + lonSteps / 2, lonSteps) - lonSteps / 2;
        long key = (long) ci << 32 | (cj & 0xffffffffL);
        double[] cached = farGrid.get(key);
        if (cached != null) {
            return cached;
        }
        double[] mm = far.minMax(ci * FAR_GRID_DEG, cj * FAR_GRID_DEG);
        if (farGrid.size() > CACHE_LIMIT) {
            farGrid.clear();
        }
        farGrid.put(key, mm);
        return mm;
    }
}
