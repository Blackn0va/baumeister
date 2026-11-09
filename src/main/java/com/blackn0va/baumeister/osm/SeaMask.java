package com.blackn0va.baumeister.osm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.blackn0va.baumeister.osm.CityModel.P;

/**
 * Meer und Land eines Rechtecks aus den OSM-Küstenlinien (natural=coastline) – für den Weltbau der Erdwelt.
 *
 * Warum: Das Höhenmodell (Terrarium) kennt viele Häfen und Buchten nicht. Gemessen 2026-11-01 an Sydney (Zoom 15,
 * 845 Proben über Port Jackson und Darling Harbour, TerrariumGridProbe): kein einziger Wert genau 0, das Hafenbecken
 * 0–5 m mit Streuung unter 0, Cockle Bay 11,7 m. Die Wasserregeln des Generators (WaterTable) machten daraus Land –
 * Sydneys Innenstadt stand an einer grünen Böschung statt am Hafen. Die Küstenlinie aus OSM ist dort genau.
 *
 * Grundlage: In OSM liegt Land LINKS der Laufrichtung einer Küstenlinie. OsmImport dreht jede Linie so, dass das
 * auch in Kachelkoordinaten gilt (die Erdprojektion spiegelt: Nord = −z). Ablauf:
 *  1. Zellen, die eine Küstenlinie berührt, sind Ufer (zählen als Land).
 *  2. Die übrigen Zellen zerfallen in zusammenhängende Gebiete; je Gebiet entscheidet die Seite der nächsten
 *     Küstenlinie (Mehrheit aus bis zu neun Proben), ob Meer oder Land.
 *  3. Abstand jeder Meereszelle zum Land (Tiefe) und jeder Landzelle zum Meer (Strand), je bis {@link #CAP}.
 * Die nächste Linie wird über ALLE Küstenlinien der Karte gesucht, nicht nur im Rechteck: Ein Ausschnitt mitten im
 * Hafen enthält keine Küste, gehört aber trotzdem zum Meer.
 *
 * Threads: unveränderlich nach of(). Fehler: keine; ohne Küstenlinien liefert of() null (das Höhenmodell entscheidet).
 */
public final class SeaMask {

    /** Größter gezählter Abstand in Zellen – darüber gilt „weit“. */
    public static final int CAP = 64;

    private static final byte LAND = 0;
    private static final byte SEA = 1;
    private static final byte COAST = 2;

    private final int x0;
    private final int z0;
    private final int w;
    private final int h;
    private final byte[] state;
    /** Meer: Abstand zum Land; Land/Ufer: Abstand zum Meer. */
    private final short[] dist;

    private SeaMask(int x0, int z0, int w, int h, byte[] state, short[] dist) {
        this.x0 = x0;
        this.z0 = z0;
        this.w = w;
        this.h = h;
        this.state = state;
        this.dist = dist;
    }

    /** Nur aus Küstenlinien – siehe {@link #of(List, Set, int, int, int, int)}. */
    public static SeaMask of(List<List<P>> coasts, int x0, int z0, int x1, int z1) {
        return of(coasts, Set.of(), x0, z0, x1, z1);
    }

