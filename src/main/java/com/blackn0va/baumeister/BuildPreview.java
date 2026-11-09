package com.blackn0va.baumeister;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bauvorschau: geplante, aber noch nicht gebaute Aufträge je Spieler, mit Ablaufzeit – der Kern ohne Bukkit.
 *
 * Vorher baute jeder Plan sofort los; ob das LLM das Haus quer über den Weg oder in den Hang setzt, sah der Spieler
 * erst am fertigen Bau (und räumte mit /bm rückgängig). Jetzt kann er mit „/bm vorschau …“ oder „Baumeister, zeig mir
 * …“ erst den Umriss sehen (Partikel, BuildService) und mit „ja“ bauen lassen. Die Vorschau hält den fertigen Plan,
 * keine Freigabe: Rechteprüfung und Kistenschutz laufen beim Bestätigen wie bei jedem Bau vor dem ersten Block.
 *
 * Eine Vorschau verfällt nach der eingestellten Zeit (komfort.vorschau-sekunden). Ohne Ablauf bliebe ein vergessener
 * Plan stundenlang bestätigbar, und ein beiläufiges „ja“ im Chat baute ihn an einem Ort, der längst anders aussieht.
 *
 * Threads: nur Hauptthread. Fehler: keine Ausnahmen.
 *
 * @param <T> der bestätigbare Plan (in BuildService ein privater Record)
 */
final class BuildPreview<T> {

