package com.blackn0va.baumeister.osm;

import com.blackn0va.baumeister.osm.CityModel.Line;
import com.blackn0va.baumeister.osm.CityModel.P;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gleise und Begrenzungen (Zäune, Hecken, Mauern) aus {@link Line} – Teil des Stadtplaners, rein und deterministisch.
 *
 * Warum ein eigener 4er-Pfad statt der Bresenham-Linie aus CityPlanner.fill: Schienen und Zäune verbinden sich in
 * Minecraft nur über Kanten, nicht über Ecken. Eine schräge Linie aus diagonal benachbarten Zellen ergäbe einzelne
 * Zaunpfosten und Schienenstücke, auf denen keine Lore fährt. Der 4er-Pfad geht jeden Schritt waagrecht ODER
 * senkrecht; die Form jeder Schiene (north_south, Kurve north_east …) und die Verbindungen jedes Zauns ergeben sich
 * aus den Nachbarn im Pfad. Gesetzt wird ohne Physik (CityJob), also muss der Zustand vollständig im Blocknamen stehen
 * – ohne ihn läge jede Schiene in Nord-Süd-Richtung und jeder Zaun stünde als Einzelpfosten.
 *
 * Threads: wie CityPlanner (nicht threadsicher, eine Etappe zur Zeit). Fehler: keine.
 */
final class LineFeatures {

    private LineFeatures() {
    }

    /** Gleisart, die ein Schotterbett bekommt; Straßenbahn auf der Fahrbahn liegt ohne Bett im Belag. */
    private static boolean ballasted(String kind) {
        return !kind.equals("tram");
    }

    static boolean isRail(String kind) {
        return switch (kind) {
            case "rail", "tram", "light_rail", "narrow_gauge", "subway" -> true;
            default -> false;
        };
    }

