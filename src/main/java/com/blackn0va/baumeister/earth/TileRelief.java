package com.blackn0va.baumeister.earth;

import com.blackn0va.baumeister.terrain.ElevationTiles;

import java.io.IOException;

/**
 * Tiefster und höchster Punkt im Umkreis aus groben Terrarium-Kacheln (Zoom 10 ≈ 153 m je Pixel am Äquator) – jedes
 * Pixel im Kreis, nicht nur Stichproben: ein Gipfel ist schmal.
 *
 * Warum grob: Ein Fenster von 30 km Radius sind bei Zoom 15 rund 160 Kacheln (40 MB), bei Zoom 10 höchstens neun. Der
 * Bezug muss den höchsten Punkt nur ungefähr kennen – oben bleiben 41 Blöcke Luft (y 1990 bis 2031) als Polster
 * dafür, dass ein grobes Pixel einen spitzen Gipfel etwas zu niedrig zeigt. Wie stark Terrarium bei Zoom 10 Gipfel
 * glättet, ist NICHT gemessen.
 *
 * Abtastung: Zu den Polen hin werden Mercator-Pixel schmaler; dort wird jede n-te Spalte/Zeile genommen, damit eine
 * Abfrage nie mehr als 800 × 800 Pixel liest.
 *
 * Nicht reines Min/Max: Jedes Pixel wirkt mit Abstand schwächer (SLOPE, FULL_M) – „wirksames“ Minimum und Maximum.
 *
 * Threads: ElevationTiles ist threadsicher, sonst kein Zustand.
 */
public final class TileRelief implements LocalRelief {

    /*
     * Zwei Stufen (ReliefField nimmt das Stärkere):
     * - nah (Zoom 10, 12 km, 15 %): kennt schmale Gipfel genau und garantiert, dass sie unter der Decke bleiben. Die
     *   künstliche Neigung bis 15 % entsteht nur im Umkreis solcher Gipfel, wo das Gelände ohnehin steil ist.
     * - fern (Zoom 7, 700 km, 1 %): breite Hochflächen verschieben das ganze Gebiet fast gleichmäßig.
     * Vorher (2026-10-28) gab es nur ein 15-km-Fenster mit voller Wirkung jedes Pixels: Ein Gipfel über 1 990 m kippte
     * 15 km weiter das Flachland (Glockenberg 3 500 m auf 600 m: 54,5 % Steigung), und am Grand Canyon wäre der Rand
     * abgesenkt worden, der Fluss nicht – die Schlucht um bis zu 19 % flacher statt 1:1.
     */

    /** Nahe Stufe: Fenster, Abschwächung je Meter Abstand, Abstand mit voller Wirkung. */
    public static final double NEAR_RADIUS_M = 12_000;
    public static final double NEAR_SLOPE = 0.15;
    /**
     * Bis zu diesem Abstand wirkt ein Pixel voll: mehr als die halbe Diagonale einer Gitterzelle (0,02° ≈ 2,2 km →
     * 1,56 km). So ist jeder der vier Gitterpunkte um einen Gipfel mindestens so stark abgesenkt, wie der Gipfel es
     * braucht, und die bilineare Mischung dazwischen kann ihn nicht über die Decke heben.
     */
    public static final double FULL_M = 1_600;
    /**
     * Ferne Stufe: 1 % Neigung ist im Spiel nicht zu sehen. Das Fenster muss so groß sein, dass auch der höchste
     * Gipfel am Rand nichts mehr ausmacht ((8 848 − 1 990) / 0,01 ≈ 690 km) – sonst spränge der Bezug, sobald ein
     * Gipfel ins Fenster rückt. Wer die Neigung erhöht, verliert die Treue breiter Täler; wer sie senkt, verschiebt das
     * Alpenvorland (München) weg von der echten Meereshöhe.
     */
    public static final double FAR_RADIUS_M = 700_000;
    public static final double FAR_SLOPE = 0.01;
    /** Für die nahe Stufe: so blieb die Rampe am Fensterrand messbar weg (Gegenprobe mit 0 im Test). */
    public static final double SLOPE = NEAR_SLOPE;
    private static final double M_PER_DEG_LAT = 110_540.0;
    private static final double M_PER_DEG_LON = 111_320.0;
    private static final double MAX_LAT = 85.0511287798;
    /**
     * Vorher 256: Mit dem 30-km-Fenster las die Abfrage auf 49° Breite nur noch jedes zweite Pixel und verfehlte einen
     * Gipfel von einem Pixel Breite ganz (Test findsANarrowPeak…, gemessen 2026-10-28). 800 = jedes Pixel bis ~60°
     * Breite, je Gitterpunkt einmal (≈ 0,6 Mio. Lesezugriffe, Ergebnis gecacht).
     */
    private static final int MAX_SAMPLES_PER_AXIS = 800;