    /** Achsenparalleler Kasten in Weltkoordinaten, beide Ecken eingeschlossen. */
    record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        /** Aus zwei beliebigen Ecken. */
        static Box of(int x1, int y1, int z1, int x2, int y2, int z2) {
            return new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2),
                    Math.max(z1, z2));
        }

        int sizeX() {
            return maxX - minX + 1;
        }

        int sizeY() {
            return maxY - minY + 1;
        }

        int sizeZ() {
            return maxZ - minZ + 1;
        }

        double centerX() {
            return (minX + maxX + 1) / 2.0;
        }

        double centerZ() {
            return (minZ + maxZ + 1) / 2.0;
        }
    }

    /** Eine offene Vorschau. */
    record Pending<T>(UUID owner, String world, Box box, int blocks, T plan, long expiresAt) {
    }

    private final Map<UUID, Pending<T>> open = new HashMap<>();
    private final LongSupplier clock;

    BuildPreview(LongSupplier clockMillis) {
        this.clock = clockMillis;
    }

    /** Neue Vorschau; eine ältere desselben Spielers wird ersetzt (zurück kommt sie, sonst null). */
    Pending<T> offer(UUID owner, String world, Box box, int blocks, T plan, long lifetimeMillis) {
        return open.put(owner, new Pending<>(owner, world, box, blocks, plan, clock.getAsLong() + lifetimeMillis));
    }

    /** Offen und nicht verfallen? */
    boolean has(UUID owner) {
        Pending<T> p = open.get(owner);
        return p != null && p.expiresAt() > clock.getAsLong();
    }

    /**
     * Offene, nicht verfallene Vorschau ansehen, ohne sie herauszunehmen. ComfortFlow.confirm nimmt sie erst heraus,
     * wenn feststeht, dass sie gebaut oder eingereiht wird.
     */
    Pending<T> peek(UUID owner) {
        Pending<T> p = open.get(owner);
        return p == null || p.expiresAt() <= clock.getAsLong() ? null : p;
    }

    /** Offene Vorschau herausnehmen (Bestätigen); null, wenn keine da oder sie verfallen ist. */
    Pending<T> take(UUID owner) {
        Pending<T> p = open.remove(owner);
        if (p == null || p.expiresAt() <= clock.getAsLong()) {
            return null;
        }
        return p;
    }

    /** Verwerfen; true, wenn eine (auch verfallene) da war. */
    boolean discard(UUID owner) {
        return open.remove(owner) != null;
    }

    /** Verfallene herausnehmen und zurückgeben – der Aufrufer sagt dem Spieler Bescheid. */
    List<Pending<T>> expire() {
        long now = clock.getAsLong();
        List<Pending<T>> gone = new ArrayList<>();
        for (Iterator<Pending<T>> it = open.values().iterator(); it.hasNext();) {
            Pending<T> p = it.next();
            if (p.expiresAt() <= now) {
                gone.add(p);
                it.remove();
            }
        }
        return gone;
    }

    /** Noch gültige Vorschauen (zum Zeichnen). */
    List<Pending<T>> live() {
        long now = clock.getAsLong();
        List<Pending<T>> out = new ArrayList<>();
        for (Pending<T> p : open.values()) {
            if (p.expiresAt() > now) {
                out.add(p);
            }
        }
        return out;
    }

    /** Restzeit in Sekunden (aufgerundet), 0 ohne gültige Vorschau. */
    long secondsLeft(UUID owner) {
        Pending<T> p = open.get(owner);
        long left = p == null ? 0 : p.expiresAt() - clock.getAsLong();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    boolean isEmpty() {
        return open.isEmpty();
    }

    /** Übernahme nach /bm neuladen. */
    void takeOver(BuildPreview<T> old) {
        open.putAll(old.open);
        old.open.clear();
    }

    // ---------------------------------------------------------------- Umriss

    /**
     * Punkte entlang der 12 Kanten des Kastens (Blockkanten, also Ecken bei min und max + 1), höchstens maxPoints. Bei
     * großen Bauten wächst der Abstand zwischen den Punkten statt der Zahl – 192 × 120 × 192 Blöcke mit einem Punkt je
     * Block wären ~2 000 Partikel alle halbe Sekunde, das ruckelt beim Spieler.
     */
    static List<double[]> outline(Box b, int maxPoints) {
        double x0 = b.minX();
        double y0 = b.minY();
        double z0 = b.minZ();
        double x1 = b.maxX() + 1.0;
        double y1 = b.maxY() + 1.0;
        double z1 = b.maxZ() + 1.0;
        double perimeter = 4 * ((x1 - x0) + (y1 - y0) + (z1 - z0));
        // Je Kante kommen bis zu zwei Punkte über len/step hinzu (Aufrunden, Endpunkt): 12 Kanten → 24 Reserve. So
        // passt alles unter maxPoints, und subList unten schneidet nie eine ganze Kante ab.
        double step = Math.max(0.5, perimeter / Math.max(1, maxPoints - 24));
        List<double[]> pts = new ArrayList<>();
        for (double y : new double[] {y0, y1}) {
            for (double z : new double[] {z0, z1}) {
                edge(pts, x0, y, z, x1, y, z, step);
            }
        }
        for (double x : new double[] {x0, x1}) {
            for (double z : new double[] {z0, z1}) {
                edge(pts, x, y0, z, x, y1, z, step);
            }
            for (double y : new double[] {y0, y1}) {
                edge(pts, x, y, z0, x, y, z1, step);
            }
        }
        return pts.size() > maxPoints ? pts.subList(0, maxPoints) : pts;
    }

    /**
     * Kante von a nach b, beide Enden eingeschlossen. Ecken kommen dadurch doppelt vor – ohne Endpunkt fehlte die Ecke
     * (max, max, max), die keine Kante als Anfang hat.
     */
    private static void edge(List<double[]> pts, double ax, double ay, double az, double bx, double by, double bz,
            double step) {
        double len = Math.abs(bx - ax) + Math.abs(by - ay) + Math.abs(bz - az);
        int n = Math.max(1, (int) Math.ceil(len / step));
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            pts.add(new double[] {ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t});
        }
    }

    // ---------------------------------------------------------------- Chat-Wörter

    /** „zeig mir …“, „zeige mir …“, „vorschau: …“ – der Rest ist der Auftrag. */
    private static final Pattern SHOW = Pattern.compile("^(?:zeig(?:e)?\\s+mir|vorschau\\s*:?)\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);

    /** Bestätigen nur mit einer offenen Vorschau – sonst gehen diese Wörter wie bisher als Auftrag weiter. */
    private static final Set<String> CONFIRM = Set.of("ja", "bau", "bauen", "bau es", "bau das", "ja bau", "ja bau es",
            "ja bitte", "ok", "okay", "passt", "mach", "mach es", "mach das", "bestätigen");

    /** „abbrechen“ fehlt bewusst: Es ist das Stopp-Wort für einen laufenden Bau (ChatIntent). */
    private static final Set<String> DISCARD = Set.of("nein", "nee", "doch nicht", "verwerfen", "lass es", "lass das",
            "vorschau weg");

    /**
     * Auftrag aus „zeig mir …“ oder null. Ohne eigenes Verb wird „bau “ davorgesetzt – „zeig mir ein kleines Haus“
     * soll beim LLM und bei QuickOrders genau wie „bau ein kleines Haus“ ankommen.
     */
    static String showRequest(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = SHOW.matcher(text.trim());
        if (!m.matches()) {
            return null;
        }
        String rest = m.group(1).trim();
        if (rest.isEmpty()) {
            return null;
        }
        String lower = rest.toLowerCase(Locale.GERMAN);
        return lower.startsWith("bau ") || lower.startsWith("baue ") ? rest : "bau " + rest;
    }

    static boolean confirms(String text) {
        return CONFIRM.contains(normalize(text));
    }

    static boolean discards(String text) {
        return DISCARD.contains(normalize(text));
    }

    /** Wie ChatIntent: klein (deutsch), Satzzeichen am Ende weg; dazu Kommas zu Leerzeichen („ja, bau es“). */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.GERMAN).replaceAll("[!.?]+$", "").replace(',', ' ')
                .replaceAll("\\s+", " ").trim();
    }
}
