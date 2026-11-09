package com.blackn0va.baumeister.earth;

import com.blackn0va.baumeister.terrain.DemGenerator;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import org.bukkit.Material;

/**
 * Gelände der Erdwelt an jeder Blockstelle: Projektion rückwärts → lat/lon → Höhe (Zoom 15) → Bezug (HeightModel) →
 * Oberkante. Dieselbe Rechnung für den Weltgenerator (paint) und für alle, die die Oberfläche ohne Weltzugriff
 * brauchen (surfaceY, /bm reise, /bm wo) – sonst stünde ein Spieler nach dem Teleport in der Luft oder im Berg.
 *
 * Lücken im Netz (toGeo null) und alles hinter dem Netz sind Meer: Meeresboden auf −seaDepthMax, Wasser bis y 0.
 *
 * Fehlende Daten: Fehlt eine Kachel und lässt sie sich nicht laden, gilt die grobe Höhe (Zoom 10), fehlt auch die,
 * 0 m; fehlt das Relief, shift 0. Das Ergebnis trägt dann estimated = true (/bm reise bricht dann ab). Ein Chunk entsteht
 * nur einmal – lieber grob als ein Loch aus Luft; EarthRepaint malt ihn neu, sobald die Daten da sind.
 *
 * Wasser (WaterTable, optional): Senken im Landesinneren bleiben trocken, Seen unter 0 m bekommen eine Wasserfläche
 * auf eigener Höhe, Meer ohne Tiefendaten (genau 0 m) eine Wasserfläche auf 0. Die Oberkante (top) bleibt dabei exakt
 * dieselbe wie ohne WaterTable – nur das Wasser darüber ändert sich.
 * Grenze, bewusst: Das gilt nur für neue Chunks. Chunks, die vor der Wasserkarte entstanden (Senken bis 0 gefüllt,
 * Mittelmeer als Sand auf y 0), bleiben so; grenzt so ein alter Chunk an einen neuen trockenen Polder, steht altes
 * Wasser seitlich an Luft und läuft beim ersten Blockupdate hinein – der Uferschutz kennt nur die eigene Rechnung,
 * nicht die alten Blöcke. Auf dev01 ist die Erdwelt praktisch unerkundet (Stand 2026-10-29).
 *
 * Threads: zustandslos bis auf die threadsicheren Datenquellen; paint läuft auf den Weltgenerator-Threads.
 */
public final class EarthTerrain implements HeightField {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    public static final int DEFAULT_MIN_Y = -2032;
    /** ausschließlich, wie World.getMaxHeight(). */
    public static final int DEFAULT_MAX_Y = 2032;
    private static final long WARN_EVERY_NANOS = 60_000_000_000L;

    /**
     * Eine Säule.
     *
     * @param realElevation Meereshöhe laut Daten (m), auch unter seaDepthMax
     * @param elevation     Meereshöhe mit angehobenem Meeresboden (m)
     * @param top           Oberkante (Blockkoordinate des obersten festen Blocks)
     * @param seaY          Wasserspiegel (Blockkoordinate); Wasser nur, wo top darunter liegt
     * @param gap           Lücke im Netz (lat/lon sind dann NaN)
     * @param estimated     Höhe oder Bezug geschätzt, weil Daten fehlten
     * @param clippedTop    Gipfel an der Decke gekappt
     * @param clippedBottom Talgrund am Boden angehoben
     * @param surfaceWater  Wasserfläche auf eigener Höhe seaY statt Meer (WaterTable SURFACE: seaY = top, der
     *                      oberste Block ist Wasser; LAKE: Wasser von top bis zur Seefläche seaY)
     * @param dryBasin      Oberkante unter dem Meeresspiegel, aber trocken (Senke, Polder)
     * @param lake          Wasser eines geschlossenen Beckens (See) – der Uferschutz trennt es von tieferem Wasser
     */
    public record Column(double lat, double lon, double realElevation, double elevation, double shift, int top,
            int seaY, boolean gap, boolean estimated, boolean clippedTop, boolean clippedBottom, boolean surfaceWater,
            boolean dryBasin, boolean lake) {

        /** Ohne Seekennung (Meer, Meer ohne Tiefendaten auf 0). */
        public Column(double lat, double lon, double realElevation, double elevation, double shift, int top,
                int seaY, boolean gap, boolean estimated, boolean clippedTop, boolean clippedBottom,
                boolean surfaceWater, boolean dryBasin) {
            this(lat, lon, realElevation, elevation, shift, top, seaY, gap, estimated, clippedTop, clippedBottom,
                    surfaceWater, dryBasin, false);
        }

        /** Ohne Wasserkarte (Tests, alter Stand): Wasser nur unter dem Meeresspiegel. */
        public Column(double lat, double lon, double realElevation, double elevation, double shift, int top,
                int seaY, boolean gap, boolean estimated, boolean clippedTop, boolean clippedBottom) {
            this(lat, lon, realElevation, elevation, shift, top, seaY, gap, estimated, clippedTop, clippedBottom,
                    false, false);
        }

        /** Liegt die Oberkante unter Wasser (oder ist sie selbst Wasser)? */
        public boolean underwater() {
            return top < seaY || surfaceWater;
        }

        /** Höchster Wasserblock oder Integer.MIN_VALUE ohne Wasser. */
        int waterTop() {
            if (surfaceWater) {
                return Math.max(top, seaY);
            }
            return top < seaY ? seaY : Integer.MIN_VALUE;
        }

        /** Der oberste Block selbst ist Wasser (Wasserfläche genau auf der Oberkante). */
        boolean waterOnTop() {
            return surfaceWater && seaY <= top;
        }
    }

