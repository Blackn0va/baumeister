package com.blackn0va.baumeister.terrain;

import java.io.IOException;
import java.util.Random;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

/**
 * Weltgenerator aus echten Höhendaten: 1 Block = 1 Meter waagerecht, senkrecht 1:1 (oder gestaucht, wenn die Welt
 * zu niedrig ist – siehe vScale). Block (0,0) ist die Mitte des Orts; Projektion wie im Stadtbau (OsmImport), damit
 * Städte und Gelände später aufeinanderpassen.
 *
 * Warum ein Generator statt Blöcke setzen: Der Grand Canyon misst rund 30 × 10 km und ist bis 1 800 m tief – Block für
 * Block wären das über 100 Milliarden Blöcke (Jahre bei 50 000 je Sekunde). Ein Generator füllt jede Säule beim
 * ersten Laden des Chunks in einem Rutsch, so schnell wie Vanilla-Gelände.
 *
 * Zwei Wege, dieselben Säulen (paint): als ChunkGenerator einer eigenen Welt (384 Blöcke hoch, ggf. gestaucht) oder
 * über TerrainPopulator in der 4 064 Blöcke hohen Datapack-Dimension (1:1). Der zweite Weg braucht keinen eigenen
 * Generator – den kann Spigot einer Datapack-Dimension nur über bukkit.yml und load: STARTUP geben.
 *
 * Aussehen: steile Hänge (Höhenunterschied ≥ 3 zum Nachbarn) als Terrakotta-Schichten in wechselnden Farben über die
 * ganze freiliegende Wand – so sehen Canyon-Wände geschichtet aus; flach: Gras, über schneeHöhe Schnee, unter dem
 * Meeresspiegel (Höhe ≤ 0 m) Sand und Wasser. Keine Höhlen, keine Strukturen, keine Mobs.
 *
 * Threads: generateNoise/paint können parallel laufen (isParallelCapable); ElevationTiles ist dafür gebaut. Die
 * Kacheln sind vorher geladen (TerrainService); fehlt eine trotzdem, wird sie hier nachgeladen (langsam, aber korrekt).
 */
public final class DemGenerator extends ChunkGenerator {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    private static final double M_PER_DEG_LAT = 110_540.0;
    private static final double M_PER_DEG_LON = 111_320.0;
    private static final Material[] STRATA = {Material.RED_TERRACOTTA, Material.ORANGE_TERRACOTTA,
        Material.TERRACOTTA, Material.WHITE_TERRACOTTA, Material.BROWN_TERRACOTTA, Material.YELLOW_TERRACOTTA,
        Material.LIGHT_GRAY_TERRACOTTA, Material.ORANGE_TERRACOTTA};

    /** Schreibziel einer Säule: ChunkData (Generator, Chunk-Koordinaten 0..15) oder LimitedRegion (Populator). */
    public interface Sink {
        void set(int x, int y, int z, Material m);

        /** y0 einschließlich, y1 ausschließlich (wie ChunkData.setRegion). */
        void fill(int x, int y0, int z, int y1, Material m);
    }

    private final ElevationTiles tiles;
    private final double lat0;
    private final double lon0;
    private final double cos0;
    private final double refElev;
    private final double vScale;
    private final int baseY;
    private final double snowElev;
    private final int radius;
    private volatile boolean warned;

    /**
     * @param refElev Höhe (m), die auf baseY liegt – meist die tiefste Stelle des Gebiets
     * @param vScale  Blöcke je Höhenmeter: 1 = echt; kleiner nur, wenn die Welt sonst zu niedrig wäre
     */
    public DemGenerator(ElevationTiles tiles, double lat0, double lon0, double refElev, double vScale, int baseY,
            double snowElev) {
        this(tiles, lat0, lon0, refElev, vScale, baseY, snowElev, 0);
    }

    /**
     * @param radius halbe Kantenlänge (Blöcke) des Gebiets mit geladenen Kacheln; außerhalb gilt die Höhe am Rand –
     *               sonst holte der Weltgenerator Kacheln über das Netz, und ein Chunk stünde bis zu 60 s. 0 = ohne Grenze.
     */
    public DemGenerator(ElevationTiles tiles, double lat0, double lon0, double refElev, double vScale, int baseY,
            double snowElev, int radius) {
        this.tiles = tiles;
        this.lat0 = lat0;
        this.lon0 = lon0;
        this.cos0 = Math.cos(Math.toRadians(lat0));
        this.refElev = refElev;
        this.vScale = vScale;
        this.baseY = baseY;
        this.snowElev = snowElev;
        this.radius = radius;
    }

    /** Geländehöhe (Blockkoordinate der Oberfläche) an Block (x, z). */
    public int surfaceY(int x, int z, int minY, int maxY) {
        double e = elevationAt(x, z);
        return (int) Math.max(minY + 1, Math.min(maxY - 2, Math.round(baseY + (e - refElev) * vScale)));
    }

    double elevationAt(int x, int z) {
        if (radius > 0) {
            x = Math.max(-radius, Math.min(radius, x));
            z = Math.max(-radius, Math.min(radius, z));
        }
        double lat = lat0 - (z + 0.5) / M_PER_DEG_LAT;
        double lon = lon0 + (x + 0.5) / (M_PER_DEG_LON * cos0);
        try {
            return tiles.elevation(lat, lon);
        } catch (IOException e) {
            if (!warned) {
                warned = true;
                LOG.warning("gelände | höhendaten_fehlen | grund=" + e.getMessage() + " – dort flach auf Bezugshöhe");
            }
            return refElev;
        }
    }