    private final ElevationTiles tiles;
    private final double radius;
    private final double slope;
    private final double full;

    /** Nahe Stufe (Zoom-10-Kacheln erwartet). */
    public TileRelief(ElevationTiles tiles) {
        this(tiles, NEAR_RADIUS_M, SLOPE, FULL_M);
    }

    /** Ferne Stufe (Zoom-7-Kacheln erwartet). */
    public static TileRelief far(ElevationTiles tiles) {
        return new TileRelief(tiles, FAR_RADIUS_M, FAR_SLOPE, 0);
    }

    TileRelief(ElevationTiles tiles, double radius, double slope, double full) {
        this.tiles = tiles;
        this.radius = radius;
        this.slope = slope;
        this.full = full;
    }

    @Override
    public double[] minMax(double lat, double lon) throws IOException {
        int zoom = tiles.zoom();
        double center = tiles.elevation(lat, lon);
        double min = center;
        double max = center;
        double cos = Math.max(Math.cos(Math.toRadians(lat)), 1e-6);
        double dLat = radius / M_PER_DEG_LAT;
        double dLon = Math.min(180, radius / (M_PER_DEG_LON * cos));
        double latN = Math.max(-MAX_LAT, Math.min(MAX_LAT, lat + dLat));
        double latS = Math.max(-MAX_LAT, Math.min(MAX_LAT, lat - dLat));
        int gy0 = (int) Math.floor(ElevationTiles.tileY(latN, zoom) * 256);
        int gy1 = (int) Math.floor(ElevationTiles.tileY(latS, zoom) * 256);
        int gx0 = (int) Math.floor(ElevationTiles.tileX(lon - dLon, zoom) * 256);
        int gx1 = (int) Math.floor(ElevationTiles.tileX(lon + dLon, zoom) * 256);
        int strideY = Math.max(1, (gy1 - gy0 + 1) / MAX_SAMPLES_PER_AXIS);
        int strideX = Math.max(1, (gx1 - gx0 + 1) / MAX_SAMPLES_PER_AXIS);
        int n = 256 << zoom;
        double r2 = radius * radius;
        for (int gy = gy0; gy <= gy1; gy += strideY) {
            int y = Math.max(0, Math.min(n - 1, gy));
            double dz = (ElevationTiles.pixelLat(y, zoom) - lat) * M_PER_DEG_LAT;
            if (Math.abs(dz) > radius) {
                continue;
            }
            int ty = y >> 8;
            int currentTx = Integer.MIN_VALUE;
            float[] tile = null;
            for (int gx = gx0; gx <= gx1; gx += strideX) {
                double dx = (ElevationTiles.pixelLon(gx, zoom) - lon) * M_PER_DEG_LON * cos;
                if (dx * dx + dz * dz > r2) {
                    continue;
                }
                int x = Math.floorMod(gx, n);
                int tx = x >> 8;
                if (tx != currentTx) {
                    // Eine Kachel je Abschnitt der Zeile holen statt je Pixel – 40 000 Tabellenzugriffe weniger.
                    tile = tiles.tile(tx, ty);
                    currentTx = tx;
                }
                double v = tile[(y & 255) * 256 + (x & 255)];
                double weaken = slope * Math.max(0, Math.sqrt(dx * dx + dz * dz) - full);
                if (v + weaken < min) {
                    min = v + weaken;
                }
                if (v - weaken > max) {
                    max = v - weaken;
                }
            }
        }
        return new double[] {min, max};
    }
}
