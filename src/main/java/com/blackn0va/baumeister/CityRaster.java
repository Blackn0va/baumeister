package com.blackn0va.baumeister;

import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.CityPlanner.Cell;
import com.blackn0va.baumeister.osm.CityPlanner.Section;

import java.util.Arrays;
import java.util.List;

/**
 * Draufsicht einer Etappe als Raster mit einer Kategorie je Zelle – für die Stadtansicht im Baumeister-Host
 * (Schnittstelle /baumeister/v1/stadt, Version 1, siehe HostReporter). Läuft in StagePrep, also im Hintergrund-Pool, nie
 * im Bukkit-Takt: eine Etappe hat bis zu ~1 Mio. Zellen.
 *
 * Kategorien und Vorrang stehen so in der Schnittstelle; der Host färbt danach. Eine Zelle gewinnt immer die Kategorie
 * mit dem höchsten Vorrang unter allen Blöcken darin (Gebäude &gt; Brücke &gt; Gleis &gt; Straße &gt; Wasser &gt;
 * Fußweg &gt; Baum &gt; Grün &gt; sonstiges) – sonst überdeckte z. B. eine Laterne die Straße.
 *
 * Brücke: Weg- oder Straßenbelag mindestens 2 Blöcke über dem niedrigsten Nicht-Gebäude-Block derselben Rasterzelle.
 * Über die Blockart allein ginge das nicht (das Deck ist derselbe Beton wie die Straße), und eine feste Höhe nicht, weil
 * der Weltbau absolute Höhen plant.
 *
 * Threads: rein, beliebig. Fehler: keine.
 */
final class CityRaster {

    static final byte EMPTY = 0;
    static final byte BUILDING = 1;
    static final byte ROAD = 2;
    static final byte PATH = 3;
    static final byte WATER = 4;
    static final byte GREEN = 5;
    static final byte TREE = 6;
    static final byte RAIL = 7;
    static final byte BRIDGE = 8;
    static final byte OTHER = 9;

    /** Vorrang je Kategorie (Index = Kategorie). */
    private static final int[] RANK = {0, 9, 6, 4, 5, 2, 3, 7, 8, 1};
    /**
     * Größte Rasterseite: 360² als Base64 ≈ 173 KB passt unter die 256-KiB-Grenze des Host-Servers (http.hpp
     * kMaxBodyBytes). 512 (erster Entwurf) hätte ≈ 350 KB ergeben und jede volle Etappe mit 413 abprallen lassen.
     */
    static final int MAX_SIDE = 360;
    private static final int MAX_CELL = 16;

    /** x0/z0: Weltkoordinate der linken oberen Rasterecke; cells zeilenweise (z außen, x innen). */
    record Raster(int x0, int z0, int cell, int w, int h, byte[] cells) {
    }

    private CityRaster() {
    }

    /** Zellen, die ein ganzer Auftrag höchstens belegen soll – der Host hält 16 Mio. (server/city_api.hpp). */
    static final long JOB_CELLS = 8_000_000L;
    /** Fläche einer Etappe in Blöcken (CityStages.STAGE² und WorldTileSource, beide 256 m). */
    private static final long STAGE_AREA = 256L * 256L;

    /**
     * Kleinste Zellgröße, mit der count Etappen zusammen unter JOB_CELLS bleiben. Vorher immer 1 Block je Zelle:
     * Eberdingen (2449 Etappen ≈ 160 Mio. Zellen) überschritt die Host-Grenze ab Etappe ~244, jede weitere Etappe kam mit
     * 400 zurück und fehlte auf der Karte (GPU-PC 2026-10-29). Jetzt 5 Blöcke je Zelle ≈ 6,4 Mio.
     */
    static int cellFor(int count) {
        double cells = (double) Math.max(1, count) * STAGE_AREA;
        return (int) Math.min(MAX_CELL, Math.max(1, Math.ceil(Math.sqrt(cells / JOB_CELLS))));
    }

    /** null, wenn die Etappe nichts enthält. baseX/baseZ wie StagePrep (Zelle x liegt bei baseX + x). */
    static Raster of(CityPlanner.Plan p, int baseX, int baseZ) {
        return of(p, baseX, baseZ, 1);
    }

