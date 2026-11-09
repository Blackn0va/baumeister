package com.blackn0va.baumeister;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.imageio.ImageIO;

import com.blackn0va.baumeister.minimap.OverviewStore;
import com.blackn0va.baumeister.minimap.RegionScanner;

/**
 * Messprobe: Wie sieht das erzeugte Gelände einer Gegend wirklich aus? Liest kopierte Regionsdateien (.mca) und
 * schreibt ein Bild – Farbe nach Oberflächenblock, Helligkeit nach Höhe mit Schummerung – plus Zahlen zu Wasser,
 * Strand und Höhen. Frage 2026-11-01: „Sydney ist nicht so gut“ – was genau?
 *
 * Aufruf: java -cp target/classes;target/test-classes;lib/* com.blackn0va.baumeister.RegionRenderProbe <ordner> <png>
 * Erdwelt: minY −2032, maxY 2032 (Datapack-Dimension baumeister:erde). Ein Bildpunkt = 4 Blöcke.
 */
public final class RegionRenderProbe {

    private static final int WATER = 1;
    private static final int SAND = 2;
    private static final int GRASS = 3;
    private static final int STONE = 4;
    private static final int SNOW = 5;
    private static final int BUILT = 6;
    private static final int DIRT = 7;
    private static final int TREE = 8;

    private RegionRenderProbe() {
    }

    static int category(String name) {
        if (name == null || name.endsWith("air")) {
            return 0;
        }
        String n = name.replace("minecraft:", "");
        if (n.contains("water") || n.equals("ice") || n.contains("kelp") || n.contains("seagrass")) {
            return WATER;
        }
        if (n.contains("sand") && !n.contains("stone")) {
            return SAND;
        }
        if (n.equals("grass_block") || n.contains("moss") || n.equals("short_grass") || n.equals("tall_grass")) {
            return GRASS;
        }
        if (n.contains("leaves") || n.contains("_log")) {
            return TREE;
        }
        if (n.contains("snow")) {
            return SNOW;
        }
        if (n.equals("dirt") || n.equals("coarse_dirt") || n.equals("podzol") || n.equals("mud")
                || n.equals("gravel") || n.equals("dirt_path")) {
            return DIRT;
        }
        if (n.equals("stone") || n.equals("deepslate") || n.equals("andesite") || n.equals("granite")
                || n.equals("diorite") || n.equals("tuff") || n.equals("calcite")) {
            return STONE;
        }
        return BUILT;
    }

