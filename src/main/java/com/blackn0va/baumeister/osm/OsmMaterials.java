package com.blackn0va.baumeister.osm;

import java.util.Locale;
import java.util.Map;

/**
 * OSM-Farben und -Baustoffe → Minecraft-Blöcke.
 *
 * Warum: Bis 2026-10-28 bekam jedes Haus eine Zufallsfarbe aus acht Fassaden und jede Kirche Steinziegel – „mit
 * Design“ (Nutzerwunsch) heißt, was OSM weiß, auch zu bauen: building:colour, building:material, roof:colour,
 * roof:material. Farben gehen zum nächstgelegenen Block (RGB-Abstand) aus einer Palette ruhiger Baustoffe; ein Baustoff
 * schlägt die Farbe nur, wenn keine Farbe angegeben ist. Kupferdächer werden grün (oxidiert) wie echte Kirchendächer.
 *
 * Threads: zustandslos. Fehler: Unbekanntes ergibt "" – der Planer nimmt dann seine Vorgabe.
 */
public final class OsmMaterials {

    private OsmMaterials() {
    }

    /** Fassadenblöcke mit mittlerer Farbe (gemessen an den Texturen, gerundet). */
    private static final Map<String, int[]> WALLS = Map.ofEntries(
            Map.entry("white_concrete", new int[] {207, 213, 214}),
            Map.entry("light_gray_concrete", new int[] {125, 125, 115}),
            Map.entry("gray_concrete", new int[] {54, 57, 61}),
            Map.entry("black_concrete", new int[] {8, 10, 15}),
            Map.entry("quartz_block", new int[] {235, 229, 222}),
            Map.entry("smooth_sandstone", new int[] {223, 214, 170}),
            Map.entry("white_terracotta", new int[] {209, 178, 161}),
            Map.entry("light_gray_terracotta", new int[] {135, 106, 97}),
            Map.entry("terracotta", new int[] {152, 94, 67}),
            Map.entry("orange_terracotta", new int[] {161, 83, 37}),
            Map.entry("yellow_terracotta", new int[] {186, 133, 35}),
            Map.entry("red_terracotta", new int[] {143, 61, 46}),
            Map.entry("brown_terracotta", new int[] {77, 51, 35}),
            Map.entry("pink_terracotta", new int[] {161, 78, 78}),
            Map.entry("green_terracotta", new int[] {76, 83, 42}),
            Map.entry("cyan_terracotta", new int[] {86, 91, 91}),
            Map.entry("bricks", new int[] {150, 97, 83}),
            Map.entry("stone_bricks", new int[] {122, 121, 122}),
            Map.entry("yellow_concrete", new int[] {240, 175, 21}),
            Map.entry("orange_concrete", new int[] {224, 97, 0}),
            Map.entry("red_concrete", new int[] {142, 32, 32}),
            Map.entry("brown_concrete", new int[] {96, 59, 31}),
            Map.entry("light_blue_concrete", new int[] {35, 137, 198}),
            Map.entry("blue_concrete", new int[] {44, 46, 143}),
            Map.entry("green_concrete", new int[] {73, 91, 36}),
            Map.entry("lime_concrete", new int[] {94, 168, 24}),
            Map.entry("pink_concrete", new int[] {213, 101, 142}));

    /** Dachblöcke. */
    private static final Map<String, int[]> ROOFS = Map.ofEntries(
            Map.entry("deepslate_tiles", new int[] {54, 54, 55}),
            Map.entry("dark_oak_planks", new int[] {66, 43, 20}),
            Map.entry("red_terracotta", new int[] {143, 61, 46}),
            Map.entry("brown_terracotta", new int[] {77, 51, 35}),
            Map.entry("terracotta", new int[] {152, 94, 67}),
            Map.entry("bricks", new int[] {150, 97, 83}),
            Map.entry("light_gray_concrete", new int[] {125, 125, 115}),
            Map.entry("gray_concrete", new int[] {54, 57, 61}),
            Map.entry("white_concrete", new int[] {207, 213, 214}),
            Map.entry("oxidized_cut_copper", new int[] {79, 153, 126}),
            Map.entry("blue_terracotta", new int[] {74, 60, 91}),
            Map.entry("black_concrete", new int[] {8, 10, 15}));

