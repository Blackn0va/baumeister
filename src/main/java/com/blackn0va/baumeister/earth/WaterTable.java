package com.blackn0va.baumeister.earth;

import com.blackn0va.baumeister.terrain.ElevationTiles;

import java.io.IOException;

/**
 * Wasser je Säule der Erdwelt: Meer bis Meereshöhe, Wasserfläche auf eigener Höhe (See, Lagune, Meer ohne Tiefen-
 * daten) oder trocken (Polder, Death Valley). Die Höhe der Säule (y der Oberkante) ändert sich dadurch NIE – nur, ob
 * und wie hoch Wasser darüber steht (Gedächtnis: Höhenmodell einer erkundeten Erdwelt nicht ändern).
 *
 * Grundlage, gemessen 2026-10-29 an echten Terrarium-Kacheln (FlatProbe, je 64 × 64 Rohpixel bei Zoom 15):
 * - Wasser ist ganzzahlig: Meeresboden (Nordsee −16, Wattenmeer −5, Themse −13), Seeflächen (Totes Meer −412,
 *   Kaspisches Meer −29, Saltonsee −69, IJsselmeer −1) und Meer ohne Tiefendaten (Mittelmeer, Biskaya, Schwarzes
 *   Meer, Fjorde, Venedig: genau 0) – je 100 % der Pixel.
 * - Land unter 0 m ist es in Gebieten mit gemischten Quellen nicht: Haarlemmermeer, Flevopolder, Zuidplaspolder,
 *   Badwater, Jericho, Qattara, Turpan, Imperial Valley – 0–2 % ganzzahlige Pixel. ABER: Wo Terrarium SRTM nimmt, ist
 *   auch Land ganzzahlig (LakeRuleProbe 2026-10-29: Atyrau 18–29 %, Astrachan 27 % „3 von 4 Ecken ganzzahlig“).
 * - Offene See und das Kaspische Meer enthalten kaputte Pixel um −16 109 bzw. −9 935 (Atlantik, Golf von Mexiko,
 *   Bohai; im Kaspischen Meer ganze Pixelzeilen) – Bruchwerte, aber tief.
 *
 * Entscheidung (below = Oberkante unter dem Meeresspiegel):
 * - alle vier Ecken genau 0 → Wasserfläche auf 0. Vorher war das offene Mittelmeer eine Sandebene auf y 0 ohne Wasser
 *   (Oberkante = Meeresspiegel), ebenso Biskaya, Schwarzes Meer, Fjorde, Lagunen. Bewusst OHNE Fenster: Der
 *   Mississippi bei New Orleans (15/8185/13521) ist eine Fläche aus genau 0 mit eingestreuten Bruchwert-Pixeln – mit
 *   Fenster 12 × 12 fiel der Wasseranteil dort von 39,1 auf 4,6 % (WaterPaintProbe 2026-10-29). Dass SRTM-Land auf
 *   genau 0 m so Pfützen bekommt, ist möglich, aber an keiner Kachel gemessen.
 * - nicht below → wie bisher (Land).
 * - offen (BasinMap): tiefer als DEEP_FILL oder mindestens 3 von 4 Ecken wasserartig → Meer; sonst Land unter 0 m:
 *   flacher als SHORE → Meer (Uferstreifen, geglättete Buchten wie Puget Sound −0,6 m), tiefer → trocken (Polder).
 * - geschlossen: Seefläche nur, wo ein Fenster von WINDOW × WINDOW Rohpixeln ganz flach auf einer ganzzahligen Höhe
 *   liegt und die Säule selbst auf dieser Fläche liegt; sonst trocken (Death Valley, Qattara, Ufer des Kaspischen
 *   Meers). Kaputte Pixel in der Fläche bekommen Wasser bis zur Fläche (LAKE), nicht bis 0.
 *
 * Warum das Fenster nur im geschlossenen Becken: Vorher entschieden dort die vier Ecken („3 von 4 ganzzahlig“). An
 * SRTM-Land gemessen (LakeRuleProbe, Anteil der Stellen unter 0 m, die Wasserfläche werden): Atyrau 29,2 / 23,8 /
 * 17,9 %, Astrachan 26,6 % – mit Fenster 12 × 12 nur noch 2,3 / 0,9 / 1,1 / 1,0 %. Seen bleiben: Kaspisches Meer
 * 97,7–100 %, Totes Meer und Saltonsee 100 %, IJsselmeer 89,8 % (der Rest ist ein Uferstreifen von höchstens sechs
 * Pixeln auf Seehöhe, trocken, aber ohne Leck). Im offenen Meer würde dasselbe Fenster entlang jeder Küste einen
 * trockenen Graben unter 0 m ziehen (Säulen, die nach SHORE trocken fallen) – dort bleiben die vier Ecken.
 *
 * Warum DEEP_FILL nicht mehr vor der Senken-Prüfung steht: Vorher war jede Säule unter −500 m Meer bis 0 – auch die
 * kaputten Pixelzeilen mitten im Kaspischen Meer (15/20980/12164: 2 048 Säulen, 15/21026/12523: 6 108). Dort stand
 * dann eine Wassersäule 29 Blöcke über dem Seespiegel, seitlich an Luft. Wer die Abkürzung zurückholt, holt die
 * Wasserwände zurück.
 *
 * „3 von 4“ statt „alle 4“ im offenen Meer: an der Grenze zweier Tiefenblöcke ist alles ganzzahlig, aber ein einzelnes
 * zufällig ganzzahliges Landpixel (2 % in Poldern) machte sonst 8 % der Polder nass; drei von vier zufällig ≈ 0,003 %.
 *
 * Threads: zustandslos bis auf BasinMap und die Rohpixel-Quelle (beide threadsicher).
 */