    /**
     * Pass 1 (vor Bäumen und Brücken): Gleisbett als belegt eintragen, damit kein Baum im Gleis wächst und keine
     * Laterne daneben am Bahnübergang steht.
     */
    static void reserveRails(CityPlanner p, List<Line> lines) {
        for (Line l : lines) {
            if (!isRail(l.kind()) || !p.nearClip(l.points(), 2)) {
                continue;
            }
            for (int[] c : path4(l.points())) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (p.inMargin(c[0] + dx, c[1] + dz) && p.pavedBy(c[0] + dx, c[1] + dz) == CityPlanner.NONE) {
                            p.pave(c[0] + dx, c[1] + dz, l.id());
                        }
                    }
                }
            }
        }
    }

    /**
     * Gleise: Schotter (bzw. Steinziegel auf einer Brücke) in y=0 drei Zellen breit, Schiene in y=1 mit der Form aus
     * dem Pfad, darüber Luft. Auf einer Straße (Bahnübergang, Straßenbahn) bleibt der Belag, nur die Schiene liegt
     * darauf. Bahnbrücken bekommen ein Geländer, liegen aber auf Geländehöhe (keine Rampe: aufsteigende Schienen in
     * Kurven gibt es nicht, eine Rampe über einen schrägen 4er-Pfad wäre unbefahrbar).
     */
    static void rails(CityPlanner p, List<Line> lines) {
        for (Line l : lines) {
            if (!isRail(l.kind()) || !p.nearClip(l.points(), 3)) {
                continue;
            }
            List<int[]> path = path4(l.points());
            int bed = p.mat(l.bridge() ? "stone_bricks" : "gravel");
            int air = p.mat("air");
            Set<Long> track = new HashSet<>();
            for (int[] c : path) {
                track.add(CityPlanner.key(c[0], c[1]));
            }
            Set<Long> bedCells = new HashSet<>();
            for (int[] c : path) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int x = c[0] + dx;
                        int z = c[1] + dz;
                        long by = p.pavedBy(x, z);
                        boolean onRoad = by != CityPlanner.NONE && by != l.id();
                        if (onRoad || !bedCells.add(CityPlanner.key(x, z))) {
                            continue;  // Straße bleibt Straße: Bahnübergang bzw. Straßenbahn im Belag
                        }
                        if (ballasted(l.kind()) || l.bridge()) {
                            p.put(x, 0, z, bed);
                        }
                        if (!track.contains(CityPlanner.key(x, z))) {
                            p.put(x, 1, z, air);
                            p.put(x, 2, z, air);
                        }
                    }
                }
            }
            for (int i = 0; i < path.size(); i++) {
                int[] c = path.get(i);
                int[] prev = i > 0 ? path.get(i - 1) : null;
                int[] next = i + 1 < path.size() ? path.get(i + 1) : null;
                p.put(c[0], 1, c[1], p.mat("rail[shape=" + railShape(c, prev, next) + "]"));
                p.put(c[0], 2, c[1], air);
            }
            if (l.bridge()) {
                railing(p, l, track);
            }
        }
    }

    /** Geländer der Bahnbrücke: zwei Zellen neben der Gleisachse, nicht über einer Straße (Bahnübergang). */
    private static void railing(CityPlanner p, Line l, Set<Long> track) {
        Set<Long> rail = new HashSet<>();
        for (long k : track) {
            int x = (int) (k >> 32);
            int z = (int) k;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == 2) {
                        rail.add(CityPlanner.key(x + dx, z + dz));
                    }
                }
            }
        }
        // Zellen, die näher an einem Gleisstück liegen, gehören zum Bett, nicht zum Geländer.
        rail.removeIf(k -> nearTrack(track, (int) (k >> 32), k.intValue()));
        rail.removeIf(k -> {
            long by = p.pavedBy((int) (k >> 32), k.intValue());
            return by != CityPlanner.NONE && by != l.id();
        });
        for (long k : rail) {
            int x = (int) (k >> 32);
            int z = (int) k;
            p.put(x, 0, z, p.mat("stone_bricks"));
            p.put(x, 1, z, p.mat(wall("stone_brick_wall", rail, x, z)));
        }
    }

    private static boolean nearTrack(Set<Long> track, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (track.contains(CityPlanner.key(x + dx, z + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Zäune, Hecken und Mauern: im 4er-Pfad, mit Verbindungen zu den Nachbarzellen derselben Begrenzung. Wo eine Straße
     * oder ein Gleis kreuzt, bleibt eine Lücke (Tor/Einfahrt) – vorher (ohne Begrenzungen) gab es nichts, mit einer
     * durchgezogenen Linie wäre jede Hofeinfahrt zugemauert. Über Wasser auch nicht.
     */
    static void barriers(CityPlanner p, List<Line> lines) {
        for (Line l : lines) {
            if (isRail(l.kind()) || !p.nearClip(l.points(), 2)) {
                continue;
            }
            Map<Long, int[]> cells = new LinkedHashMap<>();
            for (int[] c : path4(l.points())) {
                if (p.pavedBy(c[0], c[1]) == CityPlanner.NONE && !p.wet(c[0], c[1]) && !p.elevated(c[0], c[1])) {
                    cells.put(CityPlanner.key(c[0], c[1]), c);
                    p.markFenced(c[0], c[1]);
                }
            }
            Set<Long> set = cells.keySet();
            for (int[] c : cells.values()) {
                int x = c[0];
                int z = c[1];
                switch (l.kind()) {
                    case "fence" -> p.put(x, 1, z, p.mat(fence("oak_fence", set, x, z)));
                    case "hedge" -> {
                        int leaves = p.mat("oak_leaves[persistent=true]");
                        p.put(x, 1, z, leaves);
                        p.put(x, 2, z, leaves);
                    }
                    case "wall" -> p.put(x, 1, z, p.mat(wall("cobblestone_wall", set, x, z)));
                    case "retaining_wall" -> p.put(x, 1, z, p.mat("stone_bricks"));
                    case "city_wall" -> {
                        int stone = p.mat("stone_bricks");
                        for (int y = 1; y <= 4; y++) {
                            p.put(x, y, z, stone);
                        }
                        // Zinnen: jede zweite Zelle eine Lage höher.
                        if (Math.floorMod(x + z, 2) == 0) {
                            p.put(x, 5, z, stone);
                        }
                    }
                    default -> {
                        // lineKind lässt nur die oben genannten Arten durch
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Blockzustände

    /** Zaun mit Verbindungen zu den 4 Nachbarn aus set; Eigenschaften alphabetisch (eine Palettenzeile je Form). */
    static String fence(String block, Set<Long> set, int x, int z) {
        return block + "[east=" + set.contains(CityPlanner.key(x + 1, z)) + ",north="
                + set.contains(CityPlanner.key(x, z - 1)) + ",south=" + set.contains(CityPlanner.key(x, z + 1))
                + ",west=" + set.contains(CityPlanner.key(x - 1, z)) + "]";
    }

    /**
     * Mauer mit Verbindungen (low) zu den 4 Nachbarn aus set. up=false nur im geraden Stück (genau zwei
     * gegenüberliegende Verbindungen) – so setzt auch das Spiel selbst die Mauer, mit Pfosten an Ecken und Enden.
     */
    static String wall(String block, Set<Long> set, int x, int z) {
        boolean e = set.contains(CityPlanner.key(x + 1, z));
        boolean w = set.contains(CityPlanner.key(x - 1, z));
        boolean n = set.contains(CityPlanner.key(x, z - 1));
        boolean s = set.contains(CityPlanner.key(x, z + 1));
        boolean straight = e && w && !n && !s || n && s && !e && !w;
        return block + "[east=" + (e ? "low" : "none") + ",north=" + (n ? "low" : "none") + ",south="
                + (s ? "low" : "none") + ",up=" + !straight + ",west=" + (w ? "low" : "none") + "]";
    }

    /** Schienenform aus Vorgänger und Nachfolger im Pfad (je null am Ende). */
    static String railShape(int[] c, int[] prev, int[] next) {
        String a = prev == null ? null : dir(c, prev);
        String b = next == null ? null : dir(c, next);
        if (a == null && b == null) {
            return "north_south";
        }
        if (a == null || b == null) {
            String d = a == null ? b : a;
            return d.equals("north") || d.equals("south") ? "north_south" : "east_west";
        }
        boolean ns = (a.equals("north") || a.equals("south")) && (b.equals("north") || b.equals("south"));
        boolean ew = (a.equals("east") || a.equals("west")) && (b.equals("east") || b.equals("west"));
        if (ns) {
            return "north_south";
        }
        if (ew) {
            return "east_west";
        }
        String vertical = a.equals("north") || a.equals("south") ? a : b;
        String horizontal = vertical.equals(a) ? b : a;
        return vertical + "_" + horizontal;  // north_east, north_west, south_east, south_west
    }

    private static String dir(int[] from, int[] to) {
        if (to[0] > from[0]) {
            return "east";
        }
        if (to[0] < from[0]) {
            return "west";
        }
        return to[1] > from[1] ? "south" : "north";
    }

    /**
     * Zellen entlang der Linie, je Schritt genau eine Achse (4er-Nachbarschaft), ohne doppelte Nachbarn. Je Strecke
     * entscheidet der kleinere Fortschritt (Zellmitte), welche Achse dran ist – so bleibt der Pfad nah an der Strecke.
     */
    static List<int[]> path4(List<P> line) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < line.size(); i++) {
            P a = line.get(i);
            P b = line.get(i + 1);
            int dx = Math.abs(b.x() - a.x());
            int dz = Math.abs(b.z() - a.z());
            int sx = b.x() >= a.x() ? 1 : -1;
            int sz = b.z() >= a.z() ? 1 : -1;
            int x = a.x();
            int z = a.z();
            add(out, x, z);
            int ix = 0;
            int iz = 0;
            while (ix < dx || iz < dz) {
                if (iz >= dz || ix < dx && (1L + 2L * ix) * dz < (1L + 2L * iz) * dx) {
                    x += sx;
                    ix++;
                } else {
                    z += sz;
                    iz++;
                }
                add(out, x, z);
            }
        }
        if (line.size() == 1) {
            add(out, line.getFirst().x(), line.getFirst().z());
        }
        return out;
    }

    private static void add(List<int[]> out, int x, int z) {
        if (out.isEmpty() || out.getLast()[0] != x || out.getLast()[1] != z) {
            out.add(new int[] {x, z});
        }
    }
}