    private final EarthProjection projection;
    private final Elevation fine;
    private final Elevation coarse;
    private final HeightModel heights;
    private final int bedrockDepth;
    private final int minY;
    private final int maxY;
    /** null = altes Verhalten: jede Säule unter dem Meeresspiegel ist Meer. */
    private final WaterTable water;
    private final AtomicLong lastWarn = new AtomicLong(System.nanoTime() - WARN_EVERY_NANOS);

    /**
     * @param fine         Höhe für die Oberfläche (Zoom 15)
     * @param coarse       Ersatz, wenn fine fehlt (Zoom 10)
     * @param bedrockDepth Grundgestein so viele Blöcke unter der tiefsten Nachbaroberkante, darunter Luft
     * @param maxY         ausschließlich
     */
    public EarthTerrain(EarthProjection projection, Elevation fine, Elevation coarse, HeightModel heights,
            int bedrockDepth, int minY, int maxY) {
        this(projection, fine, coarse, heights, bedrockDepth, minY, maxY, null);
    }

    /** @param water Wasser je Säule (Senken, Seen) oder null für „unter dem Meeresspiegel ist Meer“ */
    public EarthTerrain(EarthProjection projection, Elevation fine, Elevation coarse, HeightModel heights,
            int bedrockDepth, int minY, int maxY, WaterTable water) {
        this.water = water;
        this.projection = projection;
        this.fine = fine;
        this.coarse = coarse;
        this.heights = heights;
        this.bedrockDepth = bedrockDepth;
        this.minY = minY;
        this.maxY = maxY;
    }

