package com.blackn0va.baumeister.osm;

import com.blackn0va.baumeister.osm.CityModel.P;
import com.blackn0va.baumeister.osm.CityModel.Road;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Straßenlaternen und Straßennamen-Schilder – Teil des Stadtplaners, rein und deterministisch.
 *
 * Alle Prüfungen („frei?“, „Kreuzung?“) laufen über Wissen, das im Rand um den Ausschnitt vollständig ist (Häuser,
 * Wege, Wasser, Brücken – CityPlanner.open/pavedBy). Gesetzt wird nur im Ausschnitt. So wählen zwei Etappen an ihrer
 * Grenze dieselbe Stelle: Eine Laterne oder ein Schild entsteht genau einmal. Wer hier mit Wissen nur aus dem
 * Ausschnitt prüft, bekommt an Etappengrenzen doppelte Schilder.
 *
 * Threads: wie CityPlanner. Fehler: keine.
 */
final class StreetFurniture {

    /** Abstand zweier Laternen entlang einer Straße; die Seiten wechseln sich ab (je Seite also 32 m). */
    static final int LAMP_SPACING = 16;
    /** Laternen nicht näher als so an einer anderen Straße oder einem Gleis (Kreuzung, Einfahrt, Bahnübergang). */
    private static final int LAMP_CLEARANCE = 2;
    /**
     * Wegarten, die in gut kartierten Städten als eigener Gehweg bzw. Radweg neben der Fahrbahn liegen
     * (sidewalk=separate). Sie zählen im Freiraum einer Laterne nur, wenn sie die Straße dort kreuzen oder an ihr
     * enden.
     */
    private static final Set<String> SIDE_PATHS = Set.of("footway", "cycleway", "path", "bridleway");
    /** Schilderpfosten: so weit (Schachbrettabstand) über die Straßenbreite hinaus wird gesucht. */
    private static final int SIGN_SEARCH = 3;
    /** Größte Suchweite um den Kreuzungspunkt vor SIGN_SEARCH (siehe signs: reach). */
    private static final int SIGN_REACH = 8;
    /**
     * Zwei Kreuzungen derselben Straßennamen höchstens so weit auseinander bekommen nur ein Schilderpaar
     * (Doppelfahrbahnen). Vorher 20: Ein Pfosten liegt bis SIGN_REACH + SIGN_SEARCH = 11 vom Kreuzungspunkt; der
     * Partnerpunkt lag dann bis 31 vom Ausschnitt – außerhalb des Rands (24) der Etappe, die den Pfosten setzt. Die
     * eine Etappe unterdrückte den Punkt, die andere setzte ihn (Prüfbefund 2026-10-29, Gegenprobe: 3 statt 2
     * Schilder).
     * Jetzt Rand − 11 = 13: Beide Etappen sehen jeden Partner. Wer das wieder über Rand − 11 hebt, bekommt an
     * Etappengrenzen doppelte Schilderpaare; Doppelfahrbahnen weiter als 13 m auseinander bekommen je ein Paar.
     */
    private static final int SIGN_DEDUPE = CityPlanner.MARGIN - SIGN_REACH - SIGN_SEARCH;
    private static final int SIGN_LINE = 15;
    private static final int[][] NEIGHBOURS = {{0, 0}, {0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    private StreetFurniture() {
    }

    /** Straßen mit Laternen: Wohn- und Hauptstraßen, Spielstraßen, Fußgängerzonen. Keine Autobahn, kein Feldweg. */
    static boolean lit(String kind) {
        return switch (kind) {
            case "primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "pedestrian",
                    "primary_link", "secondary_link", "tertiary_link" -> true;
            default -> false;
        };
    }

    /** Straßen mit Namensschildern an Kreuzungen – wie lit, dazu service (benannte Höfe/Gassen). */
    private static boolean signed(Road r) {
        return !r.name().isBlank() && !r.bridge() && (lit(r.kind()) || r.kind().equals("service"));
    }

    /**
     * Laternen: alle {@link #LAMP_SPACING} m, Seite wechselnd. Breite Straßen (≥ 6, mit Bordstein) bekommen sie auf
     * den Bordstein, schmale direkt neben die Fahrbahn. Nicht: auf Kreuzungen und Einfahrten (eine andere Straße oder
     * ein Gleis näher als {@link #LAMP_CLEARANCE}), in Häusern, im Wasser, unter oder auf Brücken, in Durchgängen,
     * dicht an einer anderen Laterne. reserved nimmt die Stellen auf (für die Schilder).
     */
    static void lamps(CityPlanner p, List<Road> roads, Set<Long> reserved) {
        int post = p.mat("polished_blackstone_wall");
        int light = p.mat("lantern[hanging=false]");
        Map<Long, Road> byId = new HashMap<>();
        for (Road r : roads) {
            byId.putIfAbsent(r.id(), r);
        }
        for (Road r : roads) {
            if (!lit(r.kind()) || r.bridge() || r.passage() || r.line().size() < 2
                    || !p.nearClip(r.line(), (int) Math.ceil(r.width() / 2.0) + 2)) {
                continue;
            }
            double half = r.width() / 2.0;
            boolean curb = CityPlanner.surface(r.kind()).equals("gray_concrete") && r.width() >= 6;
            double off = curb ? half - 0.5 : half + 1.0;
            double len = Bridges.length(r.line());
            int k = 0;
            for (double s = LAMP_SPACING / 2.0; s < len; s += LAMP_SPACING, k++) {
                double[] pd = pointAndDirection(r.line(), s);
                int side = k % 2 == 0 ? 1 : -1;
                int tx = (int) Math.round(pd[0] - pd[3] * side * off);
                int tz = (int) Math.round(pd[1] + pd[2] * side * off);
                // Gerundet landet der Zielpunkt je nach Seite und Schräge eine Zelle zu weit innen oder außen
                // (Math.round(-2,5) = -2): Zielzelle und ihre vier Nachbarn in fester Reihenfolge probieren.
                int[] spot = null;
                for (int[] o : NEIGHBOURS) {
                    if (lampSpot(p, r, tx + o[0], tz + o[1], half, curb, byId)) {
                        spot = new int[] {tx + o[0], tz + o[1]};
                        break;
                    }
                }
                if (spot == null || near(reserved, spot[0], spot[1], 4)) {
                    continue;
                }
                int x = spot[0];
                int z = spot[1];
                reserved.add(CityPlanner.key(x, z));
                for (int y = 1; y <= 3; y++) {
                    p.put(x, y, z, post);
                }
                p.put(x, 4, z, light);
            }
        }
    }

    private static boolean lampSpot(CityPlanner p, Road r, int x, int z, double half, boolean curb,
            Map<Long, Road> byId) {
        if (!p.open(x, z)) {
            return false;
        }
        double d = Bridges.distance(r.line(), x, z)[0];
        long own = p.pavedBy(x, z);
        if (curb ? own != r.id() || d <= half - 1 || d > half : own != CityPlanner.NONE || d <= half || d > half + 1.5) {
            return false;  // gerundet auf die Fahrbahn bzw. zu weit weg
        }
        for (int dx = -LAMP_CLEARANCE; dx <= LAMP_CLEARANCE; dx++) {
            for (int dz = -LAMP_CLEARANCE; dz <= LAMP_CLEARANCE; dz++) {
                long by = p.pavedBy(x + dx, z + dz);
                if (p.elevated(x + dx, z + dz)) {
                    return false;
                }
                if (by != CityPlanner.NONE && by != r.id()) {
                    // Vorher zählte jeder fremde Weg hier als Kreuzung bzw. Einfahrt. Gemessen am Münchner Cache
                    // (Prüfbefund 2026-10-29): von rund 2 650 erwarteten Laternen standen 1 020, in rund 25 % der
                    // Fehlschläge lag ein separat kartierter Gehweg oder Radweg im Freiraum. Ein Gehweg längs der
                    // Straße ist keine Kreuzung; ein querender (Überweg, Einmündung) bleibt eine. MULTI (mehrere Wege
                    // auf einer Zelle) bleibt immer eine Kreuzung.
                    Road o = byId.get(by);
                    if (o == null || !SIDE_PATHS.contains(o.kind()) || joins(r, o, x, z, half)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Kommt der Weg o im Umkreis der Laternenstelle (x, z) an die Fahrbahn von r heran – kreuzt er sie oder endet er an
     * ihr? Abgetastet in halben Metern, nur im Umkreis (Fahrbahnbreite + Freiraum + 2): Ein Gehweg, der 200 m weiter
     * einmündet, macht die Laterne hier nicht zur Kreuzungslaterne. „Heran“ = Achsabstand höchstens halbe
     * Fahrbahnbreite + halbe Wegbreite + 0,5 – ein Gehweg, der den Bordstein berührt, zählt also noch als Kreuzung.
     */
    private static boolean joins(Road r, Road o, int x, int z, double half) {
        int win = (int) Math.ceil(half) + LAMP_CLEARANCE + 2;
        double reach = half + o.width() / 2.0 + 0.5;
        List<P> line = o.line();
        for (int i = 0; i + 1 < line.size(); i++) {
            P a = line.get(i);
            P b = line.get(i + 1);
            if (Math.max(a.x(), b.x()) < x - win || Math.min(a.x(), b.x()) > x + win
                    || Math.max(a.z(), b.z()) < z - win || Math.min(a.z(), b.z()) > z + win) {
                continue;
            }
            double len = Math.hypot(b.x() - a.x(), b.z() - a.z());
            int steps = Math.max(1, (int) Math.ceil(len * 2));
            for (int k = 0; k <= steps; k++) {
                double t = (double) k / steps;
                int px = (int) Math.round(a.x() + t * (b.x() - a.x()));
                int pz = (int) Math.round(a.z() + t * (b.z() - a.z()));
                if (Math.abs(px - x) > win || Math.abs(pz - z) > win) {
                    continue;
                }
                if (Bridges.distance(r.line(), px, pz)[0] <= reach) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Schilder: An jedem gemeinsamen Punkt zweier Straßen mit verschiedenen Namen (in OSM teilen sich Wege an einer
     * Kreuzung einen Knoten) bekommt jeder Name einen eigenen Pfosten an einer freien Ecke, das Schild parallel zu
     * seiner Straße – wie in echt, von der Querstraße aus lesbar. Der Schildtext ist Blockzustand, kein BlockData:
     * Der Plan liefert ihn als {@link CityPlanner.Sign}, CityJob setzt ihn nach dem Block (SignWriter).
     */
    static List<CityPlanner.Sign> signs(CityPlanner p, List<Road> roads, Set<Long> reserved) {
        Map<P, List<Road>> at = new LinkedHashMap<>();
        for (Road r : roads) {
            if (!signed(r) || !p.nearClip(r.line(), SIGN_SEARCH + 16)) {
                continue;
            }
            for (P q : r.line()) {
                if (p.inMargin(q.x(), q.z())) {
                    List<Road> list = at.computeIfAbsent(q, k -> new ArrayList<>());
                    if (!list.contains(r)) {
                        list.add(r);
                    }
                }
            }
        }
        List<CityPlanner.Sign> out = new ArrayList<>();
        // {Namen, Punkt} aller bisherigen Kreuzungspunkte – auch der unterdrückten. Vorher nur der behaltenen: Dann
        // hing die Entscheidung an einer Kette von Punkten, deren Ende eine Nachbaretappe nicht mehr sieht. So hängt
        // sie nur an Punkten bis SIGN_DEDUPE, und die sieht jede Etappe, die hier einen Pfosten setzen kann.
        List<Object[]> done = new ArrayList<>();
        int postMat = p.mat("dark_oak_fence");
        for (Map.Entry<P, List<Road>> e : at.entrySet()) {
            Map<String, Road> byName = new LinkedHashMap<>();
            for (Road r : e.getValue()) {
                byName.putIfAbsent(r.name(), r);
            }
            if (byName.size() < 2) {
                continue;
            }
            P q = e.getKey();
            Set<String> names = new TreeSet<>(byName.keySet());
            boolean twin = seen(done, names, q);
            done.add(new Object[] {names, q});
            if (twin) {
                continue;
            }
            int reach = 1;
            for (Road r : byName.values()) {
                reach = Math.max(reach, (int) Math.ceil(r.width() / 2.0) + 1);
            }
            reach = Math.min(reach, SIGN_REACH);
            for (String name : names) {
                Road r = byName.get(name);
                int[] spot = signSpot(p, q, reach, byName.values(), reserved);
                if (spot == null) {
                    continue;
                }
                reserved.add(CityPlanner.key(spot[0], spot[1]));
                if (!p.inClip(spot[0], spot[1])) {
                    continue;  // gewählt, aber gesetzt von der Nachbaretappe
                }
                double[] dir = direction(r.line(), q);
                String block = "birch_sign[rotation=" + rotation(-dir[1], dir[0]) + "]";
                p.put(spot[0], 1, spot[1], postMat);
                p.put(spot[0], 2, spot[1], postMat);
                p.put(spot[0], 3, spot[1], p.mat(block));
                out.add(new CityPlanner.Sign(spot[0], 3, spot[1], block, lines(name)));
            }
        }
        return out;
    }

    private static boolean seen(List<Object[]> done, Set<String> names, P q) {
        for (Object[] d : done) {
            P o = (P) d[1];
            if (d[0].equals(names) && Math.abs(o.x() - q.x()) <= SIGN_DEDUPE && Math.abs(o.z() - q.z()) <= SIGN_DEDUPE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Freie Ecke um den Kreuzungspunkt: Ring für Ring (Schachbrettabstand reach … reach + SIGN_SEARCH), im Ring nach x,
     * dann z (fest, damit jede Etappe gleich wählt). Frei = kein Haus, kein Wasser, keine Brücke, keine Laterne oder
     * kein Pfosten daneben, und entweder kein Weg oder der Bordstein einer der Kreuzungsstraßen.
     */
    private static int[] signSpot(CityPlanner p, P q, int reach, Iterable<Road> junction, Set<Long> reserved) {
        for (int ring = reach; ring <= reach + SIGN_SEARCH; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int x = q.x() + dx;
                    int z = q.z() + dz;
                    if (p.open(x, z) && !near(reserved, x, z, 1) && besideTheRoads(p, x, z, junction)) {
                        return new int[] {x, z};
                    }
                }
            }
        }
        return null;
    }

    private static boolean besideTheRoads(CityPlanner p, int x, int z, Iterable<Road> junction) {
        long by = p.pavedBy(x, z);
        boolean ownCurb = false;
        for (Road r : junction) {
            double d = Bridges.distance(r.line(), x, z)[0];
            boolean curb = CityPlanner.surface(r.kind()).equals("gray_concrete") && r.width() >= 6;
            // Auf der Fahrbahn irgendeiner Kreuzungsstraße nie (alle prüfen, nicht nur die erste); auf dem
            // Bordstein einer Kreuzungsstraße ja.
            if (d <= (curb ? r.width() / 2.0 - 1 : r.width() / 2.0)) {
                return false;
            }
            ownCurb |= by == r.id();
        }
        return ownCurb || by == CityPlanner.NONE;
    }

    private static boolean near(Set<Long> reserved, int x, int z, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (reserved.contains(CityPlanner.key(x + dx, z + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Drehung (0–15) eines stehenden Schilds, dessen Vorderseite nach (fx, fz) zeigt: 0 = Süden (+z), 4 = Westen,
     * 8 = Norden, 12 = Osten – in 22,5°-Schritten.
     */
    static int rotation(double fx, double fz) {
        double a = Math.atan2(-fx, fz);
        return Math.floorMod((int) Math.round(a / (Math.PI / 8)), 16);
    }

    /** Richtung (Einheitsvektor) der Straße am Punkt q: Strecke zum Nachbarpunkt in der Linie. */
    private static double[] direction(List<P> line, P q) {
        int i = line.indexOf(q);
        P a = i > 0 ? line.get(i - 1) : line.get(Math.max(0, i));
        P b = i + 1 < line.size() ? line.get(i + 1) : line.get(Math.max(0, i));
        if (a.equals(b)) {
            a = line.getFirst();
            b = line.getLast();
        }
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        double l = Math.hypot(dx, dz);
        return l == 0 ? new double[] {1, 0} : new double[] {dx / l, dz / l};
    }

    /** {x, z, dx, dz}: Punkt bei Lage s und Richtung der Strecke dort (Einheitsvektor). */
    private static double[] pointAndDirection(List<P> line, double s) {
        double rest = s;
        for (int i = 0; i + 1 < line.size(); i++) {
            P a = line.get(i);
            P b = line.get(i + 1);
            double seg = Math.hypot(b.x() - a.x(), b.z() - a.z());
            if (seg == 0) {
                continue;
            }
            if (rest <= seg || i + 2 == line.size()) {
                double t = Math.min(1, rest / seg);
                double dx = (b.x() - a.x()) / seg;
                double dz = (b.z() - a.z()) / seg;
                return new double[] {a.x() + t * (b.x() - a.x()), a.z() + t * (b.z() - a.z()), dx, dz};
            }
            rest -= seg;
        }
        P f = line.getFirst();
        return new double[] {f.x(), f.z(), 1, 0};
    }

    /** Endungen, an denen ein zu langer Straßenname mit Bindestrich umbricht („Kurfürsten-“ / „straße“). */
    private static final List<String> SUFFIXES = List.of("straße", "strasse", "gasse", "allee", "platz", "chaussee",
            "damm", "ufer", "steig", "pfad", "weg", "ring", "markt", "graben");

    /**
     * Schildtext: höchstens 4 Zeilen à 15 Zeichen (so viel passt sichtbar auf ein Schild). Umbruch an Leerzeichen und
     * nach Bindestrichen („Konrad-“ / „Adenauer-Straße“); ein zu langes Wort an einer Straßen-Endung, sonst hart mit
     * Bindestrich. Was nicht passt, endet mit „…“ – lieber gekürzt mit Hinweis als still abgeschnitten.
     */
    static List<String> lines(String name) {
        List<String[]> tokens = new ArrayList<>();  // {Text, "" = mit Leerzeichen anhängen, "-" = direkt anhängen}
        for (String word : name.trim().split("\\s+")) {
            String[] parts = word.split("(?<=-)");
            for (int i = 0; i < parts.length; i++) {
                List<String> pieces = splitLong(parts[i]);
                for (int j = 0; j < pieces.size(); j++) {
                    tokens.add(new String[] {pieces.get(j), i == 0 && j == 0 ? "" : "-"});
                }
            }
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String[] t : tokens) {
            String sep = t[1].isEmpty() ? " " : "";
            if (cur.isEmpty()) {
                cur.append(t[0]);
            } else if (cur.length() + sep.length() + t[0].length() <= SIGN_LINE) {
                cur.append(sep).append(t[0]);
            } else {
                out.add(cur.toString());
                cur = new StringBuilder(t[0]);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        if (out.size() > 4) {
            List<String> cut = new ArrayList<>(out.subList(0, 4));
            String last = cut.get(3);
            cut.set(3, (last.length() >= SIGN_LINE ? last.substring(0, SIGN_LINE - 1) : last) + "…");
            return List.copyOf(cut);
        }
        return List.copyOf(out);
    }

    /** Wort > 15 Zeichen: an einer Endung („…-“ + Endung), sonst in Stücke à 14 mit Bindestrich. */
    private static List<String> splitLong(String w) {
        if (w.length() <= SIGN_LINE) {
            return List.of(w);
        }
        String lower = w.toLowerCase(Locale.GERMAN);
        for (String suffix : SUFFIXES) {
            int at = lower.lastIndexOf(suffix);
            if (at > 0 && at + suffix.length() == w.length()) {
                List<String> out = new ArrayList<>(splitLong(w.substring(0, at) + "-"));
                out.add(w.substring(at));
                return out;
            }
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        while (w.length() - i > SIGN_LINE) {
            out.add(w.substring(i, i + SIGN_LINE - 1) + "-");
            i += SIGN_LINE - 1;
        }
        out.add(w.substring(i));
        return out;
    }
}
