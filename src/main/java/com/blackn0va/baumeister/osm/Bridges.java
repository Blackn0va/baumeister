package com.blackn0va.baumeister.osm;

import com.blackn0va.baumeister.osm.CityModel.Area;
import com.blackn0va.baumeister.osm.CityModel.Line;
import com.blackn0va.baumeister.osm.CityModel.P;
import com.blackn0va.baumeister.osm.CityModel.Road;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Straßen- und Fußgängerbrücken (Road.bridge) als gehobene Fahrbahn mit Geländer. Vorher lag jede Brücke als Straße
 * am Boden: Über einem Fluss war sie ein Damm aus Beton quer durchs Wasser, über einer Straße eine Kreuzung.
 *
 * Form, je Zelle aus ihrem Abstand d zur Achse und ihrer Lage s entlang der Brücke (0 … Länge L):
 *   - Höhe in halben Blöcken u = min(2·H, s, L − s): an den Enden 0 (Anschluss an die Straße), je Meter ein halber
 *     Block – Rampen aus Stufen-Platten, die man ohne Springen hinauf geht und fährt.
 *   - Fahrbahn (d ≤ Breite/2): bei ungeradem u eine untere Platte, sonst ein voller Block; darüber drei Blöcke Luft.
 *   - Geländer (eine Zelle außerhalb der Fahrbahn), sobald die Fahrbahn mindestens einen Block hoch liegt: Mauer bzw.
 *     Zaun mit Verbindungen entlang des Geländers.
 *   - Darunter auf festem Boden ein Damm (Rampe), über Wasser und Wegen frei; im Wasser alle 12 m ein Pfeiler.
 * H: über Straße/Gleis 5 (vier Blöcke lichte Höhe), über Wasser 2, sonst 1; je Ebene (layer) darüber 5 mehr.
 * H hängt nur am Modell, nicht am Ausschnitt, und wird je Weg gemerkt – eine Brücke über eine Etappengrenze hat in
 * beiden Etappen dieselbe Höhe. Wer H aus den Zellen des Ausschnitts bestimmt, bekommt an der Grenze eine Stufe.
 *
 * Gelände (Weltbau): Fahrbahn und Geländer hängen über Cell.anchor an der Säule des näheren Brückenendes, sonst folgte
 * die Fahrbahn dem Talgrund unter ihr. Liegen die Enden verschieden hoch, entsteht in der Mitte eine Stufe – bei
 * Brücken über Flüsse selten, bei Talbrücken sichtbar (Gedächtnis „Offen“).
 *
 * Threads: wie CityPlanner. Fehler: keine.
 */
final class Bridges {

    /** Lichte Höhe über Straße/Gleis bzw. Wasser (Fahrbahnblock in y = H). */
    static final int OVER_ROAD = 5;
    static final int OVER_WATER = 2;
    private static final int MAX_HEIGHT = 16;
    private static final int PILLAR_EVERY = 12;

    private Bridges() {
    }

    /** Fußabdruck aller Brücken im Rand als „gehoben“ eintragen – vor Bäumen, Laternen und Schildern. */
    static void reserve(CityPlanner p, List<Road> roads) {
        for (Road r : roads) {
            if (!r.bridge() || r.kind().equals("waterway") || r.line().size() < 2) {
                continue;
            }
            double outer = half(r) + 1;
            if (!p.nearClip(r.line(), (int) Math.ceil(outer) + 1)) {
                continue;
            }
            int[] bb = bounds(r.line(), outer);
            CityPlanner.Clip m = p.marginClip();
            for (int x = Math.max(bb[0], m.x0()); x <= Math.min(bb[2], m.x1() - 1); x++) {
                for (int z = Math.max(bb[1], m.z0()); z <= Math.min(bb[3], m.z1() - 1); z++) {
                    if (distance(r.line(), x, z)[0] <= outer) {
                        p.markElevated(x, z);
                    }
                }
            }
        }
    }

    /** Alle Brücken, die in den Ausschnitt ragen. */
    static void build(CityPlanner p, CityModel city, Map<Long, Integer> heights) {
        for (Road r : city.roads()) {
            if (!r.bridge() || r.kind().equals("waterway") || r.line().size() < 2) {
                continue;
            }
            double outer = half(r) + 1;
            if (!p.nearClip(r.line(), (int) Math.ceil(outer) + 1)) {
                continue;
            }
            int h = heights.computeIfAbsent(r.id(), k -> height(r, city));
            bridge(p, r, h);
        }
    }

    static double half(Road r) {
        return Math.max(1.0, r.width() / 2.0);
    }

    /** Lichte Höhe H aus dem, was die Brücke kreuzt (siehe Klassenkommentar). Rein, nur aus dem Modell. */
    static int height(Road r, CityModel city) {
        int over = over(r.line(), r, city.roads(), city.lines(), city.areas());
        int h = (over & OVER_WAY) != 0 ? OVER_ROAD : (over & OVER_WET) != 0 ? OVER_WATER : 1;
        return Math.min(MAX_HEIGHT, h + OVER_ROAD * Math.max(0, r.layer() - 1));
    }