    public EarthProjection projection() {
        return projection;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    /** Dieselbe Erde in einer Welt anderer Höhe. */
    public EarthTerrain withRange(int newMinY, int newMaxY) {
        return new EarthTerrain(projection, fine, coarse, heights, bedrockDepth, newMinY, newMaxY, water);
    }

    /**
     * Oberkante ohne Wasser. Bewusst auch dort, wo der oberste Block eine Wasserfläche ist (Meer auf 0, See) oder ein
     * Deich darüber steht: Weltbau und TerrainFit bauen auf dieser Höhe wie vor der Wasserkarte – ein anderer Wert
     * verschöbe jeden Gebäudefuß gegen schon gebaute Kacheln. Ein Haus auf einer Wasserfläche ersetzt deren Block.
     */
    @Override
    public int surfaceY(int x, int z) {
        return column(x + 0.5, z + 0.5).top();
    }

    /** Derselbe Meeresspiegel, bis zu dem der Generator Meer füllt (seaY vor den Seeregeln). */
    @Override
    public int seaLevelY(int x, int z) {
        return (int) Math.round(-column(x + 0.5, z + 0.5).shift());
    }

    /** Säule an einer Blockstelle (Mitte eines Blocks = ganzzahlig + 0,5). */
    public Column column(double x, double z) {
        double[] g = projection.toGeo(x, z);
        if (g == null) {
            double e = heights.seabed(Double.NEGATIVE_INFINITY);
            int top = clamp(Math.round(e));
            return new Column(Double.NaN, Double.NaN, e, e, 0, top, 0, true, false, false, false);
        }
        double lat = g[0];
        double lon = g[1];
        boolean estimated = false;
        double real;
        double[] corners = water != null ? new double[4] : null;
        try {
            real = corners != null ? fine.at(lat, lon, corners) : fine.at(lat, lon);
        } catch (IOException e) {
            estimated = true;
            corners = null;
            real = fallbackElevation(lat, lon, e);
        }
        double shift;
        try {
            shift = heights.shift(lat, lon);
        } catch (IOException e) {
            // fail-open bewusst: ohne Relief ist der Bezug 0 – Hochgebirge wird dann gekappt, aber nicht zu Luft.
            estimated = true;
            warn("relief_fehlt", lat, lon, e);
            shift = 0;
        }
        double e = heights.seabed(real);
        long raw = Math.round(e - shift);
        int top = clamp(raw);
        int seaY = (int) Math.round(-shift);
        boolean surface = false;
        boolean dry = false;
        boolean lake = false;
        if (water != null) {
            WaterTable.Water w;
            try {
                w = water.water(lat, lon, real, corners, top < seaY);
            } catch (IOException ex) {
                // fail-open bewusst: ohne grobe Kacheln wie vor der Wasserkarte (Meer bis Meereshöhe), Säule geschätzt –
                // EarthRepaint malt sie neu, sobald die Kacheln da sind.
                estimated = true;
                warn("senken_fehlen", lat, lon, ex);
                w = top < seaY ? WaterTable.Water.FILL : WaterTable.Water.NONE;
            }
            lake = w.lake();
            if (w.kind() == WaterTable.Kind.SURFACE) {
                surface = true;
                seaY = top;
            } else if (w.kind() == WaterTable.Kind.LAKE) {
                // Kaputte Pixel unter einer Seefläche: Wasser bis zur Fläche, über denselben Bezug wie die Oberkante
                // (Totes Meer: Fläche −412 m, gekappt auf −meerestiefe-max wie der Boden).
                surface = true;
                seaY = Math.max(top, clamp(Math.round(heights.seabed(w.level()) - shift)));
            } else if (w.kind() == WaterTable.Kind.NONE && top < seaY) {
                dry = true;
                seaY = top;
            }
        }
        return new Column(lat, lon, real, e, shift, top, seaY, false, estimated, raw > maxY - 2, raw < minY + 1,
                surface, dry, lake);
    }

    private double fallbackElevation(double lat, double lon, IOException cause) {
        try {
            double e = coarse.at(lat, lon);
            warn("höhendaten_grob", lat, lon, cause);
            return e;
        } catch (IOException e2) {
            // fail-open bewusst: ein Chunk entsteht nur einmal; 0 m ist besser als ein Loch aus Luft.
            warn("höhendaten_fehlen", lat, lon, e2);
            return 0;
        }
    }

    private void warn(String event, double lat, double lon, IOException e) {
        long now = System.nanoTime();
        long last = lastWarn.get();
        if (now - last >= WARN_EVERY_NANOS && lastWarn.compareAndSet(last, now)) {
            LOG.warning(() -> String.format(Locale.ROOT, "erde | %s | lat=%.5f lon=%.5f grund=%s", event,
                    lat, lon, e.getMessage()));
        }
    }

    private int clamp(long y) {
        return (int) Math.max(minY + 1, Math.min(maxY - 2, y));
    }

    /**
     * Füllt die 16 × 16 Säulen von Chunk (chunkX, chunkZ) – Blockkoordinaten der Welt, der Sink bekommt 0..15.
     * Grundgestein liegt bedrockDepth unter der tiefsten Nachbaroberkante (nicht unter der eigenen): Sonst hinge unter
     * einer Steilwand Luft zwischen Grundgestein und dem Fuß der Wand.
     */
    public void paint(int chunkX, int chunkZ, DemGenerator.Sink sink) {
        paintChecked(chunkX, chunkZ, sink);
    }

    /**
     * Wie paint, meldet aber, ob irgendeine der 18 × 18 Säulen (Chunk samt Rand) geschätzt war – dann muss der Chunk
     * neu gemalt werden, sobald die Daten da sind (EarthRepaint). Der Rand zählt mit: Grundgestein und Wandschichten
     * am Chunkrand hängen an den Nachbarsäulen.
     */
    public boolean paintChecked(int chunkX, int chunkZ, DemGenerator.Sink sink) {
        int[][] h = new int[18][18];
        double[][] elev = new double[18][18];
        Column[][] cols = new Column[18][18];
        boolean estimated = false;
        for (int dx = 0; dx < 18; dx++) {
            for (int dz = 0; dz < 18; dz++) {
                Column c = column(chunkX * 16 + dx - 1 + 0.5, chunkZ * 16 + dz - 1 + 0.5);
                cols[dx][dz] = c;
                h[dx][dz] = c.top();
                elev[dx][dz] = c.elevation();
                estimated |= c.estimated();
            }
        }
        int[][] floor = new int[16][16];
        int[][] sea = new int[16][16];
        double[][] snow = new double[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                Column c = cols[x + 1][z + 1];
                int lowest = Math.min(c.top(), DemGenerator.lowestNeighbour(h, x, z));
                floor[x][z] = Math.max(minY, lowest - bedrockDepth);
                sea[x][z] = c.seaY();
                snow[x][z] = c.gap() ? Double.MAX_VALUE : snowLine(c.lat());
            }
        }
        int[][] dike = water != null ? shore(cols) : null;
        DemGenerator.paintColumns(h, elev, floor, sea, snow, maxY, sink);
        if (dike != null) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Column c = cols[x + 1][z + 1];
                    int from = dike[x][z];
                    if (from == Integer.MIN_VALUE) {
                        if (c.waterOnTop()) {
                            sink.set(x, c.top(), z, Material.WATER);
                        }
                    } else if (!c.waterOnTop()) {
                        int level = c.waterTop();
                        for (int y = from + 1; y < level; y++) {
                            sink.set(x, y, z, Material.DIRT);
                        }
                        sink.set(x, level, z, Material.GRASS_BLOCK);
                    }
                }
            }
        }
        return estimated;
    }

    /**
     * Uferschutz (nur mit WaterTable): Grenzt eine nasse Säule an eine TROCKENE Nachbarsäule, deren Oberkante unter dem
     * Wasser liegt, stünde ein Wasserblock seitlich an Luft und liefe beim ersten Blockupdate in die Senke. Vorher kam
     * das nicht vor (jede Säule unter dem Meeresspiegel war Meer); jetzt gibt es trockene Polder, und die Daten zeigen
     * nicht jeden Deich – etwa ein Kanal auf −1 m (Meer bis 0) direkt neben einem Polder auf −4 m. Dort entsteht in
     * der nassen Säule ein Deich (Erde, oben Gras) statt des Wassers über der Polderkante; eine Wasserfläche als oberster
     * Block (See) bleibt dann einfach Land. Nur gegen die Rohwerte der Nachbarn gerechnet: beide Chunks einer Grenze
     * kommen zum selben Ergebnis, und es gibt keine Kette (der Deich ist dicht, die nächste Säule grenzt an ihn).
     *
     * Absenken statt Deich (erste Fassung) lief über Ketten: Die nächste Säule grenzte an die abgesenkte, die
     * übernächste an jene – im Test blieb ein Leck, in einer echten Welt hätte es die Nordsee bis zum Polder geleert.
     * Nasse Nachbarn mit niedrigerem Spiegel zählen, sobald eine der beiden Säulen zu einem See gehört (Column.lake):
     * Eine Pfütze auf −26 neben dem Kaspischen Meer auf −29 stünde sonst drei Blöcke seitlich an Luft, ebenso Meer
     * auf 0 neben einem See auf −29 an der Grenze zweier Zoom-7-Zellen (Prüfer-Befund 2026-10-29; vorher galt jeder
     * nasse Nachbar als dicht). Meer gegen Meer zählt weiter nicht (Stufen von einem Block bei Bezug &gt; 0): Wasser
     * über Wasser gleicht sich im Spiel von selbst aus und gab es schon vorher.
     *
     * @return je Säule die Oberkante, ab der Deich statt Wasser steht (Deich von from+1 bis zum Wasserspiegel), oder
     *         Integer.MIN_VALUE ohne Deich
     */
    static int[][] shore(Column[][] cols) {
        int[][] dike = new int[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                dike[x][z] = Integer.MIN_VALUE;
                Column c = cols[x + 1][z + 1];
                int level = c.waterTop();
                if (level == Integer.MIN_VALUE) {
                    continue;
                }
                int dry = Math.min(Math.min(sealTop(c, cols[x][z + 1]), sealTop(c, cols[x + 2][z + 1])),
                        Math.min(sealTop(c, cols[x + 1][z]), sealTop(c, cols[x + 1][z + 2])));
                if (dry < level) {
                    dike[x][z] = Math.max(c.top(), dry);
                }
            }
        }
        return dike;
    }

    /**
     * Bis wohin der Nachbar n seitlich dicht ist: trocken bis zur Oberkante, nass bis zu seinem Wasserspiegel – Meer
     * gegen Meer gilt als dicht (Integer.MAX_VALUE), siehe shore.
     */
    private static int sealTop(Column c, Column n) {
        int wet = n.waterTop();
        if (wet == Integer.MIN_VALUE) {
            return n.top();
        }
        return c.lake() || n.lake() ? wet : Integer.MAX_VALUE;
    }

    /**
     * Schneegrenze in Metern nach Breite, grob: bis 25° 5 000 m, dann linear bis 0 m bei 70° (Alpen ≈ 2 650 m, Hepberg
     * 2 360 m, Spitzbergen und Antarktis ganz). Vorher (Gelände je Ort) fest 2 800 m – auf der ganzen Erde wären
     * Grönland und die Antarktis grün gewesen.
     */
    static double snowLine(double lat) {
        double a = Math.abs(lat);
        if (a <= 25) {
            return 5000;
        }
        if (a >= 70) {
            return 0;
        }
        return 5000 * (70 - a) / 45;
    }
}