    /** minCell: mindestens so viele Blöcke je Zelle (cellFor), sonst wie oben. */
    static Raster of(CityPlanner.Plan p, int baseX, int baseZ, int minCell) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Section s : p.sections()) {
            for (Cell c : s.cells()) {
                minX = Math.min(minX, c.x());
                minZ = Math.min(minZ, c.z());
                maxX = Math.max(maxX, c.x());
                maxZ = Math.max(maxZ, c.z());
            }
        }
        if (minX > maxX) {
            return null;
        }
        int span = Math.max(maxX - minX + 1, maxZ - minZ + 1);
        int cell = Math.min(MAX_CELL, Math.max(Math.max(1, minCell), (span + MAX_SIDE - 1) / MAX_SIDE));
        int w = Math.min(MAX_SIDE, (maxX - minX) / cell + 1);
        int h = Math.min(MAX_SIDE, (maxZ - minZ) / cell + 1);
        List<String> palette = p.palette();
        byte[] kind = new byte[palette.size()];
        for (int i = 0; i < kind.length; i++) {
            kind[i] = material(palette.get(i));
        }
        // 1. Durchgang: niedrigster Nicht-Gebäude-Block je Rasterzelle (Bezug für Brücken).
        int[] ground = new int[w * h];
        Arrays.fill(ground, Integer.MAX_VALUE);
        for (Section s : p.sections()) {
            if (s.building()) {
                continue;
            }
            for (Cell c : s.cells()) {
                if (category(kind, c.mat()) == EMPTY) {
                    continue;
                }
                int i = index(c, minX, minZ, cell, w, h);
                if (i >= 0 && c.y() < ground[i]) {
                    ground[i] = c.y();
                }
            }
        }
        // 2. Durchgang: Kategorie mit dem höchsten Vorrang je Rasterzelle.
        byte[] out = new byte[w * h];
        for (Section s : p.sections()) {
            for (Cell c : s.cells()) {
                byte k = category(kind, c.mat());
                if (k == EMPTY) {
                    continue;
                }
                int i = index(c, minX, minZ, cell, w, h);
                if (i < 0) {
                    continue;
                }
                if (s.building()) {
                    k = BUILDING;
                } else if ((k == ROAD || k == PATH || k == OTHER && c.mat() >= 0 && deck(palette.get(c.mat())))
                        && ground[i] != Integer.MAX_VALUE && c.y() >= ground[i] + 2) {
                    k = BRIDGE;
                }
                if (RANK[k] > RANK[out[i]]) {
                    out[i] = k;
                }
            }
        }
        return new Raster(baseX + minX, baseZ + minZ, cell, w, h, out);
    }

    /**
     * Kategorie einer Zellnummer. TerrainFit.PLINTH (−1) ist der Sockel unter Häusern am Hang (Weltbau, Stadt auf
     * Gelände) und steht in keiner Palette – vorher las das kind[-1] und warf ArrayIndexOutOfBoundsException: Auf dem
     * Testserver blieb 2026-10-29 jede Etappe der Weltbau-Kachel 366/6584 bei 0 Blöcken und wurde übersprungen. Andere
     * Nummern außerhalb der Palette gelten als leer.
     */
    private static byte category(byte[] kind, int mat) {
        if (mat == TerrainFit.PLINTH) {
            return OTHER;
        }
        return mat >= 0 && mat < kind.length ? kind[mat] : EMPTY;
    }

    private static int index(Cell c, int minX, int minZ, int cell, int w, int h) {
        int x = (c.x() - minX) / cell;
        int z = (c.z() - minZ) / cell;
        return x < w && z < h ? z * w + x : -1;
    }

    /** Kategorie nach Blockname (ohne Zustand); EMPTY für Luft. */
    static byte material(String block) {
        int bracket = block.indexOf('[');
        String b = (bracket < 0 ? block : block.substring(0, bracket)).replace("minecraft:", "");
        if (b.equals("air") || b.equals("cave_air") || b.equals("void_air")) {
            return EMPTY;
        }
        if (b.equals("water")) {
            return WATER;
        }
        if (b.contains("rail") || b.equals("gravel")) {
            return RAIL;
        }
        if (b.endsWith("_leaves") || b.endsWith("_log") || b.endsWith("_wood")) {
            return TREE;
        }
        return switch (b) {
            case "gray_concrete", "black_concrete", "white_concrete", "yellow_concrete", "smooth_stone" -> ROAD;
            case "polished_andesite", "andesite", "dirt_path", "red_concrete", "stone_bricks", "cobblestone" -> PATH;
            case "grass_block", "lime_concrete", "coarse_dirt", "moss_block", "podzol", "farmland", "short_grass",
                    "tall_grass", "fern" -> GREEN;
            default -> OTHER;
        };
    }

    /** Brückendeck aus Holz oder Stufen (sonst „sonstiges“) zählt erhöht als Brücke. */
    private static boolean deck(String block) {
        return block.contains("_planks") || block.contains("_slab");
    }
}