    /** Bits von {@link #over}: kreuzt einen Weg bzw. ein Gleis / liegt über Wasser. */
    static final int OVER_WAY = 1;
    static final int OVER_WET = 2;

    /**
     * Was liegt unter der Linie line (self = ihr eigenes Element, wird übersprungen)? Kreuzungen mit Wegen, Gleisen
     * und Gewässerlinien (jeweils nicht selbst Brücke), dazu Wasserfläche unter der Mitte. Auch der Import fragt das
     * (layer&gt;0 ohne bridge-Angabe ist nur über etwas eine Brücke).
     */
    static int over(List<P> line, Object self, List<Road> roads, List<Line> lines, List<Area> areas) {
        int out = 0;
        int[] bb = bounds(line, 0);
        for (Road o : roads) {
            if (o == self || o.bridge() || o.line().size() < 2 || !overlaps(bb, bounds(o.line(), 0))) {
                continue;
            }
            if (crosses(line, o.line())) {
                out |= o.kind().equals("waterway") ? OVER_WET : OVER_WAY;
            }
        }
        for (Line l : lines) {
            if (l != self && LineFeatures.isRail(l.kind()) && !l.bridge() && l.points().size() >= 2
                    && overlaps(bb, bounds(l.points(), 0)) && crosses(line, l.points())) {
                out |= OVER_WAY;
            }
        }
        if ((out & OVER_WET) == 0) {
            P mid = pointAt(line, length(line) / 2);
            for (Area a : areas) {
                if ((a.kind().equals("water") || a.kind().equals("sea")) && a.outline().size() >= 4 && OsmImport.contains(a.outline(), mid)) {
                    out |= OVER_WET;
                    break;
                }
            }
        }
        return out;
    }

