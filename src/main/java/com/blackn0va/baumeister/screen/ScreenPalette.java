package com.blackn0va.baumeister.screen;

import java.util.function.IntUnaryOperator;

/**
 * RGB → Kartenfarbe (Palettenbyte) mit Zwischenspeicher, und Kacheln für die einzelnen Karten.
 *
 * Die Suche der nächsten Kartenfarbe (Bukkit MapPalette.matchColor) geht über alle ~250 Farben; je Bild 100 000
 * Punkte wäre das auf dem Celeron zu teuer. Hier einmal je 15-Bit-Farbe (32 768 Einträge, 5 Bit je Kanal) gerechnet
 * und gemerkt – das sieht man auf Karten nicht, die Palette ist ohnehin grob.
 *
 * Threads: der Zwischenspeicher wird lazy gefüllt; gleichzeitige Füller schreiben dieselben Werte (harmlos). Fehler:
 * keine.
 */
public final class ScreenPalette {

    /** Kantenlänge einer Karte in Punkten. */
    public static final int TILE = 128;

    private final IntUnaryOperator match;
    private final byte[] cache = new byte[1 << 15];
    private final boolean[] known = new boolean[1 << 15];

    /** match: RGB (0xRRGGBB) → Palettenbyte (z. B. MapPalette.matchColor). */
    public ScreenPalette(IntUnaryOperator match) {
        this.match = match;
    }

    public byte index(int rgb) {
        int k = (rgb >> 9 & 0x7C00) | (rgb >> 6 & 0x03E0) | (rgb >> 3 & 0x001F);
        if (!known[k]) {
            // Mitte des 5-Bit-Fachs, nicht die untere Kante – sonst wird alles etwas dunkler.
            int r = (k >> 10 & 0x1F) << 3 | 4;
            int g = (k >> 5 & 0x1F) << 3 | 4;
            int b = (k & 0x1F) << 3 | 4;
            cache[k] = (byte) match.applyAsInt(r << 16 | g << 8 | b);
            known[k] = true;
        }
        return cache[k];
    }

    /** Ganzes Bild in Palettenbytes. */
    public byte[] convert(int[] rgb) {
        byte[] out = new byte[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            out[i] = index(rgb[i]);
        }
        return out;
    }

    /** Kachel (tx, ty) eines Bildes der Breite fullWidth (in Karten: fullWidth / TILE breit). */
    public static byte[] tile(byte[] full, int fullWidth, int tx, int ty) {
        byte[] out = new byte[TILE * TILE];
        for (int y = 0; y < TILE; y++) {
            System.arraycopy(full, (ty * TILE + y) * fullWidth + tx * TILE, out, y * TILE, TILE);
        }
        return out;
    }
}
