package com.blackn0va.baumeister.minimap;

import java.util.Set;

/**
 * Bild der Minikarte (128 × 128 Kartenpixel) aus Chunk-Kacheln, ohne Bukkit – damit sich Zusammensetzen, Schattierung
 * und Umrisse ohne Server prüfen lassen (MinimapImageTest). Die Kacheln liest der Dienst im Spiel (Minimap).
 *
 * Palettenbyte wie bei Vanilla-Karten: Grundfarbe × 4 + Helligkeit (0 dunkel, 1 normal, 2 hell, 3 sehr dunkel).
 * Die Helligkeit kommt aus dem Höhenvergleich mit dem Pixel nördlich davon – höher hell, tiefer dunkel, gleich normal.
 * So zeigt die Karte Dächer, Stufen und Hänge wie eine Vanilla-Karte; ohne Schattierung sähe jede Stadt flach aus.
 *
 * Threads: reine Rechnung, beliebig. Fehler: keine – fehlende Kacheln werden zum Hintergrund und gemeldet.
 */
public final class MinimapImage {

    public static final int SIZE = 128;

    /** Grundfarbe 0 = nichts (Vanilla „NONE“, durchsichtig) – dort zeigt die Karte den Hintergrund. */
    public static final byte NONE = 0;

    private MinimapImage() {
    }

    /** 16 × 16 Spalten eines Chunks: Grundfarbe (Palettenbyte / 4) und Höhe der Oberfläche, Index z * 16 + x. */
    public record Tile(byte[] base, short[] height) {

        public Tile {
            if (base.length != 256 || height.length != 256) {
                throw new IllegalArgumentException("Kachel braucht 256 Spalten");
            }
        }
    }

    /** Liefert die Kachel eines Chunks oder null, wenn sie (noch) fehlt. */
    @FunctionalInterface
    public interface TileSource {
        Tile tile(int chunkX, int chunkZ);
    }

    /** Grobe Stichprobe für Block (x, z), gepackt wie OverviewStore.pack, oder 0 = unbekannt. */
    @FunctionalInterface
    public interface CoarseSource {
        int sample(int x, int z);
    }

    private static final CoarseSource NO_COARSE = (x, z) -> 0;

    /** Schlüssel eines Chunks wie Bukkit (Chunk#getChunkKey): x in den unteren, z in den oberen 32 Bit. */
    public static long chunkKey(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | (long) chunkZ << 32;
    }

    public static int chunkKeyX(long key) {
        return (int) key;
    }

    public static int chunkKeyZ(long key) {
        return (int) (key >> 32);
    }

    /**
     * Was die Karte zeigt: Mitte (Weltkoordinaten, auch zwischen Blöcken), Blöcke je Pixel und die Richtung „rechts“ im
     * Bild als Einheitsvektor in der Welt; „unten“ steht senkrecht dazu. Norden oben = rechts (1, 0).
     *
     * Gedreht (Wunsch 2026-10-31) zeigt oben die
     * Blickrichtung. Bild, Rahmen und Marker rechnen alle über diese eine Ansicht – zwei getrennte Rechnungen liefen beim
     * stufenlosen Zoom schon einmal einen Block auseinander (siehe pixel()).
     */
    public record View(double centerX, double centerZ, double blocksPerPixel, double rightX, double rightZ) {

        public View {
            if (!(blocksPerPixel > 0)) {
                throw new IllegalArgumentException("blocksPerPixel " + blocksPerPixel);
            }
        }

        /** Norden oben, Mitte an der Westnordwest-Ecke des Blocks (cx, cz) – wie die Karte vor der Drehung. */
        public static View northUp(int centerX, int centerZ, double blocksPerPixel) {
            return new View(centerX, centerZ, blocksPerPixel, 1, 0);
        }

        /** Blickrichtung oben; yaw wie Minecraft (0 = Süden, 90 = Westen, 180 = Norden, 270 = Osten). */
        public static View headingUp(double centerX, double centerZ, double blocksPerPixel, float yaw) {
            double r = Math.toRadians(yaw);
            double fx = -Math.sin(r);
            double fz = Math.cos(r);
            // rechts von der Blickrichtung (von oben gesehen im Uhrzeigersinn): Blick nach Norden → rechts Osten.
            return new View(centerX, centerZ, blocksPerPixel, -fz, fx);
        }

        public double downX() {
            return -rightZ;
        }

        public double downZ() {
            return rightX;
        }

        /** Block unter der Mitte von Pixel (px, py). */
        public int worldX(int px, int py) {
            double u = px - SIZE / 2 + 0.5;
            double v = py - SIZE / 2 + 0.5;
            return (int) Math.floor(centerX + (u * rightX + v * downX()) * blocksPerPixel);
        }