    private static void bridge(CityPlanner p, Road r, int h) {
        double half = half(r);
        double outer = half + 1;
        // Fahrstraße: Beton auf Steinziegeln mit Mauer; Fuß- und Radweg: Fichtenholz mit Zaun.
        boolean car = CityPlanner.surface(r.kind()).equals("gray_concrete");
        int top = p.mat(car ? "gray_concrete" : "spruce_planks");
        int body = p.mat(car ? "stone_bricks" : "spruce_planks");
        int slab = p.mat(car ? "smooth_stone_slab[type=bottom]" : "spruce_slab[type=bottom]");
        int air = p.mat("air");
        List<P> line = r.line();
        double len = length(line);
        int[] bb = bounds(line, outer);
        CityPlanner.Clip c = p.clip();
        Map<Long, int[]> railing = new HashMap<>();  // Geländerzelle → {Oberkante, Anker}
        Set<Long> railSet = new HashSet<>();
        // Ein Feld um den Ausschnitt mehr: Das Geländer an der Grenze braucht seine Nachbarn für die Verbindungen.
        int x0 = Math.max(bb[0], c.x0() - 1);
        int x1 = Math.min(bb[2], c.x1());
        int z0 = Math.max(bb[1], c.z0() - 1);
        int z1 = Math.min(bb[3], c.z1());
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                double[] ds = distance(line, x, z);
                double d = ds[0];
                if (d > outer) {
                    continue;
                }
                int u = (int) Math.min(2.0 * h, Math.floor(Math.min(ds[1], len - ds[1])));
                int anchor = anchor(line, x, z, ds[1] <= len / 2);
                boolean land = p.pavedBy(x, z) == CityPlanner.NONE && !p.wet(x, z);
                if (d <= half) {
                    deck(p, x, z, u, anchor, land, top, body, slab, air);
                    if (p.wet(x, z) && u >= 4 && d <= 1.0
                            && Math.floorMod((int) Math.round(ds[1]), PILLAR_EVERY) == PILLAR_EVERY / 2) {
                        for (int y = -2; y < u / 2; y++) {
                            p.put(x, y, z, body, anchor);  // Pfeiler im Wasser bis unter die Fahrbahn
                        }
                    }
                } else if (u >= 2) {
                    int edge = (u + 1) / 2;
                    railing.put(CityPlanner.key(x, z), new int[] {edge, anchor, land ? 1 : 0});
                    railSet.add(CityPlanner.key(x, z));
                }
            }
        }
        for (Map.Entry<Long, int[]> e : railing.entrySet()) {
            int x = (int) (e.getKey() >> 32);
            int z = (int) (long) e.getKey();
            int edge = e.getValue()[0];
            int anchor = e.getValue()[1];
            boolean land = e.getValue()[2] == 1;
            for (int y = 1; y < edge; y++) {
                p.put(x, y, z, land ? body : air, anchor);
            }
            p.put(x, edge, z, body, anchor);
            p.put(x, edge + 1, z, p.mat(car ? LineFeatures.wall("stone_brick_wall", railSet, x, z)
                    : LineFeatures.fence("spruce_fence", railSet, x, z)), anchor);
            p.put(x, edge + 2, z, air, anchor);
        }
    }

    /** Fahrbahnzelle mit u halben Blöcken Höhe. */
    private static void deck(CityPlanner p, int x, int z, int u, int anchor, boolean land, int top, int body,
            int slab, int air) {
        int full = u / 2;
        boolean half = u % 2 == 1;
        if (full == 0) {
            p.put(x, 0, z, top, anchor);  // Anschluss: Fahrbahn auf Bodenhöhe
        }
        for (int y = 1; y < full; y++) {
            p.put(x, y, z, land ? body : air, anchor);
        }
        if (full >= 1) {
            p.put(x, full, z, half ? body : top, anchor);
        }
        int walk = full + (half ? 1 : 0);
        if (half) {
            p.put(x, walk, z, slab, anchor);
        }
        for (int y = walk + 1; y <= walk + 3; y++) {
            p.put(x, y, z, air, anchor);
        }
    }

    /**
     * Anker zur Säule des näheren Brückenendes (siehe Klassenkommentar), sofern sie in ±127 Blöcken liegt; sonst die
     * eigene Säule.
     */
    private static int anchor(List<P> line, int x, int z, boolean first) {
        P end = first ? line.getFirst() : line.getLast();
        int dx = end.x() - x;
        int dz = end.z() - z;
        if (Math.abs(dx) > 127 || Math.abs(dz) > 127) {
            return 0;
        }
        return CityPlanner.Cell.anchor(dx, dz);
    }

    // ------------------------------------------------------------------ Geometrie

    /** {Abstand zur Linie, Lage s des Lotfußpunkts entlang der Linie}. */
    static double[] distance(List<P> line, int x, int z) {
        double best = Double.MAX_VALUE;
        double bestS = 0;
        double s = 0;
        for (int i = 0; i + 1 < line.size(); i++) {
            P a = line.get(i);
            P b = line.get(i + 1);
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double len2 = dx * dx + dz * dz;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - a.x()) * dx + (z - a.z()) * dz) / len2));
            double d = Math.hypot(x - (a.x() + t * dx), z - (a.z() + t * dz));
            double seg = Math.sqrt(len2);
            if (d < best) {
                best = d;
                bestS = s + t * seg;
            }
            s += seg;
        }
        return new double[] {best, bestS};
    }

    static double length(List<P> line) {
        double s = 0;
        for (int i = 0; i + 1 < line.size(); i++) {
            s += Math.hypot(line.get(i + 1).x() - line.get(i).x(), line.get(i + 1).z() - line.get(i).z());
        }
        return s;
    }

    /** Punkt bei Lage s entlang der Linie (gerundet). */
    static P pointAt(List<P> line, double s) {
        double rest = s;
        for (int i = 0; i + 1 < line.size(); i++) {
            P a = line.get(i);
            P b = line.get(i + 1);
            double seg = Math.hypot(b.x() - a.x(), b.z() - a.z());
            if (rest <= seg || i + 2 == line.size()) {
                double t = seg == 0 ? 0 : Math.min(1, rest / seg);
                return new P((int) Math.round(a.x() + t * (b.x() - a.x())),
                        (int) Math.round(a.z() + t * (b.z() - a.z())));
            }
            rest -= seg;
        }
        return line.getFirst();
    }

    /** Kreuzen sich die Linien echt (nicht nur in einem gemeinsamen Punkt – das ist ein Anschluss, keine Querung)? */
    static boolean crosses(List<P> a, List<P> b) {
        for (int i = 0; i + 1 < a.size(); i++) {
            for (int j = 0; j + 1 < b.size(); j++) {
                if (properCross(a.get(i), a.get(i + 1), b.get(j), b.get(j + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean properCross(P a, P b, P c, P d) {
        long d1 = orient(c, d, a);
        long d2 = orient(c, d, b);
        long d3 = orient(a, b, c);
        long d4 = orient(a, b, d);
        return (d1 > 0 && d2 < 0 || d1 < 0 && d2 > 0) && (d3 > 0 && d4 < 0 || d3 < 0 && d4 > 0);
    }

    private static long orient(P a, P b, P c) {
        return (long) (b.x() - a.x()) * (c.z() - a.z()) - (long) (b.z() - a.z()) * (c.x() - a.x());
    }

    /** {minX, minZ, maxX, maxZ} der Linie, um pad erweitert. */
    static int[] bounds(List<P> line, double pad) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (P q : line) {
            b[0] = Math.min(b[0], q.x());
            b[1] = Math.min(b[1], q.z());
            b[2] = Math.max(b[2], q.x());
            b[3] = Math.max(b[3], q.z());
        }
        int e = (int) Math.ceil(pad);
        return new int[] {b[0] - e, b[1] - e, b[2] + e, b[3] + e};
    }

    private static boolean overlaps(int[] a, int[] b) {
        return a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3];
    }
}