    @Override
    public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
        paint(chunkX, chunkZ, info.getMinHeight(), info.getMaxHeight(), new Sink() {
            @Override
            public void set(int x, int y, int z, Material m) {
                data.setBlock(x, y, z, m);
            }

            @Override
            public void fill(int x, int y0, int z, int y1, Material m) {
                data.setRegion(x, y0, z, x + 1, y1, z + 1, m);
            }
        });
    }

    /**
     * Füllt die 16 × 16 Säulen eines Chunks (Ort-Koordinaten: Chunk (0,0) liegt an der Ortsmitte) ab floorY
     * (Grundgestein) bis zur Oberfläche. Der Sink bekommt Chunk-Koordinaten 0..15.
     */
    public void paint(int chunkX, int chunkZ, int floorY, int maxY, Sink sink) {
        // 18×18 Höhen (Chunk plus Rand), damit Hangneigung und freiliegende Wandhöhe auch am Chunkrand stimmen.
        int[][] h = new int[18][18];
        double[][] elev = new double[18][18];
        for (int dx = 0; dx < 18; dx++) {
            for (int dz = 0; dz < 18; dz++) {
                int x = chunkX * 16 + dx - 1;
                int z = chunkZ * 16 + dz - 1;
                elev[dx][dz] = elevationAt(x, z);
                h[dx][dz] = (int) Math.max(floorY + 1, Math.min(maxY - 2,
                        Math.round(baseY + (elev[dx][dz] - refElev) * vScale)));
            }
        }
        int seaY = (int) Math.round(baseY + (0 - refElev) * vScale);
        int[][] floor = new int[16][16];
        int[][] sea = new int[16][16];
        double[][] snow = new double[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                floor[x][z] = floorY;
                sea[x][z] = seaY;
                snow[x][z] = snowElev;
            }
        }
        paintColumns(h, elev, floor, sea, snow, maxY, sink);
    }

    /** Tiefster der vier direkten Nachbarn der Säule (x, z) im 18×18-Feld (Index 1..16 = Chunk). */
    public static int lowestNeighbour(int[][] h, int x, int z) {
        return Math.min(Math.min(h[x][z + 1], h[x + 2][z + 1]), Math.min(h[x + 1][z], h[x + 1][z + 2]));
    }

    /**
     * Die 16 × 16 Säulen eines Chunks. Vorher steckte das fest in paint() mit einem Grundgestein und einem
     * Meeresspiegel für den ganzen Ort; die Erdwelt (EarthTerrain) braucht beides je Säule (Grundgestein 64 unter der
     * Oberfläche, Meeresspiegel = −Höhenbezug, Schneegrenze nach Breite). Gleiche Säulen in beiden Welten, damit
     * Gelände und Erde gleich aussehen.
     *
     * @param h     18 × 18 Oberkanten (Blockkoordinate) samt einem Rand – für Hangneigung und Wandhöhe am Chunkrand
     * @param elev  18 × 18 Höhen in Metern (Sand bis 1 m, Schnee ab der Schneegrenze)
     * @param floor 16 × 16 Grundgestein (Blockkoordinate)
     * @param sea   16 × 16 Wasserspiegel (Blockkoordinate); Wasser nur, wo die Oberkante darunter liegt
     * @param snow  16 × 16 Schneegrenze in Metern
     */
    public static void paintColumns(int[][] h, double[][] elev, int[][] floor, int[][] sea, double[][] snow, int maxY,
            Sink sink) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int top = h[x + 1][z + 1];
                int lowest = lowestNeighbour(h, x, z);
                int floorY = floor[x][z];
                boolean steep = top - lowest >= 3;
                sink.set(x, floorY, z, Material.BEDROCK);
                if (steep) {
                    // Freiliegende Wand bis zum tiefsten Nachbarn geschichtet, darunter Stein.
                    int wallFrom = Math.max(floorY + 1, lowest - 1);
                    sink.fill(x, floorY + 1, z, wallFrom, Material.STONE);
                    for (int y = wallFrom; y <= top; y++) {
                        sink.set(x, y, z, STRATA[Math.floorMod(Math.floorDiv(y, 3), STRATA.length)]);
                    }
                    // Vorher bekam eine steile Säule nie Wasser – unter dem Meeresspiegel blieb dort ein Luftschacht
                    // im Meer (Steilküsten, Schelfkante der Erdwelt). Wer das zurückdreht, locht jeden Meeresgrund.
                    if (top < sea[x][z]) {
                        sink.fill(x, top + 1, z, Math.min(sea[x][z] + 1, maxY), Material.WATER);
                    }
                    continue;
                }
                sink.fill(x, floorY + 1, z, Math.max(floorY + 1, top - 3), Material.STONE);
                double e = elev[x + 1][z + 1];
                Material sub = e <= 1 ? Material.SAND : Material.DIRT;
                Material surface = e <= 1 ? Material.SAND : e >= snow[x][z] ? Material.SNOW_BLOCK
                        : top - lowest >= 2 ? Material.COARSE_DIRT : Material.GRASS_BLOCK;
                sink.fill(x, Math.max(floorY + 1, top - 3), z, top, sub);
                sink.set(x, top, z, surface);
                if (top < sea[x][z]) {
                    sink.fill(x, top + 1, z, Math.min(sea[x][z] + 1, maxY), Material.WATER);
                }
            }
        }
    }

    @Override
    public boolean isParallelCapable() {
        return true;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, surfaceY(0, 0, world.getMinHeight(), world.getMaxHeight()) + 1, 0.5);
    }
}
