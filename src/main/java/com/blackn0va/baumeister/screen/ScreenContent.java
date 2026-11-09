package com.blackn0va.baumeister.screen;

/**
 * Was der Bildschirm zeigt – als RGB-Bild je Zeitpunkt, ohne Bukkit (testbar).
 *
 * Wunsch 2026-11-07 – Vera baute die 7-Segment-Anzeige (12 595 Blöcke) und der
 * Nutzer machte sie rückgängig. Ein Minecraft-Bildschirm aus Karten in Rahmen kann wirklich Bilder bewegen.
 *
 * Bildrate: Jede Änderung geht als Kartendaten an jeden, der hinsieht – bei extern verbundenen Spielern über deren
 * Leitung. Deshalb höchstens FPS (Inhalt) und zusammen höchstens MAP_FRAMES_PER_SECOND Karten-Bilder je Sekunde
 * (fps(kind, maps)): 3 × 2 Karten → 5 Bilder/s, 6 × 4 → gut 1 Bild/s. Uhr und Laufschrift ändern wenige Punkte, das
 * schicken die Karten nur als geänderten Ausschnitt.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class ScreenContent {

    public enum Kind {
        ANIMATION, UHR, LAUFSCHRIFT, BILD
    }

    /** Höchste Bildrate je Inhalt. */
    static final int FPS_MAX = 5;
    /** Karten-Bilder je Sekunde, die ein Bildschirm insgesamt schicken darf (Leitung der Zuschauer). */
    public static final int MAP_FRAMES_PER_SECOND = 30;

    public static final int BLACK = 0x000000;
    static final int YELLOW = 0xFFD84A;
    static final int CYAN = 0x4AE3FF;
    static final int WHITE = 0xFFFFFF;
    static final int VIOLET = 0x8B5CF6;

    private ScreenContent() {
    }

    /** Bilder je Sekunde für kind auf einem Bildschirm aus maps Karten (mindestens 1). */
    public static int fps(Kind kind, int maps) {
        int content = switch (kind) {
            case UHR -> 1;
            case LAUFSCHRIFT, ANIMATION, BILD -> FPS_MAX;
        };
        return Math.max(1, Math.min(content, MAP_FRAMES_PER_SECOND / Math.max(1, maps)));
    }

    /** Uhr: große Ziffern HH:MM mittig, darunter klein die zweite Zeile (Datum, Spielzeit). */
    public static int[] clock(int w, int h, String time, String below) {
        int[] rgb = fill(w, h, BLACK);
        int scale = Math.max(1, Math.min((w - 8) / Math.max(1, PixelFont.width(time, 1)), (h * 2 / 3) / PixelFont.H));
        int tw = PixelFont.width(time, scale);
        int th = PixelFont.H * scale;
        int small = Math.max(1, scale / 3);
        int y = (h - th - (below.isEmpty() ? 0 : PixelFont.H * small + small * 3)) / 2;
        PixelFont.draw(rgb, w, h, time, (w - tw) / 2, y, scale, YELLOW);
        if (!below.isEmpty()) {
            PixelFont.draw(rgb, w, h, below, (w - PixelFont.width(below, small)) / 2, y + th + small * 3, small, CYAN);
        }
        return rgb;
    }

    /** Länge eines Laufschrift-Durchlaufs in Bildpunkten (Text plus Bildschirmbreite). */
    public static int tickerPeriod(int w, String text, int scale) {
        return PixelFont.width(text, scale) + w;
    }

    /** Laufschrift: Text läuft von rechts nach links, offset in Bildpunkten seit Beginn. */
    public static int[] ticker(int w, int h, String text, int offset, int scale) {
        int[] rgb = fill(w, h, BLACK);
        int period = tickerPeriod(w, text, scale);
        int x = w - Math.floorMod(offset, period);
        PixelFont.draw(rgb, w, h, text, x, (h - PixelFont.H * scale) / 2, scale, WHITE);
        // Schmale Leisten oben und unten in der Akzentfarbe: wirkt wie ein Laufband, nicht wie ein Loch.
        for (int i = 0; i < w; i++) {
            rgb[i] = VIOLET;
            rgb[(h - 1) * w + i] = VIOLET;
        }
        return rgb;
    }

    /** Schriftgröße der Laufschrift: so groß, dass sie gut die Hälfte der Höhe füllt. */
    public static int tickerScale(int h) {
        return Math.max(1, h / 2 / PixelFont.H);
    }

    /**
     * Animation: Plasma (farbige Wellen) mit wanderndem Schriftzug. t = Bildnummer. Klassisches Plasma aus Sinus-
     * Summen; Farben über drei verschobene Sinus-Bänder.
     */
    public static int[] animation(int w, int h, int t, String title) {
        int[] rgb = new int[w * h];
        double time = t * 0.18;
        for (int y = 0; y < h; y++) {
            double fy = y / 24.0;
            for (int x = 0; x < w; x++) {
                double fx = x / 24.0;
                double v = Math.sin(fx + time) + Math.sin((fy + time) / 2.0) + Math.sin((fx + fy + time) / 2.0)
                        + Math.sin(Math.sqrt(fx * fx + fy * fy) + time);
                int r = (int) (128 + 127 * Math.sin(v * Math.PI));
                int g = (int) (128 + 127 * Math.sin(v * Math.PI + 2.094));
                int b = (int) (128 + 127 * Math.sin(v * Math.PI + 4.188));
                rgb[y * w + x] = r << 16 | g << 8 | b;
            }
        }
        if (title != null && !title.isBlank()) {
            int scale = Math.max(1, Math.min(w / Math.max(1, PixelFont.width(title, 1) + 4), h / 4 / PixelFont.H));
            int tw = PixelFont.width(title, scale);
            int range = Math.max(1, h - PixelFont.H * scale - 4);
            // Schriftzug hüpft sanft auf und ab, Schatten für Lesbarkeit auf bunter Fläche.
            int y = 2 + (int) ((Math.sin(t * 0.25) + 1) / 2 * range);
            int x = (w - tw) / 2;
            PixelFont.draw(rgb, w, h, title, x + scale, y + scale, scale, BLACK);
            PixelFont.draw(rgb, w, h, title, x, y, scale, WHITE);
        }
        return rgb;
    }

    static int[] fill(int w, int h, int color) {
        int[] rgb = new int[w * h];
        java.util.Arrays.fill(rgb, color);
        return rgb;
    }
}
