package com.blackn0va.baumeister;

import com.blackn0va.baumeister.earth.EarthProjection;
import com.blackn0va.baumeister.osm.CityModel;
import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.Projector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Kachelrechnung des Weltbaus, rein und ohne Server: Die Erdwelt ist in Kacheln zu 1024 × 1024 Blöcken geteilt
 * (Kachel (tx, tz) = x tx·1024 … tx·1024+1023). Je Kachel eine Overpass-Abfrage für das lat/lon-Rechteck um sie
 * herum, gebaut in 4 × 4 Etappen zu 256 m wie der Stadtbau.
 *
 * Warum 1024 und nicht die 1280 m des Stadtbaus: Dort sind Kacheln um den Mittelpunkt eines Orts gelegt (5 × 5
 * Etappen symmetrisch). In der Erdwelt gibt es keinen Mittelpunkt – die Kacheln müssen ein festes Raster sein, das
 * jeder Spieler an jeder Stelle gleich trifft, sonst würden Kacheln doppelt oder versetzt gebaut. 1024 = 64 Chunks,
 * Grenzen fallen auf Chunkgrenzen.
 *
 * Threads: rein. Fehler: keine Ausnahmen; eine Kachel in einer Netzlücke liefert bei box() null, eine über der
 * Datumsgrenze ein entfaltetes Rechteck (GeoBox.parts: zwei Abfragen).
 */
final class WorldTiles {

    static final int SIZE = 1024;
    /** Rand der Abfrage wie im Stadtbau (CityStages.MARGIN): Häuser und Straßen an der Grenze kennen beide Kacheln. */
    static final int MARGIN = CityStages.MARGIN;
    static final int STAGE = CityStages.STAGE;

    private WorldTiles() {
    }

    /** Kachel (tx, tz). */
    record Tile(int tx, int tz) {

        int x0() {
            return tx * SIZE;
        }

        int z0() {
            return tz * SIZE;
        }

        /** Mitte – Ursprung der Projektion und des Bauplans (Clip −512 … 512). */
        int centerX() {
            return x0() + SIZE / 2;
        }

        int centerZ() {
            return z0() + SIZE / 2;
        }

        long key() {
            return ((long) tx << 32) ^ (tz & 0xffffffffL);
        }

        @Override
        public String toString() {
            return tx + "/" + tz;
        }
    }

    /**
     * lat/lon-Rechteck einer Kachel samt Rand. Über der Datumsgrenze ist es „entfaltet“: west &lt; −180 oder east &gt; 180
     * (stetig um die Kachelmitte) – abgefragt wird es dann in zwei Teilen (parts).
     */
    record GeoBox(double south, double west, double north, double east) {

        boolean crossesDateline() {
            return west < -180 || east > 180;
        }

        /**
         * Die Rechtecke für den Kartendienst, jedes innerhalb −180 … 180: eins, oder zwei beiderseits der Datumsgrenze.
         * Overpass kann je Abfrage nur EIN Rechteck mit west ≤ east; ob es west &gt; east als „über die Datumsgrenze“
         * liest, ist nicht geprüft – deshalb zwei Abfragen, deren Antworten WorldBuildService zusammenführt.
         */
        List<GeoBox> parts() {
            if (west < -180) {
                return List.of(new GeoBox(south, west + 360, north, 180), new GeoBox(south, -180, north, east));
            }
            if (east > 180) {
                return List.of(new GeoBox(south, west, north, 180), new GeoBox(south, -180, north, east - 360));
            }
            return List.of(this);
        }
    }

    /** Kachel, in der der Punkt liegt (Weltgrenze ±30 Mio. – der Quotient passt immer in int). */
    static Tile at(double x, double z) {
        return new Tile((int) Math.floorDiv((long) Math.floor(x), SIZE),
                (int) Math.floorDiv((long) Math.floor(z), SIZE));
    }