    private static final Map<String, Integer> SEEN = new TreeMap<>();

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args[0]);
        int minRx = Integer.MAX_VALUE;
        int minRz = Integer.MAX_VALUE;
        int maxRx = Integer.MIN_VALUE;
        int maxRz = Integer.MIN_VALUE;
        Map<Path, int[]> coords = new TreeMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "r.*.mca")) {
            for (Path p : ds) {
                int[] rc = RegionScanner.regionCoords(p);
                coords.put(p, rc);
                minRx = Math.min(minRx, rc[0]);
                minRz = Math.min(minRz, rc[1]);
                maxRx = Math.max(maxRx, rc[0]);
                maxRz = Math.max(maxRz, rc[1]);
            }
        }
        int per = 32 * 4;  // Bildpunkte je Region (32 Chunks × 4 Proben)
        int w = (maxRx - minRx + 1) * per;
        int h = (maxRz - minRz + 1) * per;
        int[] cat = new int[w * h];
        int[] height = new int[w * h];
        boolean[] known = new boolean[w * h];
        for (Map.Entry<Path, int[]> e : coords.entrySet()) {
            RegionScanner.Result r = RegionScanner.scan(e.getKey(), -2032, 2032, name -> {
                SEEN.merge(name.replace("minecraft:", ""), 1, Integer::sum);
                return category(name);
            }, Set.of());
            int ox = (e.getValue()[0] - minRx) * per;
            int oz = (e.getValue()[1] - minRz) * per;
            for (int c = 0; c < 1024; c++) {
                int cx = c % 32;
                int cz = c / 32;
                for (int s = 0; s < 16; s++) {
                    int v = r.data()[c * 16 + s];
                    if (v == 0) {
                        continue;
                    }
                    int px = ox + cx * 4 + s % 4;
                    int pz = oz + cz * 4 + s / 4;
                    int i = pz * w + px;
                    cat[i] = OverviewStore.base(v) & 0x3F;
                    height[i] = OverviewStore.height(v);
                    known[i] = true;
                }
            }
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] count = new int[16];
        int minLand = Integer.MAX_VALUE;
        int maxLand = Integer.MIN_VALUE;
        int beachNextToWater = 0;
        int landNextToWater = 0;
        int cliffNextToWater = 0;
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int i = z * w + x;
                if (!known[i]) {
                    img.setRGB(x, z, 0x202020);
                    continue;
                }
                int c = cat[i];
                count[c]++;
                if (c != WATER) {
                    minLand = Math.min(minLand, height[i]);
                    maxLand = Math.max(maxLand, height[i]);
                    boolean nearWater = false;
                    for (int d = 0; d < 4 && !nearWater; d++) {
                        int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                        int nz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
                        if (nx >= 0 && nz >= 0 && nx < w && nz < h && known[nz * w + nx] && cat[nz * w + nx] == WATER) {
                            nearWater = true;
                            if (height[i] - height[nz * w + nx] >= 6) {
                                cliffNextToWater++;
                            }
                        }
                    }
                    if (nearWater) {
                        landNextToWater++;
                        beachNextToWater += c == SAND ? 1 : 0;
                    }
                }
                int rgb = switch (c) {
                    case WATER -> 0x2a5db0;
                    case SAND -> 0xe3d38f;
                    case GRASS -> 0x5f9e3c;
                    case TREE -> 0x2f6b2a;
                    case STONE -> 0x8a8a8a;
                    case SNOW -> 0xf4f4f4;
                    case DIRT -> 0x8b6a45;
                    case BUILT -> 0xc06060;
                    default -> 0x000000;
                };
                // Schummerung: Höhenunterschied zum Nachbarn links oben (Licht aus Nordwest).
                int hn = x > 0 && z > 0 && known[i - w - 1] ? height[i - w - 1] : height[i];
                double shade = Math.max(0.55, Math.min(1.35, 1.0 + (height[i] - hn) * 0.06));
                int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * shade));
                int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * shade));
                int b = Math.min(255, (int) ((rgb & 0xFF) * shade));
                img.setRGB(x, z, (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(img, "png", Path.of(args[1]).toFile());
        int total = 0;
        for (int v : count) {
            total += v;
        }
        System.out.printf("Bild %dx%d (1 px = 4 Blöcke), bekannt %d Proben%n", w, h, total);
        String[] names = {"leer", "Wasser", "Sand", "Gras", "Fels", "Schnee", "Bebaut/sonst", "Erde/Kies", "Baum"};
        for (int k = 0; k < names.length; k++) {
            System.out.printf("  %-13s %6.1f %%%n", names[k], 100.0 * count[k] / Math.max(1, total));
        }
        System.out.printf("Landhöhe y %d … %d%n", minLand, maxLand);
        // Auffällig tiefe Landproben (unter −20): Löcher im Meer, wo Wasser fehlt? Lage im Bild und Kategorie.
        int shown = 0;
        int deep = 0;
        for (int i = 0; i < w * h; i++) {
            if (known[i] && cat[i] != WATER && height[i] < -20) {
                deep++;
                if (shown++ < 8) {
                    System.out.printf("  tief: px %d,%d (Block x+%d z+%d) y %d Kategorie %d%n", i % w, i / w,
                            (i % w) * 4, (i / w) * 4, height[i], cat[i]);
                }
            }
        }
        System.out.printf("Landproben unter −20: %d%n", deep);
        System.out.printf("Küstenproben (Land neben Wasser): %d, davon Sand %.1f %%, Steilufer (≥ 6 Blöcke) %.1f %%%n",
                landNextToWater, 100.0 * beachNextToWater / Math.max(1, landNextToWater),
                100.0 * cliffNextToWater / Math.max(1, landNextToWater));
        System.out.println("Häufigste Oberflächenblöcke:");
        SEEN.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(14)
                .forEach(en -> System.out.printf("  %-24s %d%n", en.getKey(), en.getValue()));
    }
}
