package com.blackn0va.baumeister.osm;

import com.blackn0va.baumeister.osm.CityModel.P;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Straße (und Hausnummer) in den Kartenkacheln eines gebauten Orts finden – für /bm ort &lt;Stadt&gt; &lt;Straße&gt; [Nr]
 * (Wunsch 2026-10-30).
 *
 * Gesucht wird in denselben Overpass-Kacheln, aus denen der Ort gebaut wurde (Zwischenspeicher, kein Netz), und
 * umgerechnet mit derselben Projektion wie OsmImport – der Punkt liegt also genau auf der gebauten Straße.
 *
 * Name verzeihend: klein, Umlaute und ß ausgeschrieben, nur Buchstaben und Ziffern, „straße/strasse/str“ als „str“.
 * Passt kein Name genau, gilt der mit dem kleinsten Abstand (Levenshtein), höchstens 1 je angefangene 8 Zeichen –
 * „Kepplerstrasse“ findet „Keplerstraße“ (Abstand 1). Ein genau passender Name hat immer Vorrang (Abstand 0).
 *
 * Ziel: ohne Nummer die Mitte des längsten Abschnitts; mit Nummer der Punkt der Straße, der dem Haus am nächsten
 * liegt, Blick zum Haus. Fehlt die Nummer in den Daten, geht es zur Straßenmitte (notFound = true).
 *
 * Threads: rein, beliebig. Fehler: kaputte Kacheln oder Elemente werden übersprungen, nie geworfen.
 */
public final class StreetFinder {

    private static final double M_PER_DEG_LAT = 110_540.0;
    private static final double M_PER_DEG_LON = 111_320.0;

    /**
     * x/z in Metern relativ zur Mitte des Orts (wie die Zellen des Stadtbaus), yaw im Minecraft-Sinn (0 = Süden).
     * numberMissing: eine Hausnummer war verlangt, steht aber an keinem Haus dieser Straße.
     */
    public record Hit(String street, String number, int x, int z, float yaw, boolean numberMissing) {
    }

    private StreetFinder() {
    }

    /** Kanonischer Straßenname zum Vergleichen. Rein, testbar. */
    public static String norm(String s) {
        String t = s.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("ß", "ss").replaceAll("[^a-z0-9]", "");
        return t.replaceAll("(strasse|str)$", "str");
    }