public final class WaterTable {

    /** Was über der Oberkante steht. */
    public enum Kind {
        /** kein Wasser (Land oder trockene Senke) */
        NONE,
        /** Wasser von der Oberkante bis zum Meeresspiegel (wie bisher jedes Meer) */
        FILL,
        /** der oberste Block selbst ist Wasser (Wasserfläche auf eigener Höhe) */
        SURFACE,
        /** Wasser von der Oberkante bis zur Seefläche level (kaputte Pixel unter einer Seefläche) */
        LAKE
    }

    /**
     * Ergebnis je Säule.
     *
     * @param level Höhe der Wasserfläche in Metern (SURFACE, LAKE), sonst NaN
     * @param lake  Wasser eines geschlossenen Beckens (See auf eigener Höhe, nicht Meer) – der Uferschutz trennt Seen
     *              unterschiedlicher Höhe, Meer gegen Meer nicht
     */
    public record Water(Kind kind, double level, boolean lake) {
        static final Water NONE = new Water(Kind.NONE, Double.NaN, false);
        static final Water FILL = new Water(Kind.FILL, Double.NaN, false);
    }

    /** Ob eine Stelle mit dem Ozean zusammenhängt (BasinMap). */
    @FunctionalInterface
    public interface Basins {
        boolean open(double lat, double lon) throws IOException;
    }

    /** Rohpixel-Kacheln der feinen Stufe (im Plugin ElevationTiles::tile bei Zoom 15). */
    @FunctionalInterface
    public interface Raw {
        float[] tile(int tx, int ty) throws IOException;
    }

    /** Tiefer als jedes Land der Erde (Totes Meer −430 m, in den Daten −412) – im offenen Becken immer Meer. */
    public static final double DEEP_FILL = 500;
    /** Land, das höchstens so tief unter 0 m liegt, bleibt in offenen Gebieten Meer (Uferstreifen). */
    public static final double SHORE = 1.5;
    /** Rohpixel so tief oder tiefer gelten als Wasser, auch als Bruchwert (kaputte Pixel um −16 109 m). */
    static final double DEEP_PIXEL = 450;
    /**
     * Seitenlänge des Fensters (Rohpixel) für Seeflächen im geschlossenen Becken. Gemessen (LakeRuleProbe): 8 ließ in
     * Atyrau noch 6,3 % Pfützen, 12 noch 2,3 %; Seen verlieren dabei nur einen Uferstreifen.
     */
    static final int WINDOW = 12;
    private static final double MAX_LAT = 85.0511287798;

    private final Basins basins;
    /** null = nur die vier Ecken (Tests, alter Stand). */
    private final Raw raw;
    private final int zoom;

    public WaterTable(Basins basins) {
        this(basins, null, ElevationTiles.ZOOM);
    }

    /**
     * @param raw  Rohpixel derselben Stufe, aus der die Ecken kommen, oder null
     * @param zoom Zoom dieser Stufe (Plugin: 15)
     */
    public WaterTable(Basins basins, Raw raw, int zoom) {
        this.basins = basins;
        this.raw = raw;
        this.zoom = zoom;
    }

    /**
     * @param real    Höhe laut Daten (m)
     * @param corners die vier Rohpixel oder null (Daten fehlten, Höhe geschätzt)
     * @param below   Oberkante liegt unter dem Meeresspiegel
     * @throws IOException wenn BasinMap keine Kacheln bekommt (Aufrufer: wie bisher Meer, Säule geschätzt)
     */
    public Kind kind(double lat, double lon, double real, double[] corners, boolean below) throws IOException {
        return water(lat, lon, real, corners, below).kind();
    }

    /** Wie kind, samt Höhe der Wasserfläche. */
    public Water water(double lat, double lon, double real, double[] corners, boolean below) throws IOException {
        Window w = raw == null || corners == null ? null : () -> flatLevel(lat, lon);
        return decideWater(real, corners, below, basins, lat, lon, w);
    }

    /** Nur die Entscheidung über die vier Ecken (Fenster = die Ecken selbst). */
    static Kind decide(double real, double[] corners, boolean below, Basins basins, double lat, double lon)
            throws IOException {
        return decideWater(real, corners, below, basins, lat, lon, null).kind();
    }

    /** Flache Höhe rund um die Säule: alle nicht kaputten Pixel gleich und ganzzahlig, sonst NaN. */
    @FunctionalInterface
    interface Window {
        double level() throws IOException;
    }