        public int worldZ(int px, int py) {
            double u = px - SIZE / 2 + 0.5;
            double v = py - SIZE / 2 + 0.5;
            return (int) Math.floor(centerZ + (u * rightZ + v * downZ()) * blocksPerPixel);
        }

        /** Bildposition (Pixel, mit Nachkommastellen; 64 = Mitte) eines Weltpunkts. */
        public double screenX(double x, double z) {
            return ((x - centerX) * rightX + (z - centerZ) * rightZ) / blocksPerPixel + SIZE / 2;
        }

        public double screenY(double x, double z) {
            return ((x - centerX) * downX() + (z - centerZ) * downZ()) / blocksPerPixel + SIZE / 2;
        }

        /** Halbe Bilddiagonale in Blöcken – so weit reicht das Bild in jeder Drehung. */
        public int reach() {
            return (int) Math.ceil(SIZE / 2.0 * Math.sqrt(2) * blocksPerPixel) + 1;
        }
    }

    /** Grundfarbe für Straßen (Vanilla SNOW, weiß) – siehe ROAD. */
    public static final int ROAD_BASE = 8;
    /**
     * Bit in der Grundfarbe: Diese Spalte ist Straße (Straßenblock auf Bodenhöhe, entschieden beim Lesen). Die Karte
     * malt sie weiß statt im Grau des Betons – Wunsch 2026-10-31; grauer Beton sah aus wie
     * graue Dächer.
     */
    public static final int ROAD = 0x80;

    /**
     * Setzt das Bild um (centerX, centerZ) zusammen: Pixel (64, 64) ist die Mitte, je Pixel blocksPerPixel Blöcke,
     * Norden oben. Fehlende Kacheln landen in missing (Chunk-Schlüssel), ihre Pixel bekommen background.
     */
    public static byte[] compose(TileSource tiles, int centerX, int centerZ, double blocksPerPixel, byte background,
            Set<Long> missing) {
        return compose(tiles, NO_COARSE, View.northUp(centerX, centerZ, blocksPerPixel), background, missing);
    }

    public static byte[] compose(TileSource tiles, CoarseSource coarse, int centerX, int centerZ,
            double blocksPerPixel, byte background, Set<Long> missing) {
        return compose(tiles, coarse, View.northUp(centerX, centerZ, blocksPerPixel), background, missing);
    }