    /**
     * Wie weit ein getippter Name abweichen darf: 1 Zeichen je angefangene 8. Vorher je 6: „Hauptstrasse“ (8 Zeichen
     * als „hauptstr“) traf mit Abstand 2 die Hauffstraße – eine echte andere Straße ist schlimmer als „nicht gefunden“.
     */
    static int tolerance(String normalized) {
        return Math.max(1, (normalized.length() + 7) / 8);
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private record Way(String name, List<P> points) {
    }

    private record House(String street, String number, P centre) {
    }

    /**
     * @param tiles  Overpass-JSON der Kacheln (out geom)
     * @param street wie getippt („kepplerstrasse“)
     * @param number Hausnummer oder null
     */
    public static Optional<Hit> find(List<String> tiles, double lat0, double lon0, String street, String number) {
        Search search = new Search(lat0, lon0, street, number);
        for (String tile : tiles) {
            search.add(tile);
        }
        return search.result();
    }

    /**
     * Suche Kachel für Kachel. Vorher las CityService alle frischen Kacheln einer Stadt als entpackte Strings in eine
     * Liste, und read() behielt jeden benannten Weg und jedes Haus mit Adresse – bei radius-max-ganz 25 km über
     * tausend Kacheln, eine Münchner Kachel allein 4,6 MB: ein einziges /bm ort konnte den 3-GB-Server in einen
     * OutOfMemoryError treiben, gegen die Speicherregel aus CityStages („höchstens zwei Kartenkacheln“). Jetzt liegt
     * nur die gerade gelesene Kachel im Speicher, und add() behält nur Wege und Häuser, deren Name innerhalb der
     * Toleranz liegt. Das Ergebnis bleibt dasselbe: find nimmt nur einen Weg mit Abstand ≤ tolerance(wanted) (bei
     * Gleichstand den ersten), und Häuser zählen nur bei genau diesem Namen – beide überstehen den Filter in derselben
     * Reihenfolge. Wer den Filter entfernt, holt den Speicherbedarf der ganzen Stadt zurück.
     *
     * Threads: nicht threadsicher, eine Suche gehört einem Thread.
     */
    public static final class Search {

        private final double lat0;
        private final double lon0;
        private final double cos;
        private final String wanted;
        private final int tolerance;
        private final String number;
        private final List<Way> ways = new ArrayList<>();
        private final List<House> houses = new ArrayList<>();

        public Search(double lat0, double lon0, String street, String number) {
            this.lat0 = lat0;
            this.lon0 = lon0;
            this.cos = Math.cos(Math.toRadians(lat0));
            this.wanted = norm(street);
            this.tolerance = tolerance(wanted);
            this.number = number;
        }

        /** Eine Kachel (Overpass-JSON, out geom) durchsuchen; kaputte Kacheln werden übersprungen. */
        public void add(String tile) {
            if (wanted.isEmpty()) {
                return;  // ohne Namen gibt es nichts zu finden – nichts behalten
            }
            read(tile, lat0, lon0, cos, ways, houses, name -> distance(wanted, norm(name)) <= tolerance);
        }

        /** Behaltene Wege – nur für den Test der Speicherregel. */
        int retainedWays() {
            return ways.size();
        }

        public Optional<Hit> result() {
            if (wanted.isEmpty()) {
                return Optional.empty();
            }
            return pick(ways, houses, wanted, number);
        }
    }

    private static Optional<Hit> pick(List<Way> ways, List<House> houses, String wanted, String number) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Way w : ways) {
            int d = distance(wanted, norm(w.name()));
            if (d < bestDistance) {
                bestDistance = d;
                best = w.name();
            }
        }
        if (best == null || bestDistance > tolerance(wanted)) {
            return Optional.empty();
        }
        String name = best;
        String key = norm(name);
        List<Way> mine = ways.stream().filter(w -> norm(w.name()).equals(key)).toList();
        if (number != null && !number.isBlank()) {
            String n = number.trim().toLowerCase(Locale.ROOT);
            for (House h : houses) {
                if (norm(h.street()).equals(key) && h.number().trim().toLowerCase(Locale.ROOT).equals(n)) {
                    P at = nearest(mine, h.centre());
                    return Optional.of(new Hit(name, h.number(), at.x(), at.z(), yaw(at, h.centre()), false));
                }
            }
        }
        Way longest = mine.getFirst();
        for (Way w : mine) {
            if (length(w.points()) > length(longest.points())) {
                longest = w;
            }
        }
        P mid = middle(longest.points());
        P ahead = longest.points().get(Math.min(longest.points().size() - 1,
                longest.points().indexOf(mid) + 1));
        float yaw = ahead.equals(mid) ? 0f : yaw(mid, ahead);
        boolean asked = number != null && !number.isBlank();
        return Optional.of(new Hit(name, asked ? number.trim() : null, mid.x(), mid.z(), yaw, asked));
    }

    private static void read(String tile, double lat0, double lon0, double cos, List<Way> ways, List<House> houses,
            Predicate<String> keep) {
        JsonObject root;
        try {
            root = JsonParser.parseString(tile).getAsJsonObject();
        } catch (RuntimeException e) {
            return;  // kaputte Kachel: Stadtbau hätte sie ebenso verworfen
        }
        if (!root.has("elements") || !root.get("elements").isJsonArray()) {
            return;
        }
        for (JsonElement el : root.getAsJsonArray("elements")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject e = el.getAsJsonObject();
            if (!e.has("tags") || !e.get("tags").isJsonObject() || !e.has("geometry")
                    || !e.get("geometry").isJsonArray()) {
                continue;
            }
            JsonObject tags = e.getAsJsonObject("tags");
            boolean way = tags.has("highway") && tags.has("name") && keep.test(tags.get("name").getAsString());
            boolean house = tags.has("addr:street") && tags.has("addr:housenumber")
                    && keep.test(tags.get("addr:street").getAsString());
            if (!way && !house) {
                continue;  // Punkte nur für Behaltenes umrechnen
            }
            List<P> pts = points(e.getAsJsonArray("geometry"), lat0, lon0, cos);
            if (pts.isEmpty()) {
                continue;
            }
            if (way) {
                ways.add(new Way(tags.get("name").getAsString(), pts));
            }
            if (house) {
                houses.add(new House(tags.get("addr:street").getAsString(),
                        tags.get("addr:housenumber").getAsString(), centre(pts)));
            }
        }
    }

    private static List<P> points(JsonArray geometry, double lat0, double lon0, double cos) {
        List<P> out = new ArrayList<>(geometry.size());
        for (JsonElement g : geometry) {
            if (!g.isJsonObject() || !g.getAsJsonObject().has("lat") || !g.getAsJsonObject().has("lon")) {
                continue;
            }
            double lat = g.getAsJsonObject().get("lat").getAsDouble();
            double lon = g.getAsJsonObject().get("lon").getAsDouble();
            // Wie OsmImport.project: gleichabständig um die Mitte des Orts.
            out.add(new P((int) Math.round((lon - lon0) * cos * M_PER_DEG_LON),
                    (int) Math.round(-(lat - lat0) * M_PER_DEG_LAT)));
        }
        return out;
    }

    /** Mittel der Eckpunkte; der Schlusspunkt eines geschlossenen Umrisses (= erster) zählt nicht doppelt. */
    private static P centre(List<P> pts) {
        int n = pts.size() > 1 && pts.getFirst().equals(pts.getLast()) ? pts.size() - 1 : pts.size();
        long x = 0;
        long z = 0;
        for (int i = 0; i < n; i++) {
            x += pts.get(i).x();
            z += pts.get(i).z();
        }
        return new P((int) Math.round((double) x / n), (int) Math.round((double) z / n));
    }

    private static double length(List<P> pts) {
        double l = 0;
        for (int i = 1; i < pts.size(); i++) {
            l += Math.hypot(pts.get(i).x() - pts.get(i - 1).x(), pts.get(i).z() - pts.get(i - 1).z());
        }
        return l;
    }

    /** Stützpunkt auf halber Länge. */
    private static P middle(List<P> pts) {
        double half = length(pts) / 2;
        double l = 0;
        for (int i = 1; i < pts.size(); i++) {
            l += Math.hypot(pts.get(i).x() - pts.get(i - 1).x(), pts.get(i).z() - pts.get(i - 1).z());
            if (l >= half) {
                return pts.get(i);
            }
        }
        return pts.getFirst();
    }

    /** Nächster Punkt auf den Abschnitten der Straße (auch zwischen zwei Stützpunkten). */
    private static P nearest(List<Way> ways, P to) {
        P best = ways.getFirst().points().getFirst();
        double bestD = Double.MAX_VALUE;
        for (Way w : ways) {
            List<P> pts = w.points();
            for (int i = 0; i < pts.size(); i++) {
                P a = pts.get(i);
                P b = i + 1 < pts.size() ? pts.get(i + 1) : a;
                double dx = b.x() - a.x();
                double dz = b.z() - a.z();
                double len2 = dx * dx + dz * dz;
                double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((to.x() - a.x()) * dx + (to.z() - a.z()) * dz)
                        / len2));
                double px = a.x() + t * dx;
                double pz = a.z() + t * dz;
                double d = Math.hypot(to.x() - px, to.z() - pz);
                if (d < bestD) {
                    bestD = d;
                    best = new P((int) Math.round(px), (int) Math.round(pz));
                }
            }
        }
        return best;
    }

    /** Minecraft-Blickrichtung von a nach b: 0 = +z (Süden), 90 = −x (Westen). */
    static float yaw(P a, P b) {
        return (float) Math.toDegrees(Math.atan2(-(b.x() - a.x()), b.z() - a.z()));
    }
}