    static Water decideWater(double real, double[] corners, boolean below, Basins basins, double lat, double lon,
            Window window) throws IOException {
        if (corners != null && corners[0] == 0 && corners[1] == 0 && corners[2] == 0 && corners[3] == 0) {
            return new Water(Kind.SURFACE, 0, false);
        }
        if (!below) {
            return Water.NONE;
        }
        if (basins.open(lat, lon)) {
            // Ein kaputtes Pixel genügt: Es liegt im Wasser (IJsselmeer 15/16875/10749). Vorher zählte es nur als eine
            // der drei nötigen Ecken – zwei kaputte und zwei Uferpixel ergaben eine trockene Grube bis zum Meeresboden
            // mitten im See, gemessen 21 bzw. 31 Säulen auf je 256 × 256 Blöcken (WaterPaintProbe).
            boolean water = corners != null && (waterLike(corners) || anyBroken(corners));
            if (real <= -DEEP_FILL || water || corners == null || real >= -SHORE) {
                return Water.FILL;
            }
            return Water.NONE;
        }
        if (corners == null) {
            return Water.NONE;
        }
        // Erst die vier Ecken (billig): Nur wo alle nicht kaputten Ecken auf derselben ganzzahligen Höhe liegen, lohnt
        // das Fenster – Death Valley, Qattara und das Land ums Kaspische Meer brauchen so keine 144 Pixel je Säule.
        // Liegen alle vier Ecken in einer kaputten Zeile (Kaspisches Meer: zwei Zeilen breit), entscheidet das Fenster.
        double own = flatCorners(corners);
        boolean allBroken = corners[0] <= -DEEP_PIXEL && corners[1] <= -DEEP_PIXEL && corners[2] <= -DEEP_PIXEL
                && corners[3] <= -DEEP_PIXEL;
        if (Double.isNaN(own) && !allBroken) {
            return Water.NONE;
        }
        double level = window != null ? window.level() : own;
        if (Double.isNaN(level) || (!allBroken && own != level)) {
            // Kein flaches Fenster, oder die Säule liegt am Rand der Fläche (Ecke auf anderer Höhe): trocken.
            return Water.NONE;
        }
        return new Water(anyBroken(corners) ? Kind.LAKE : Kind.SURFACE, level, true);
    }

    /** Alle nicht kaputten Ecken gleich und ganzzahlig, mindestens eine nicht kaputt – sonst NaN. */
    static double flatCorners(double[] corners) {
        double v = Double.NaN;
        for (double c : corners) {
            if (c <= -DEEP_PIXEL) {
                continue;
            }
            if (c != Math.rint(c) || (!Double.isNaN(v) && c != v)) {
                return Double.NaN;
            }
            v = c;
        }
        return v;
    }

    /**
     * Fenster WINDOW × WINDOW um die vier Ecken der Säule (dieselbe Pixelrechnung wie ElevationTiles.elevation):
     * flach, wenn alle nicht kaputten Pixel gleich und ganzzahlig sind und mindestens ein Viertel nicht kaputt ist.
     * Hängt nur am Pixel, nicht am Chunk – beide Chunks einer Grenze sehen dasselbe Fenster.
     */
    double flatLevel(double lat, double lon) throws IOException {
        double la = Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
        int px = (int) Math.floor(ElevationTiles.tileX(lon, zoom) * 256 - 0.5);
        int py = (int) Math.floor(ElevationTiles.tileY(la, zoom) * 256 - 0.5);
        int n = 256 << zoom;
        int half = WINDOW / 2;
        double v = Double.NaN;
        int count = 0;
        long key = Long.MIN_VALUE;
        float[] t = null;
        for (int y = py - half + 1; y <= py + half; y++) {
            int gy = Math.max(0, Math.min(n - 1, y));
            for (int x = px - half + 1; x <= px + half; x++) {
                int gx = Math.floorMod(x, n);
                long k = (long) (gx >> 8) << 32 | (gy >> 8);
                if (k != key) {
                    t = raw.tile(gx >> 8, gy >> 8);
                    key = k;
                }
                double p = t[(gy & 255) * 256 + (gx & 255)];
                if (p <= -DEEP_PIXEL) {
                    continue;
                }
                if (p != Math.rint(p) || (count > 0 && p != v)) {
                    return Double.NaN;
                }
                v = p;
                count++;
            }
        }
        return count * 4 >= WINDOW * WINDOW ? v : Double.NaN;
    }

    /** Mindestens eine Ecke ist ein kaputtes (sehr tiefes) Pixel. */
    static boolean anyBroken(double[] corners) {
        for (double c : corners) {
            if (c <= -DEEP_PIXEL) {
                return true;
            }
        }
        return false;
    }

    /** Mindestens drei der vier Rohpixel ganzzahlig oder sehr tief. */
    static boolean waterLike(double[] corners) {
        int n = 0;
        for (double c : corners) {
            if (c == Math.rint(c) || c <= -DEEP_PIXEL) {
                n++;
            }
        }
        return n >= 3;
    }
}
