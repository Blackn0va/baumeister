package com.blackn0va.baumeister.earth;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Senkrechte Abbildung der Erdwelt: y = round(e − shift), e = Meereshöhe in Metern.
 *
 * Warum ein örtlicher Bezug (shift): Die Welt ist 4 064 Blöcke hoch (y −2032..2031), die Erde von der Tiefsee bis zum
 * Everest rund 20 km. 1:1 passt nur ein Ausschnitt. Wo im Umkreis von 15 km nichts über 1 990 m liegt (fast überall:
 * Flachland, Mittelgebirge, Küsten), ist shift = 0 und y die echte Meereshöhe. Im Hochgebirge wird um so viel
 * abgesenkt, dass der höchste Punkt im Fenster bei y 1990 liegt – aber nie so weit, dass der tiefste Punkt unter
 * y −1900 fiele. Passt beides nicht (mehr als 3 890 m Relief im Fenster, z. B. Everest, Nanga Parbat), liegt shift in
 * der Mitte zwischen beiden Grenzen: Gipfel oben und Talgründe unten werden zu gleichen Teilen gekappt.
 *
 * Stetigkeit: Ein Bezug je Ort (wie TerrainService.vertical) gäbe an der Grenze zweier Orte eine Stufe von hunderten
 * Metern. Deshalb wird shift auf einem festen Gitter von 0,02° (≈ 2,2 km) berechnet und dazwischen bilinear
 * gemischt – stetig in jede Richtung, für alle Chunks gleich, egal in welcher Reihenfolge sie entstehen. Wer das
 * Gitter oder die Grenzen ändert, verschiebt jeden noch nicht erzeugten Chunk gegen die schon erzeugten.
 *
 * Meere: Der Meeresboden liegt höchstens seaDepthMax (Vorgabe 200) unter dem Meer; tiefer wird angehoben. Die Tiefsee
 * 1:1 passte nicht in die Welt und kostete je Säule bis zu 4 000 Wasserblöcke.
 *
 * Threads: Gitterwerte in einer ConcurrentHashMap; wird sie zu groß, wird sie geleert (Werte sind reproduzierbar).
 */
public final class HeightModel {

    /** Gitterweite des Bezugs in Grad. */
    public static final double GRID_DEG = 0.02;
    /** Höchster Punkt des Fensters soll höchstens hier liegen (41 Blöcke Luft bis zur Decke 2031). */
    public static final double TOP_Y = 1990;
    /** Tiefster Punkt des Fensters soll mindestens hier liegen (darunter 64 Blöcke Grundgestein bis −2032). */
    public static final double BOTTOM_Y = -1900;
    private static final int LAT_STEPS = (int) Math.round(90 / GRID_DEG);
    private static final int LON_STEPS = (int) Math.round(360 / GRID_DEG);
    /** ≈ 20 MB; eine Erkundung braucht ein paar hundert Gitterpunkte. */
    private static final int CACHE_LIMIT = 250_000;

    private final LocalRelief relief;
    private final double seaDepthMax;
    private final Map<Long, Double> grid = new ConcurrentHashMap<>();

    public HeightModel(LocalRelief relief, double seaDepthMax) {
        this.relief = relief;
        this.seaDepthMax = seaDepthMax;
    }

    public double seaDepthMax() {
        return seaDepthMax;
    }

    /** Meereshöhe mit angehobenem Meeresboden. */
    public double seabed(double e) {
        return Math.max(e, -seaDepthMax);
    }

    /**
     * Bezug aus tiefstem und höchstem Punkt eines Fensters (schon mit angehobenem Meeresboden): so wenig wie möglich,
     * so viel wie nötig, bei zu viel Relief die Mitte.
     */
    public static double shiftFor(double min, double max) {
        double need = Math.max(0, max - TOP_Y);
        double cap = Math.max(0, min - BOTTOM_Y);
        if (need <= cap) {
            return need;
        }
        return (need + cap) / 2;
    }

    /** Bezug an (lat, lon), bilinear zwischen den vier umgebenden Gitterpunkten. */
    public double shift(double lat, double lon) throws IOException {
        double fi = lat / GRID_DEG;
        double fj = lon / GRID_DEG;
        int i = (int) Math.floor(fi);
        int j = (int) Math.floor(fj);
        double a = fi - i;
        double b = fj - j;
        double s00 = node(i, j);
        double s01 = node(i, j + 1);
        double s10 = node(i + 1, j);
        double s11 = node(i + 1, j + 1);
        return (s00 * (1 - b) + s01 * b) * (1 - a) + (s10 * (1 - b) + s11 * b) * a;
    }

    /** Bezug an Gitterpunkt (i, j) = (i · 0,02°, j · 0,02°); Länge über die Datumsgrenze umgebrochen. */
    double node(int i, int j) throws IOException {
        int ci = Math.max(-LAT_STEPS, Math.min(LAT_STEPS, i));
        int cj = Math.floorMod(j + LON_STEPS / 2, LON_STEPS) - LON_STEPS / 2;
        long key = (long) ci << 32 | (cj & 0xffffffffL);
        Double cached = grid.get(key);
        if (cached != null) {
            return cached;
        }
        double[] mm = relief.minMax(ci * GRID_DEG, cj * GRID_DEG);
        double s = shiftFor(seabed(mm[0]), seabed(mm[1]));
        if (grid.size() > CACHE_LIMIT) {
            grid.clear();
        }
        grid.put(key, s);
        return s;
    }
}