    /**
     * Rechteck der Kachel samt margin in lat/lon: Ecken, Kantenmitten und Mitte über toGeo, davon Minimum und Maximum.
     * Nur die Ecken reichten bei einer gekrümmten Projektion nicht ganz (eine Kante kann sich nach außen wölben); die
     * Kantenmitten fangen das, der Rand von 64 m den Rest.
     *
     * null, sobald ein Punkt der Kachel selbst in einer Netzlücke liegt – eine Kachel halb im Nichts wird nicht gebaut
     * (Gelände fehlt dort ohnehin). Punkte des Rands in einer Lücke zählen nicht: Sonst fiele jede Kachel neben einer
     * Lücke mit weg, obwohl sie ganz auf der Karte liegt; jenseits der Lücke gibt es ohnehin nichts abzufragen.
     *
     * Datumsgrenze (Prüfbefund 2026-10-28): Vorher bildete box() Minimum und Maximum der rohen Längengrade. Eine Kachel
     * über lon ±180 – in Airocean erreichbar, die Datumsgrenze läuft dort durch Fidschi, Tschukotka und die Aleuten –
     * bekam so West −179,998 / Ost 179,9995: eine Overpass-Abfrage über einen 1,5 km breiten Streifen um die ganze Erde
     * (gemessen mit der echten AiroceanProjection bei lat −17,8). Jetzt werden die Längen relativ zur Kachelmitte
     * entfaltet. Ragt das Rechteck dann über ±180, liefert box() es entfaltet (west &lt; −180 oder east &gt; 180), und
     * GeoBox.parts() teilt es in zwei schmale Rechtecke beiderseits der Datumsgrenze (Nutzerwunsch 2026-10-29: Kacheln
     * dort wurden vorher gar nicht gebaut). Die Plausibilitätsgrenze MAX_SPAN_DEG gilt für das entfaltete Rechteck,
     * also für beide Teile zusammen – gegen jede Projektion, die eine 1-km-Kachel auf ein riesiges Rechteck abbildet.
     * Wer das Entfalten oder die Grenze entfernt, schickt im Automatikbetrieb Abfragen um den Erdball an öffentliche
     * Dienste – mit drei Runden Wiederholung je Spiegel.
     */
    static GeoBox box(EarthProjection p, Tile t, int margin) {
        double[] mid = p.toGeo(t.x0() + SIZE / 2.0, t.z0() + SIZE / 2.0);
        if (!valid(mid)) {
            return null;
        }
        double lon0 = mid[1];
        double[] bounds = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.NEGATIVE_INFINITY};
        double[] core = {0, SIZE / 2.0, SIZE - 1};
        for (double dx : core) {
            for (double dz : core) {
                if (!extend(bounds, p.toGeo(t.x0() + dx, t.z0() + dz), lon0)) {
                    return null;
                }
            }
        }
        double[] ring = {-margin, SIZE / 2.0, SIZE - 1 + margin};
        for (double dx : ring) {
            for (double dz : ring) {
                extend(bounds, p.toGeo(t.x0() + dx, t.z0() + dz), lon0);  // Lücke im Rand: Punkt zählt nicht (s. o.)
            }
        }
        if (bounds[1] < -540 || bounds[3] > 540) {
            return null;  // kann nach dem Entfalten um eine Mitte in −180 … 180 nicht vorkommen – fail-closed
        }
        if (bounds[2] - bounds[0] > MAX_SPAN_DEG || bounds[3] - bounds[1] > MAX_SPAN_DEG) {
            return null;
        }
        return new GeoBox(bounds[0], bounds[1], bounds[2], bounds[3]);
    }

    /**
     * Größte erlaubte Ausdehnung des Abfragerechtecks in Grad (Breite wie Länge). Eine Kachel samt Rand misst rund
     * 1,15 km, also ~0,01° Breite; in Länge wird 2° erst jenseits von |lat| ≈ 89,7° erreicht (1 088 m / (111 320 m ·
     * cos φ)). Was darüber liegt, ist ein Projektionsfehler oder die Polkappe – beides wird nicht abgefragt.
     */
    static final double MAX_SPAN_DEG = 2.0;

    private static boolean valid(double[] g) {
        return g != null && g.length >= 2 && Double.isFinite(g[0]) && Double.isFinite(g[1]);
    }

    /**
     * bounds {Süd, West, Nord, Ost} um g = {lat, lon} erweitern, die Länge entfaltet um lon0 (Abstand in (−180, 180],
     * kann also unter −180 oder über 180 landen – daran erkennt GeoBox die Datumsgrenze); false, wenn g in einer
     * Lücke liegt.
     */
    private static boolean extend(double[] bounds, double[] g, double lon0) {
        if (!valid(g)) {
            return false;
        }
        double d = (g[1] - lon0) % 360.0;
        if (d > 180) {
            d -= 360;
        } else if (d <= -180) {
            d += 360;
        }
        double lon = lon0 + d;
        bounds[0] = Math.min(bounds[0], g[0]);
        bounds[1] = Math.min(bounds[1], lon);
        bounds[2] = Math.max(bounds[2], g[0]);
        bounds[3] = Math.max(bounds[3], lon);
        return true;
    }

    /**
     * Kacheln, die den Kreis (x, z, radius) berühren, nächste zuerst (Abstand der Kachelmitte zum Punkt, bei
     * Gleichstand tx, dann tz – gleiche Eingabe, gleiche Reihenfolge). Für /bm weltbau &lt;Ort&gt; [radius].
     */
    static List<Tile> around(double x, double z, int radius) {
        Tile c = at(x, z);
        int n = radius / SIZE + 1;
        List<Tile> out = new ArrayList<>();
        for (int tx = c.tx() - n; tx <= c.tx() + n; tx++) {
            for (int tz = c.tz() - n; tz <= c.tz() + n; tz++) {
                Tile t = new Tile(tx, tz);
                // Nächster Punkt der Kachel zum Mittelpunkt – liegt er außerhalb des Kreises, bleibt die Kachel weg.
                double nx = Math.max(t.x0(), Math.min(x, t.x0() + SIZE - 1));
                double nz = Math.max(t.z0(), Math.min(z, t.z0() + SIZE - 1));
                if (Math.hypot(nx - x, nz - z) <= radius) {
                    out.add(t);
                }
            }
        }
        out.sort(nearest(List.of(new double[] {x, z})));
        return out;
    }

    /**
     * Kacheln im Quadrat ±radiusTiles um die Kachel jedes Spielers (1 → 3 × 3), ohne Dubletten, nächste zuerst: Abstand
     * der Kachelmitte zum nächsten Spieler. Für den Automatikbetrieb.
     */
    static List<Tile> near(Collection<double[]> players, int radiusTiles) {
        Set<Tile> set = new LinkedHashSet<>();
        for (double[] p : players) {
            Tile c = at(p[0], p[1]);
            for (int tx = c.tx() - radiusTiles; tx <= c.tx() + radiusTiles; tx++) {
                for (int tz = c.tz() - radiusTiles; tz <= c.tz() + radiusTiles; tz++) {
                    set.add(new Tile(tx, tz));
                }
            }
        }
        List<Tile> out = new ArrayList<>(set);
        out.sort(nearest(players));
        return out;
    }

    private static Comparator<Tile> nearest(Collection<double[]> points) {
        return Comparator.<Tile>comparingDouble(t -> {
            double best = Double.MAX_VALUE;
            for (double[] p : points) {
                best = Math.min(best, Math.hypot(t.centerX() + 0.5 - p[0], t.centerZ() + 0.5 - p[1]));
            }
            return best;
        }).thenComparingInt(Tile::tx).thenComparingInt(Tile::tz);
    }

    /**
     * Etappen einer Kachel in Kachelkoordinaten (Ursprung = Kachelmitte): 4 × 4 Ausschnitte zu 256 m, von der Mitte
     * nach außen. Eine ganze Kachel in EINEM Plan wäre in einer Innenstadt weit über der Grenze je Etappe (München
     * Mitte: bis ~3 Mio. Blöcke je 256 m) – und der Speicher soll wie beim Stadtbau nur zwei Etappen halten.
     */
    static List<CityPlanner.Clip> stages() {
        List<CityPlanner.Clip> out = new ArrayList<>();
        int half = SIZE / 2;
        int far = Integer.MAX_VALUE / 2;  // kein Kreis: die Kachel ist ein Quadrat
        for (int x = -half; x < half; x += STAGE) {
            for (int z = -half; z < half; z += STAGE) {
                out.add(new CityPlanner.Clip(x, z, x + STAGE, z + STAGE, far));
            }
        }
        out.sort(Comparator.<CityPlanner.Clip>comparingDouble(c -> Math.hypot(c.x0() + STAGE / 2.0,
                c.z0() + STAGE / 2.0)).thenComparingInt(CityPlanner.Clip::x0).thenComparingInt(CityPlanner.Clip::z0));
        return List.copyOf(out);
    }

    /**
     * Bau-Reihenfolge: Kandidaten in ihrer Reihenfolge, ohne die in skip (fertig, aufgegeben) und ohne Kacheln in einer
     * Netzlücke der Projektion (box() == null). Kacheln über der Datumsgrenze werden seit 2026-10-29 gebaut (zwei
     * Abfragen). gaps merkt sich gefundene Lücken (wird ergänzt), damit die Automatik nicht alle 10 s dieselben Punkte
     * je Kachel neu projiziert.
     */
    static List<Tile> todo(List<Tile> candidates, EarthProjection p, Set<Long> skip, Set<Long> gaps) {
        List<Tile> out = new ArrayList<>();
        for (Tile t : candidates) {
            if (skip.contains(t.key()) || gaps.contains(t.key())) {
                continue;
            }
            if (box(p, t, MARGIN) == null) {
                gaps.add(t.key());
                continue;
            }
            out.add(t);
        }
        return out;
    }

    /**
     * Projektion für den Import einer Kachel: Erdwelt-Projektion minus Kachelmitte, gerundet auf Blöcke. round(w) − c
     * statt round(w − c): gleich, solange c ganzzahlig ist, aber so sieht man, dass jede Kachel dieselbe Weltzelle
     * trifft – ein Haus an der Kachelgrenze landet in beiden Kacheln auf demselben Block. Punkte ohne Stelle in der
     * Welt (null, NaN) → null, der Import lässt sie weg.
     */
    static Projector projector(EarthProjection p, Tile t) {
        int cx = t.centerX();
        int cz = t.centerZ();
        return (lat, lon) -> {
            double[] w = p.toWorld(lat, lon);
            if (w == null || w.length < 2 || !Double.isFinite(w[0]) || !Double.isFinite(w[1])) {
                return null;
            }
            return new CityModel.P((int) Math.round(w[0]) - cx, (int) Math.round(w[1]) - cz);
        };
    }

    /** Eine Zeile der Merkliste: „tx tz“. Kommentare (#) und Leerzeilen → null; Unlesbares wirft. */
    static Tile parseLine(String line) {
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("#")) {
            return null;
        }
        String[] parts = t.split("\\s+");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Zeile ohne zwei Zahlen: " + t);
        }
        return new Tile(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    static String formatLine(Tile t) {
        return String.format(Locale.ROOT, "%d %d", t.tx(), t.tz());
    }
}