    /**
     * Maske für [x0, x1) × [z0, z1). marine: Zellen aus Meeresflächen (Wasser-Relation mit water=harbour …) – sie sind
     * immer Meer, auch ohne Küstenlinie (Sydney). null, wenn es weder Küstenlinie noch Meeresfläche gibt (dann bleibt das
     * Höhenmodell maßgeblich).
     */
    public static SeaMask of(List<List<P>> coasts, Set<P> marine, int x0, int z0, int x1, int z1) {
        List<double[]> segs = segments(coasts);
        if ((segs.isEmpty() && marine.isEmpty()) || x1 <= x0 || z1 <= z0) {
            return null;
        }
        int w = x1 - x0;
        int h = z1 - z0;
        byte[] state = new byte[w * h];
        // 1. Ufer: jede Strecke in halben Zellschritten abtasten.
        for (double[] s : segs) {
            double len = Math.hypot(s[2] - s[0], s[3] - s[1]);
            int steps = Math.max(1, (int) Math.ceil(len * 2));
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                int cx = (int) Math.floor(s[0] + t * (s[2] - s[0])) - x0;
                int cz = (int) Math.floor(s[1] + t * (s[3] - s[1])) - z0;
                if (cx >= 0 && cz >= 0 && cx < w && cz < h) {
                    state[cz * w + cx] = COAST;
                }
            }
        }
        // 2. Gebiete ohne Ufer füllen und je Gebiet die Seite bestimmen.
        int[] comp = new int[w * h];
        int label = 0;
        int[] queue = new int[w * h];
        for (int start = 0; start < w * h; start++) {
            if (state[start] == COAST || comp[start] != 0) {
                continue;
            }
            label++;
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            comp[start] = label;
            List<Integer> cells = new ArrayList<>();
            while (head < tail) {
                int i = queue[head++];
                cells.add(i);
                int x = i % w;
                int z = i / w;
                if (x > 0) {
                    tail = visit(state, comp, queue, tail, i - 1, label);
                }
                if (x + 1 < w) {
                    tail = visit(state, comp, queue, tail, i + 1, label);
                }
                if (z > 0) {
                    tail = visit(state, comp, queue, tail, i - w, label);
                }
                if (z + 1 < h) {
                    tail = visit(state, comp, queue, tail, i + w, label);
                }
            }
            int votes = 0;
            int samples = Math.min(9, cells.size());
            for (int k = 0; k < samples; k++) {
                int i = cells.get((int) ((long) k * cells.size() / samples));
                votes += landSide(coasts, x0 + i % w + 0.5, z0 + i / w + 0.5) ? -1 : 1;
            }
            byte kind = votes > 0 ? SEA : LAND;
            for (int i : cells) {
                state[i] = kind;
            }
        }
        // Ohne Küstenlinie hat Schritt 2 nichts zu entscheiden (keine Linie für die Seite): alles Land, dann nur die
        // Meeresflächen.
        if (segs.isEmpty()) {
            java.util.Arrays.fill(state, LAND);
        }
        for (P p : marine) {
            int cx = p.x() - x0;
            int cz = p.z() - z0;
            if (cx >= 0 && cz >= 0 && cx < w && cz < h) {
                state[cz * w + cx] = SEA;
            }
        }
        // 3. Abstände: Meer → Land (Tiefe), Land/Ufer → Meer (Strand).
        short[] dist = new short[w * h];
        distances(state, dist, w, h, true);
        distances(state, dist, w, h, false);
        return new SeaMask(x0, z0, w, h, state, dist);
    }

    private static int visit(byte[] state, int[] comp, int[] queue, int tail, int j, int label) {
        if (state[j] != COAST && comp[j] == 0) {
            comp[j] = label;
            queue[tail++] = j;
        }
        return tail;
    }

    /** Mehrquellen-Breitensuche: für Meer (toLand) bzw. Land den Abstand zur jeweils anderen Seite. */
    private static void distances(byte[] state, short[] dist, int w, int h, boolean seaCells) {
        ArrayDeque<Integer> q = new ArrayDeque<>();
        boolean[] seen = new boolean[w * h];
        for (int i = 0; i < w * h; i++) {
            boolean isSea = state[i] == SEA;
            if (isSea != seaCells) {
                // Quelle: die andere Seite
                seen[i] = true;
                q.add(i);
            } else {
                dist[i] = CAP;
            }
        }
        while (!q.isEmpty()) {
            int i = q.poll();
            int d = (state[i] == SEA) == seaCells ? dist[i] : 0;
            if (d >= CAP) {
                continue;
            }
            int x = i % w;
            int z = i / w;
            int[] next = {x > 0 ? i - 1 : -1, x + 1 < w ? i + 1 : -1, z > 0 ? i - w : -1, z + 1 < h ? i + w : -1};
            for (int j : next) {
                if (j >= 0 && !seen[j]) {
                    seen[j] = true;
                    dist[j] = (short) (d + 1);
                    q.add(j);
                }
            }
        }
    }

    private static List<double[]> segments(List<List<P>> coasts) {
        List<double[]> out = new ArrayList<>();
        for (List<P> line : coasts) {
            for (int i = 0; i + 1 < line.size(); i++) {
                P a = line.get(i);
                P b = line.get(i + 1);
                if (a.x() != b.x() || a.z() != b.z()) {
                    out.add(new double[] {a.x(), a.z(), b.x(), b.z()});
                }
            }
        }
        return out;
    }

    /** Liegt der Punkt auf der Landseite (links) der nächsten Küstenlinie? */
    static boolean landSide(List<List<P>> coasts, double px, double pz) {
        double best = Double.MAX_VALUE;
        boolean land = false;
        for (List<P> line : coasts) {
            int n = line.size();
            for (int i = 0; i + 1 < n; i++) {
                P a = line.get(i);
                P b = line.get(i + 1);
                double dx = b.x() - a.x();
                double dz = b.z() - a.z();
                double len2 = dx * dx + dz * dz;
                if (len2 == 0) {
                    continue;
                }
                double t = ((px - a.x()) * dx + (pz - a.z()) * dz) / len2;
                if (t > 0 && t < 1) {
                    double qx = a.x() + t * dx;
                    double qz = a.z() + t * dz;
                    double d = Math.hypot(px - qx, pz - qz);
                    if (d < best) {
                        best = d;
                        land = cross(a, b, px, pz) > 0;
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                P v = line.get(i);
                double d = Math.hypot(px - v.x(), pz - v.z());
                if (d < best) {
                    best = d;
                    land = cornerLand(line, i, px, pz);
                }
            }
        }
        return land;
    }

    /**
     * Seite an Punkt i der Linie, wenn dieser Eckpunkt der nächste ist. Dann liegt die Stelle im Außenkeil der Ecke und
     * damit auf derselben Seite beider angrenzenden Strecken – eine genügt. Gegenprobe 2026-11-01: Eine eigene
     * Ecken-Regel (Links-/Rechtskurve) änderte kein Testergebnis und ist geometrisch gleichwertig; sie wurde wieder
     * entfernt. An einem Linienende zählt die eine vorhandene Strecke.
     */
    private static boolean cornerLand(List<P> line, int i, double px, double pz) {
        P v = line.get(i);
        if (i + 1 < line.size()) {
            return cross(v, line.get(i + 1), px, pz) > 0;
        }
        return cross(line.get(i - 1), v, px, pz) > 0;
    }

    private static double cross(P a, P b, double px, double pz) {
        return (b.x() - a.x()) * (pz - a.z()) - (b.z() - a.z()) * (px - a.x());
    }

    private int index(int x, int z) {
        int cx = x - x0;
        int cz = z - z0;
        return cx < 0 || cz < 0 || cx >= w || cz >= h ? -1 : cz * w + cx;
    }

    /** Meer laut Küstenlinie? Außerhalb des Rechtecks: nein. */
    public boolean sea(int x, int z) {
        int i = index(x, z);
        return i >= 0 && state[i] == SEA;
    }

    /** Meer: Abstand zum nächsten Land in Zellen (1 = direkt am Ufer), höchstens CAP; sonst 0. */
    public int toLand(int x, int z) {
        int i = index(x, z);
        return i >= 0 && state[i] == SEA ? dist[i] : 0;
    }

    /** Land oder Ufer: Abstand zum nächsten Meer in Zellen, höchstens CAP; Meer: 0. */
    public int toSea(int x, int z) {
        int i = index(x, z);
        return i < 0 ? CAP : state[i] == SEA ? 0 : dist[i];
    }
}
