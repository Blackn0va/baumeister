package com.blackn0va.baumeister.minimap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MinimapImageTest {

    private static final byte BG = 0;

    /** Welt aus Kacheln: jede Spalte bekommt Grundfarbe color(x, z) und Höhe height(x, z). */
    private static final class World implements MinimapImage.TileSource {
        final Map<Long, MinimapImage.Tile> tiles = new HashMap<>();

        World fill(int cx0, int cz0, int cx1, int cz1, java.util.function.IntBinaryOperator color,
                java.util.function.IntBinaryOperator height) {
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    byte[] base = new byte[256];
                    short[] h = new short[256];
                    for (int i = 0; i < 256; i++) {
                        int x = cx * 16 + i % 16;
                        int z = cz * 16 + i / 16;
                        base[i] = (byte) color.applyAsInt(x, z);
                        h[i] = (short) height.applyAsInt(x, z);
                    }
                    tiles.put(MinimapImage.chunkKey(cx, cz), new MinimapImage.Tile(base, h));
                }
            }
            return this;
        }

        @Override
        public MinimapImage.Tile tile(int cx, int cz) {
            return tiles.get(MinimapImage.chunkKey(cx, cz));
        }
    }

    private static int at(byte[] img, int px, int py) {
        return img[py * MinimapImage.SIZE + px] & 0xFF;
    }

    @Test
    void chunkKeyRoundTripsNegativeCoordinates() {
        for (int[] c : new int[][] {{0, 0}, {-1, -1}, {123456, -98765}, {-4096, 4095}}) {
            long k = MinimapImage.chunkKey(c[0], c[1]);
            assertEquals(c[0], MinimapImage.chunkKeyX(k));
            assertEquals(c[1], MinimapImage.chunkKeyZ(k));
        }
    }

    @Test
    void centerPixelShowsTheBlockUnderThePlayerNorthUp() {
        // Grundfarbe nach Lage: x < 100 → 1, sonst 2; flach (Helligkeit normal = 1).
        World w = new World().fill(0, 0, 15, 15, (x, z) -> x < 100 ? 1 : 2, (x, z) -> 64);
        byte[] img = MinimapImage.compose(w, 100, 120, 1, BG, new HashSet<>());
        assertEquals(2 * 4 + 1, at(img, 64, 64), "Mitte = Block 100/120");
        assertEquals(1 * 4 + 1, at(img, 63, 64), "ein Pixel links = x 99");
        // Norden oben: Ein Bild mit Farbe nach z zeigt kleinere z weiter oben.
        World byZ = new World().fill(0, 0, 15, 15, (x, z) -> z < 120 ? 3 : 4, (x, z) -> 64);
        byte[] img2 = MinimapImage.compose(byZ, 100, 120, 1, BG, new HashSet<>());
        assertEquals(3 * 4 + 1, at(img2, 64, 63));
        assertEquals(4 * 4 + 1, at(img2, 64, 64));
    }

    @Test
    void shadingFollowsHeightAgainstTheNorthernNeighbourLikeVanillaMaps() {
        // Höhe steigt nach Süden bis z 120, ab da fällt sie; dazwischen eine flache Zeile.
        World w = new World().fill(0, 0, 15, 15, (x, z) -> 5, (x, z) -> z <= 118 ? z : z == 119 ? 118 : 100);
        byte[] img = MinimapImage.compose(w, 100, 110, 1, BG, new HashSet<>());
        assertEquals(5 * 4 + 2, at(img, 64, 64), "höher als nördlich = hell");
        int flat = MinimapImage.pixel(119, 110, 1);
        assertEquals(5 * 4 + 1, at(img, 64, flat), "gleich hoch = normal");
        assertEquals(5 * 4 + 0, at(img, 64, flat + 1), "tiefer = dunkel");
    }

    @Test
    void missingChunksAreReportedAndDrawnAsBackground() {
        // Nur Chunks 0..3 vorhanden: Das Bild um 32/32 reicht bis −32 und fragt nach den fehlenden.
        World w = new World().fill(0, 0, 3, 3, (x, z) -> 7, (x, z) -> 64);
        Set<Long> missing = new HashSet<>();
        byte[] img = MinimapImage.compose(w, 32, 32, 1, BG, missing);
        assertEquals(BG, img[0], "Ecke links oben liegt bei −32/−32");
        assertTrue(missing.contains(MinimapImage.chunkKey(-2, -2)));
        assertTrue(!missing.contains(MinimapImage.chunkKey(0, 0)));
        assertEquals(7 * 4 + 1, at(img, 64, 64));
    }

    @Test
    void transparentBlocksShowTheBackground() {
        World w = new World().fill(0, 0, 15, 15, (x, z) -> MinimapImage.NONE, (x, z) -> 64);
        byte[] img = MinimapImage.compose(w, 100, 100, 1, (byte) 99, new HashSet<>());
        assertEquals(99, at(img, 64, 64));
    }

    @Test
    void zoomCoversMoreBlocksPerPixel() {
        World w = new World().fill(0, 0, 31, 31, (x, z) -> x >= 260 ? 9 : 8, (x, z) -> 64);
        byte[] img = MinimapImage.compose(w, 256, 256, 4, BG, new HashSet<>());
        assertEquals(8 * 4 + 1, at(img, 64, 64), "Pixel 64 = x 256");
        assertEquals(9 * 4 + 1, at(img, 65, 64), "Pixel 65 = x 260 bei 4 Blöcken je Pixel");
        assertEquals(MinimapImage.pixel(260, 256, 4), 65);
    }

    @Test
    void zoomingInSpreadsOneBlockOverSeveralPixels() {
        World w = new World().fill(0, 0, 15, 15, (x, z) -> x == 100 ? 6 : 5, (x, z) -> 64);
        byte[] img = MinimapImage.compose(w, 100, 100, 0.5, BG, new HashSet<>());
        assertEquals(6 * 4 + 1, at(img, 64, 64), "Block 100 = Pixel 64 und 65");
        assertEquals(6 * 4 + 1, at(img, 65, 64));
        assertEquals(5 * 4 + 1, at(img, 63, 64));
        assertEquals(5 * 4 + 1, at(img, 66, 64));
    }

    /**
     * Marker (pixel) und Bild (offset) müssen dieselbe Rechnung haben: Bis 1 Block je Punkt zeigt das Pixel eines Blocks
     * genau diesen Block, darüber liegt der Block in der Fläche seines Pixels. Der erste Entwurf tastete das Bild am
     * Pixelanfang ab, die Marker an der Blockmitte – bei 0,8 stand ein Marker dann neben seinem Block.
     */
    @Test
    void markerPixelsAndImagePixelsAgreeAtEveryZoom() {
        for (double b : new double[] {0.25, 0.3, 0.512, 0.64, 0.8, 1.0}) {
            for (int d = -300; d <= 300; d++) {
                assertEquals(d, MinimapImage.offset(MinimapImage.pixel(d, 0, b) - 0, b), "Zoom " + b + " Block " + d);
            }
        }
        for (double b : new double[] {1.25, 1.5625, 2.0, 3.05, 4.0}) {
            for (int d = -300; d <= 300; d++) {
                int shown = MinimapImage.offset(MinimapImage.pixel(d, 0, b), b);
                assertTrue(Math.abs(shown - d) < b, "Zoom " + b + " Block " + d + " zeigt " + shown);
            }
        }
    }

    @Test
    void outlineDrawsThePlotBorderAndClipsAtTheEdge() {
        byte[] img = new byte[MinimapImage.SIZE * MinimapImage.SIZE];
        // Plot 90..109 × 95..104 um Mitte 100/100: Rand bei Pixel 54/73 (x) und 59/68 (z).
        MinimapImage.outline(img, 100, 100, 1, 90, 95, 109, 104, (byte) 50);
        assertEquals(50, at(img, 54, 59));
        assertEquals(50, at(img, 73, 68));
        assertEquals(50, at(img, 60, 59), "Oberkante");
        assertEquals(0, at(img, 60, 60), "innen frei");
        // Teilweise außerhalb: nur der sichtbare Teil, keine Ausnahme.
        byte[] img2 = new byte[MinimapImage.SIZE * MinimapImage.SIZE];
        MinimapImage.outline(img2, 100, 100, 1, 0, 0, 120, 120, (byte) 50);
        assertEquals(50, at(img2, 84, 64), "rechter Rand x 120 bei Pixel 84");
        assertEquals(0, at(img2, 64, 64));
    }
}