    /** Farbnamen, wie sie in OSM vorkommen (englisch, laut Wiki-Empfehlung). */
    private static final Map<String, int[]> NAMES = Map.ofEntries(
            Map.entry("white", new int[] {240, 240, 240}),
            Map.entry("black", new int[] {20, 20, 20}),
            Map.entry("gray", new int[] {128, 128, 128}),
            Map.entry("grey", new int[] {128, 128, 128}),
            Map.entry("silver", new int[] {192, 192, 192}),
            Map.entry("red", new int[] {170, 40, 35}),
            Map.entry("darkred", new int[] {120, 20, 20}),
            Map.entry("maroon", new int[] {110, 25, 25}),
            Map.entry("brown", new int[] {120, 75, 40}),
            Map.entry("orange", new int[] {220, 120, 30}),
            Map.entry("yellow", new int[] {230, 200, 60}),
            Map.entry("beige", new int[] {225, 210, 170}),
            Map.entry("cream", new int[] {240, 230, 190}),
            Map.entry("tan", new int[] {210, 180, 140}),
            Map.entry("pink", new int[] {230, 150, 160}),
            Map.entry("green", new int[] {70, 130, 70}),
            Map.entry("darkgreen", new int[] {30, 80, 40}),
            Map.entry("blue", new int[] {50, 70, 160}),
            Map.entry("lightblue", new int[] {120, 170, 220}),
            Map.entry("purple", new int[] {110, 50, 140}));

    /** Fassade aus building:colour / colour / building:material; "" = nichts bekannt. */
    public static String wall(Map<String, String> tags) {
        int[] rgb = colour(tags.getOrDefault("building:colour", tags.get("colour")));
        if (rgb != null) {
            return nearest(WALLS, rgb);
        }
        String m = tags.getOrDefault("building:material", "").toLowerCase(Locale.ROOT);
        return switch (m) {
            case "brick", "bricks" -> "bricks";
            case "stone", "sandstone_block", "masonry" -> "stone_bricks";
            case "sandstone" -> "smooth_sandstone";
            case "wood", "timber_framing" -> "spruce_planks";
            case "glass" -> "light_blue_stained_glass";
            case "concrete", "cement_block" -> "light_gray_concrete";
            case "plaster" -> "white_terracotta";
            case "metal", "steel" -> "light_gray_concrete";
            default -> "";
        };
    }

    /** Dach aus roof:colour / roof:material; "" = nichts bekannt. */
    public static String roof(Map<String, String> tags) {
        String material = tags.getOrDefault("roof:material", "").toLowerCase(Locale.ROOT);
        if (material.equals("copper")) {
            return "oxidized_cut_copper";  // grüne Patina wie auf echten Kirchen- und Rathausdächern
        }
        int[] rgb = colour(tags.get("roof:colour"));
        if (rgb != null) {
            return nearest(ROOFS, rgb);
        }
        return switch (material) {
            case "roof_tiles", "tiles", "clay" -> "red_terracotta";
            case "slate" -> "deepslate_tiles";
            case "metal", "tin", "zinc" -> "light_gray_concrete";
            case "concrete", "tar_paper", "asphalt" -> "gray_concrete";
            case "glass" -> "glass";
            case "thatch" -> "hay_block";
            case "grass" -> "moss_block";
            case "wood" -> "dark_oak_planks";
            default -> "";
        };
    }

    /** „#a1b2c3“, „#abc“ oder ein Farbname → RGB; sonst null. */
    static int[] colour(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
        if (s.startsWith("#")) {
            String h = s.substring(1);
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            }
            if (h.matches("[0-9a-f]{6}")) {
                int v = Integer.parseInt(h, 16);
                return new int[] {v >> 16 & 255, v >> 8 & 255, v & 255};
            }
            return null;
        }
        return NAMES.get(s);
    }

    static String nearest(Map<String, int[]> palette, int[] rgb) {
        String best = "";
        long bestD = Long.MAX_VALUE;
        for (Map.Entry<String, int[]> e : palette.entrySet()) {
            int[] c = e.getValue();
            // Gewichtet wie das Auge (Rot 2, Grün 4, Blau 3) – sonst landet Beige bei Grau statt bei Sandstein.
            long d = 2L * sq(c[0] - rgb[0]) + 4L * sq(c[1] - rgb[1]) + 3L * sq(c[2] - rgb[2]);
            if (d < bestD || d == bestD && e.getKey().compareTo(best) < 0) {
                bestD = d;
                best = e.getKey();
            }
        }
        return best;
    }

    private static long sq(int v) {
        return (long) v * v;
    }
}
