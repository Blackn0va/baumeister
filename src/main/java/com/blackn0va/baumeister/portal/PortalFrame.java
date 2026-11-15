package com.blackn0va.baumeister.portal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Findet die Öffnung eines leeren Portalrahmens aus Obsidian (2026-11-15, Portale der Lobby und Portalhalle
 * verbinden).
 *
 * Regeln wie Minecraft (PortalShape): Rechteck 2–21 breit und 3–21 hoch, ganz frei (Luft oder schon Portal), an allen
 * vier Seiten Obsidian, Ecken egal. Warum so streng: Ein Portalblock prüft bei jedem Nachbar-Update in seiner Ebene, ob
 * der Rahmen noch so aussieht. Eine Öffnung, die diese Regeln verletzt, ließe sich zwar entzünden, aber beim ersten
 * Update zerfiele das ganze Portal wieder – und das Register zeigte auf ein Portal, das es nicht mehr gibt.
 *
 * Gesucht wird in beiden senkrechten Ebenen (Öffnung entlang x oder entlang z); gilt genau eine, ist es die. Die Suche
 * ist auf 21 × 21 Zellen begrenzt, damit ein Blick durch eine große Halle nicht die ganze Halle abläuft.
 *
 * Threads: zustandslos, ohne Bukkit, auf jedem Thread aufrufbar (die Blocks-Quelle entscheidet). Fehler: keine
 * Ausnahme – ohne passende Öffnung ist {@link Result#opening()} null und {@link Result#reason()} sagt, woran es lag.
 */
public final class PortalFrame {

    /** Größte Öffnung wie in Minecraft. */
    public static final int MAX = 21;
    public static final int MIN_WIDTH = 2;
    public static final int MIN_HEIGHT = 3;

    /** Was an einer Stelle steht. */
    public interface Blocks {
        /** Obsidian – nur das hält ein Netherportal, weinender Obsidian nicht. */
        boolean frame(int x, int y, int z);

        /** Luft oder schon ein Portalblock: darf zur Öffnung gehören. */
        boolean open(int x, int y, int z);
    }

    /** X: Öffnung zieht sich entlang x (Rahmen in einer Ebene z = konstant), Portalblöcke mit axis=x. */
    public enum Axis {
        X, Z
    }

    /** Öffnung ab der Ecke unten links (kleinstes x bzw. z, kleinstes y). */
    public record Opening(Axis axis, int x0, int y0, int z0, int width, int height) {

        public List<int[]> cells() {
            List<int[]> out = new ArrayList<>(width * height);
            for (int y = y0; y < y0 + height; y++) {
                for (int a = 0; a < width; a++) {
                    out.add(axis == Axis.X ? new int[] {x0 + a, y, z0} : new int[] {x0, y, z0 + a});
                }
            }
            return out;
        }

        public boolean contains(int x, int y, int z) {
            if (y < y0 || y >= y0 + height) {
                return false;
            }
            return axis == Axis.X ? z == z0 && x >= x0 && x < x0 + width : x == x0 && z >= z0 && z < z0 + width;
        }
    }

    /**
     * opening oder (wenn null) der Grund. rank sagt, wie weit die Suche kam: 1 = lief ins Offene, 2 = rundum Obsidian,
     * aber zu groß, 3 = geschlossen, aber kein gültiges Rechteck. Scheitern beide Ebenen, zählt die mit dem höheren
     * Rang – vorher siegte der Text der Ebene, die sofort ins Freie lief (PortalFrameTest.twentyTwoWideIsTooLarge).
     */
    public record Result(Opening opening, String reason, int rank) {
        static Result found(Opening o) {
            return new Result(o, "", 4);
        }

        static Result missing(String reason, int rank) {
            return new Result(null, reason, rank);
        }

        public boolean found() {
            return opening != null;
        }
    }

    private PortalFrame() {
    }

    /** Öffnung, zu der die Zelle gehört – in der Ebene, in der sie ein gültiges Portal ergibt. */
    public static Result at(Blocks w, int x, int y, int z) {
        if (!w.open(x, y, z)) {
            return Result.missing("dort ist kein freier Block", 0);
        }
        Result ax = flood(w, Axis.X, x, y, z);
        Result az = flood(w, Axis.Z, x, y, z);
        if (ax.found() && az.found()) {
            return Result.missing("der Rahmen ist nicht eindeutig (rundum Obsidian)", 3);
        }
        return ax.rank() >= az.rank() ? ax : az;
    }

    /**
     * Blick von (ox, oy, oz) in Richtung (dx, dy, dz): die erste Öffnung auf dem Weg, höchstens reach Blöcke weit. Der
     * Blick endet am ersten Block, der weder frei ist noch zu einem Rahmen gehört – durch Wände sieht man nicht.
     */
    public static Result along(Blocks w, double ox, double oy, double oz, double dx, double dy, double dz,
            double reach) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len == 0) {
            return Result.missing("keine Blickrichtung", 0);
        }
        Set<Long> seen = new HashSet<>();
        String last = "kein Rahmen im Blick";
        for (double t = 0; t <= reach; t += 0.2) {
            int x = (int) Math.floor(ox + dx / len * t);
            int y = (int) Math.floor(oy + dy / len * t);
            int z = (int) Math.floor(oz + dz / len * t);
            if (!seen.add(key(x, y, z))) {
                continue;
            }
            if (!w.open(x, y, z)) {
                return Result.missing(w.frame(x, y, z) ? "das ist der Rahmen – schau durch die Öffnung" : last, 0);
            }
            Result r = at(w, x, y, z);
            if (r.found()) {
                return r;
            }
            last = r.reason();
        }
        return Result.missing(last, 0);
    }

    /** Füllt die Ebene ab (x, y, z); nur Obsidian darf sie begrenzen. */
    private static Result flood(Blocks w, Axis axis, int x, int y, int z) {
        Set<Long> inside = new HashSet<>();
        ArrayDeque<int[]> todo = new ArrayDeque<>();
        inside.add(key(x, y, z));
        todo.add(new int[] {x, y, z});
        int minA = Integer.MAX_VALUE;
        int maxA = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        while (!todo.isEmpty()) {
            int[] c = todo.poll();
            int a = axis == Axis.X ? c[0] : c[2];
            minA = Math.min(minA, a);
            maxA = Math.max(maxA, a);
            minY = Math.min(minY, c[1]);
            maxY = Math.max(maxY, c[1]);
            if (maxA - minA + 1 > MAX || maxY - minY + 1 > MAX) {
                return Result.missing("die Öffnung ist größer als " + MAX + " × " + MAX, 2);
            }
            int[][] next = axis == Axis.X
                    ? new int[][] {{c[0] + 1, c[1], c[2]}, {c[0] - 1, c[1], c[2]}, {c[0], c[1] + 1, c[2]},
                            {c[0], c[1] - 1, c[2]}}
                    : new int[][] {{c[0], c[1], c[2] + 1}, {c[0], c[1], c[2] - 1}, {c[0], c[1] + 1, c[2]},
                            {c[0], c[1] - 1, c[2]}};
            for (int[] n : next) {
                if (w.open(n[0], n[1], n[2])) {
                    if (inside.add(key(n[0], n[1], n[2]))) {
                        todo.add(n);
                    }
                } else if (!w.frame(n[0], n[1], n[2])) {
                    return Result.missing("offen: bei " + n[0] + " " + n[1] + " " + n[2]
                            + " fehlt Obsidian im Rahmen", 1);
                }
            }
        }
        int width = maxA - minA + 1;
        int height = maxY - minY + 1;
        if (inside.size() != width * height) {
            return Result.missing("die Öffnung ist nicht rechteckig", 3);
        }
        if (width < MIN_WIDTH || height < MIN_HEIGHT) {
            return Result.missing("die Öffnung ist zu klein (mindestens " + MIN_WIDTH + " breit und " + MIN_HEIGHT
                    + " hoch, hier " + width + " × " + height + ")", 3);
        }
        return Result.found(axis == Axis.X ? new Opening(axis, minA, minY, z, width, height)
                : new Opening(axis, x, minY, minA, width, height));
    }

    /**
     * x, y, z in einem long, je 21 Bit. Eindeutig, solange die Zellen einer Suche weniger als 2^21 Blöcke auseinander
     * liegen – hier höchstens 22 (Suche) bzw. reach (Blick).
     */
    private static long key(int x, int y, int z) {
        return ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | (long) z & 0x1FFFFF;
    }
}