    /**
     * Bild in einer beliebigen Ansicht. Fehlt die volle Kachel, nimmt das Pixel die grobe Stichprobe (OverviewStore) –
     * so zeigt die weit herausgezoomte Karte auch Gegenden, deren Chunks gerade nicht geladen sind. missing nennt nur
     * fehlende volle Kacheln. Schattiert wird gegen das Pixel darüber im Bild (Licht von oben, auch gedreht).
     */
    public static byte[] compose(TileSource tiles, CoarseSource coarse, View view, byte background, Set<Long> missing) {
        byte[] out = new byte[SIZE * SIZE];
        int[] northHeight = new int[SIZE];
        boolean[] northKnown = new boolean[SIZE];
        for (int px = 0; px < SIZE; px++) {
            int wx = view.worldX(px, -1);
            int wz = view.worldZ(px, -1);
            Tile t = tileAt(tiles, wx, wz, null);
            if (t != null) {
                northHeight[px] = t.height()[column(wx, wz)];
                northKnown[px] = true;
            } else {
                int v = coarse.sample(wx, wz);
                if (OverviewStore.present(v)) {
                    northHeight[px] = OverviewStore.height(v);
                    northKnown[px] = true;
                }
            }
        }
        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                int wx = view.worldX(px, py);
                int wz = view.worldZ(px, py);
                Tile t = tileAt(tiles, wx, wz, missing);
                int i = py * SIZE + px;
                int base;
                int h;
                if (t != null) {
                    int c = column(wx, wz);
                    base = t.base()[c] & 0xFF;
                    h = t.height()[c];
                } else {
                    int v = coarse.sample(wx, wz);
                    if (!OverviewStore.present(v)) {
                        out[i] = background;
                        northKnown[px] = false;
                        continue;
                    }
                    base = OverviewStore.base(v);
                    h = OverviewStore.height(v);
                }
                int shade = !northKnown[px] || h == northHeight[px] ? 1 : h > northHeight[px] ? 2 : 0;
                if ((base & ROAD) != 0) {
                    out[i] = (byte) (ROAD_BASE * 4 + 2);  // Straße: hell und ohne Schattierung, damit sie durchgeht
                } else {
                    out[i] = base == NONE ? background : (byte) (base * 4 + shade);
                }
                northHeight[px] = h;
                northKnown[px] = true;
            }
        }
        return out;
    }

    /**
     * Rechteck in Weltkoordinaten als Rahmen ins Bild (z. B. Plotgrenze). Teile außerhalb des Bilds fallen weg; liegt
     * das Rechteck ganz außerhalb, bleibt das Bild unverändert.
     */
    public static void outline(byte[] img, int centerX, int centerZ, double blocksPerPixel, int minX, int minZ,
            int maxX, int maxZ, byte color) {
        outline(img, View.northUp(centerX, centerZ, blocksPerPixel), minX, minZ, maxX, maxZ, color);
    }

    /** Rahmen in einer beliebigen Ansicht: Linien durch die Mitten der Randblöcke, gedreht wie das Bild. */
    public static void outline(byte[] img, View view, int minX, int minZ, int maxX, int maxZ, byte color) {
        double[][] c = {{minX + 0.5, minZ + 0.5}, {maxX + 0.5, minZ + 0.5}, {maxX + 0.5, maxZ + 0.5},
            {minX + 0.5, maxZ + 0.5}};
        for (int k = 0; k < 4; k++) {
            double[] a = c[k];
            double[] b = c[(k + 1) % 4];
            line(img, (int) Math.floor(view.screenX(a[0], a[1])), (int) Math.floor(view.screenY(a[0], a[1])),
                    (int) Math.floor(view.screenX(b[0], b[1])), (int) Math.floor(view.screenY(b[0], b[1])), color);
        }
    }

    /** Bresenham; außerhalb liegende Punkte fallen weg (set prüft). Lange Linien werden auf das Bild geklemmt. */
    static void line(byte[] img, int x0, int y0, int x1, int y1, byte color) {
        // Ganz draußen und auf derselben Seite: nichts zu zeichnen (spart die Schleife bei weit entfernten Plots).
        if (x0 < 0 && x1 < 0 || y0 < 0 && y1 < 0 || x0 >= SIZE && x1 >= SIZE || y0 >= SIZE && y1 >= SIZE) {
            return;
        }
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        if (dx > 4 * SIZE || -dy > 4 * SIZE) {
            return;  // Plot um ein Vielfaches größer als das Bild: Rand liegt ohnehin weit draußen
        }
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0;
        int y = y0;
        while (true) {
            set(img, x, y, color);
            if (x == x1 && y == y1) {
                return;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
    }

    /**
     * Unbekannte Pixel (background) als feines dunkles Schachbrett. Vorher blieben sie durchsichtig: Dort schien das
     * beige Kartenpapier durch, das genauso aussieht wie der Rahmen – der Rahmen wirkte an einer Ecke dicker, das Bild
     * verschoben (Wunsch). Jetzt füllt das Bild immer
     * das ganze Quadrat, und man sieht, wo noch nichts bekannt ist.
     */
    public static void fillUnknown(byte[] img, byte background, byte dark, byte light) {
        for (int i = 0; i < img.length; i++) {
            if (img[i] == background) {
                int x = i % SIZE;
                int z = i / SIZE;
                img[i] = ((x >> 2) + (z >> 2) & 1) == 0 ? dark : light;
            }
        }
    }

    /**
     * Kartenpixel einer Weltkoordinate (kann außerhalb 0..127 liegen): das Pixel, dessen Fläche die Blockmitte enthält.
     * Gegenstück zu offset() – beide rechnen mit Mitten. Mit Anfängen (Pixel zeigt den Block an seinem linken Rand)
     * lag ein Marker bei 0,8 Blöcken je Pixel einen Block neben dem Block, den das Pixel zeigt (nachgerechnet
     * 2026-10-31); bei ganzzahligem Zoom fällt das nicht auf, beim stufenlosen schon.
     */
    public static int pixel(int world, int center, double blocksPerPixel) {
        return (int) Math.floor((world - center + 0.5) / blocksPerPixel) + SIZE / 2;
    }

    /** Blockversatz zur Mitte für Pixel p: der Block unter der Pixelmitte. */
    static int offset(int p, double blocksPerPixel) {
        return (int) Math.floor((p - SIZE / 2 + 0.5) * blocksPerPixel);
    }

    private static void set(byte[] img, int x, int z, byte color) {
        if (x >= 0 && x < SIZE && z >= 0 && z < SIZE) {
            img[z * SIZE + x] = color;
        }
    }

    private static Tile tileAt(TileSource tiles, int wx, int wz, Set<Long> missing) {
        int cx = wx >> 4;
        int cz = wz >> 4;
        Tile t = tiles.tile(cx, cz);
        if (t == null && missing != null) {
            missing.add(chunkKey(cx, cz));
        }
        return t;
    }

    private static int column(int wx, int wz) {
        return (wz & 15) * 16 + (wx & 15);
    }
}
