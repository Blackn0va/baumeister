package com.blackn0va.baumeister.structure;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.Pos;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * Nachgebaute Mini-Schematics für die Dach-Erkennung, Block für Block von Hand gesetzt – bewusst NICHT über den
 * PlanCompiler. Sonst bewiese der Test nur, dass der Rückübersetzer seine eigene Nachbildung des Compilers wiedererkennt;
 * so steht das Dach da, wie ein Spieler es mit Treppen legt. Keine Downloads, keine Wiki-Bauten (Lizenzfrage „share
 * alike“ beim Lehrset offen, Gedächtnis).
 *
 * Lokale Achsen wie StructureFile.toLocal: Vorderseite bei z 0, Richtungen front/back/left/right; eine Treppe mit
 * facing=back hat ihre hohe Seite hinten (+z).
 */
public final class MiniVorbilder {

    private MiniVorbilder() {
    }

    /**
     * Steinhäuschen 5×7 (Boden, Wände, Fenster) mit Satteldach aus Fichtentreppen, First entlang z (der längeren
     * Seite), eine Treppe Überstand rundum, Fichtenplatten am First, Giebeldreiecke aus Fichtenbrettern.
     */
    public static Map<Pos, BlockSpec> cottage() {
        Map<Pos, BlockSpec> m = shed();
        for (int i = 0; i <= 3; i++) {
            int y = 4 + i;
            int a = i;
            int b = 6 - i;
            for (int z = 0; z <= 8; z++) {
                if (a == b) {
                    m.put(new Pos(a, y, z), BlockSpec.of("spruce_slab"));
                } else {
                    m.put(new Pos(a, y, z), stairs("spruce_stairs", "right", null));
                    m.put(new Pos(b, y, z), stairs("spruce_stairs", "left", null));
                }
            }
            for (int x = Math.max(a + 1, 1); x <= Math.min(b - 1, 5); x++) {
                m.put(new Pos(x, y, 1), BlockSpec.of("spruce_planks"));
                m.put(new Pos(x, y, 7), BlockSpec.of("spruce_planks"));
            }
        }
        return m;
    }

    /** Das Häuschen ohne Dach: Boden, Wände, Fenster – kein einziger Treppenblock. */
    public static Map<Pos, BlockSpec> shed() {
        Map<Pos, BlockSpec> m = new HashMap<>();
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 7; z++) {
                m.put(new Pos(x, 0, z), BlockSpec.of("cobblestone"));
                for (int y = 1; y <= 3; y++) {
                    if (x == 1 || x == 5 || z == 1 || z == 7) {
                        m.put(new Pos(x, y, z), BlockSpec.of("stone_bricks"));
                    }
                }
            }
        }
        m.put(new Pos(2, 2, 1), BlockSpec.of("glass_pane"));
        m.put(new Pos(4, 2, 1), BlockSpec.of("glass_pane"));
        m.put(new Pos(1, 2, 4), BlockSpec.of("glass_pane"));
        m.put(new Pos(5, 2, 4), BlockSpec.of("glass_pane"));
        return m;
    }

    /** Häuschen, dessen First offen ist (keine Platten): zwei Treppenschrägen, aber kein Dach, das roof bauen würde. */
    public static Map<Pos, BlockSpec> openRidge() {
        Map<Pos, BlockSpec> m = cottage();
        m.values().removeIf(b -> b.id().equals("spruce_slab"));
        return m;
    }

    /**
     * Pavillon 7×7: Steinboden, vier Stammsäulen, Walmdach aus Ziegeltreppen. Die Ecken tragen wie im Spiel
     * shape=outer_… – das rechnet erst der Server am Nachbarn aus, der roof-Schritt schreibt es nicht.
     */
    public static Map<Pos, BlockSpec> pavilion() {
        Map<Pos, BlockSpec> m = new HashMap<>();
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                m.put(new Pos(x, 0, z), BlockSpec.of("smooth_stone"));
            }
        }
        for (int[] c : new int[][] {{0, 0}, {6, 0}, {0, 6}, {6, 6}}) {
            for (int y = 1; y <= 2; y++) {
                m.put(new Pos(c[0], y, c[1]), new BlockSpec("oak_log", Map.of("axis", "y")));
            }
        }
        for (int i = 0; i <= 3; i++) {
            int y = 3 + i;
            int lo = i;
            int hi = 6 - i;
            if (lo == hi) {
                m.put(new Pos(lo, y, lo), BlockSpec.of("brick_slab"));
                break;
            }
            for (int x = lo; x <= hi; x++) {
                String shape = x == lo ? "outer_left" : x == hi ? "outer_right" : null;
                m.put(new Pos(x, y, lo), stairs("brick_stairs", "back", shape));
                m.put(new Pos(x, y, hi), stairs("brick_stairs", "front", shape));
            }
            for (int z = lo + 1; z < hi; z++) {
                m.put(new Pos(lo, y, z), stairs("brick_stairs", "right", null));
                m.put(new Pos(hi, y, z), stairs("brick_stairs", "left", null));
            }
        }
        return m;
    }

    /** Freitreppe vor einer Mauer: Treppen nur mit facing=back, keine Gegenreihe – das ist kein Dach. */
    public static Map<Pos, BlockSpec> staircase() {
        Map<Pos, BlockSpec> m = new HashMap<>();
        for (int x = 0; x <= 6; x++) {
            for (int y = 0; y <= 6; y++) {
                for (int z = 7; z <= 8; z++) {
                    m.put(new Pos(x, y, z), BlockSpec.of("stone_bricks"));
                }
            }
        }
        for (int k = 0; k <= 6; k++) {
            for (int x = 2; x <= 4; x++) {
                m.put(new Pos(x, k, k), stairs("stone_brick_stairs", "back", null));
                for (int y = 0; y < k; y++) {
                    m.put(new Pos(x, y, k), BlockSpec.of("stone_bricks"));
                }
            }
        }
        return m;
    }

    /**
     * Doppelbank: zwei Treppenreihen Rücken an Rücken ohne Lücke auf einem Bretterboden (lowFacing an der vorderen
     * Reihe, highFacing an der hinteren). Nebeneinander wie ein Dach aus einer Lage – aber eine Bank. alongX = false
     * dreht den Bau um 90° (Reihen entlang z, facing dann right/left).
     */
    public static Map<Pos, BlockSpec> doubleBench(String lowFacing, String highFacing, boolean alongX) {
        Map<Pos, BlockSpec> m = new HashMap<>();
        for (int a = 0; a < 5; a++) {
            for (int b = 0; b <= 1; b++) {
                m.put(alongX ? new Pos(a, 0, b) : new Pos(b, 0, a), BlockSpec.of("oak_planks"));
            }
            m.put(alongX ? new Pos(a, 1, 0) : new Pos(0, 1, a), stairs("oak_stairs", lowFacing, null));
            m.put(alongX ? new Pos(a, 1, 1) : new Pos(1, 1, a), stairs("oak_stairs", highFacing, null));
        }
        return m;
    }

    /**
     * Kleinstes Satteldach, das roof noch ist: 6 lang, 4 tief, zwei Treppenlagen (y 0 außen, y 1 innen), ohne Platte
     * am First, auf einem Bretterboden darunter.
     */
    public static Map<Pos, BlockSpec> twoLayerRoof() {
        Map<Pos, BlockSpec> m = new HashMap<>();
        for (int x = 0; x < 6; x++) {
            m.put(new Pos(x, 0, 0), stairs("oak_stairs", "back", null));
            m.put(new Pos(x, 0, 3), stairs("oak_stairs", "front", null));
            m.put(new Pos(x, 0, 1), BlockSpec.of("oak_planks"));
            m.put(new Pos(x, 0, 2), BlockSpec.of("oak_planks"));
            m.put(new Pos(x, 1, 1), stairs("oak_stairs", "back", null));
            m.put(new Pos(x, 1, 2), stairs("oak_stairs", "front", null));
        }
        return m;
    }

    /**
     * Schritte außer roof mit einem Treppenblock. Ohne Erkennung wurden Dachtreppen zu line, set und – wo eine Reihe
     * ein Quader aus einer Zelle Breite ist – auch zu fill (gemessen 2026-11-21 am Häuschen: 10 fill, 10 set).
     */
    public static int stairsSteps(String json) {
        int n = 0;
        for (JsonElement s : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("steps")) {
            JsonObject o = s.getAsJsonObject();
            if (!o.get("op").getAsString().equals("roof") && o.get("block").getAsString().contains("_stairs")) {
                n++;
            }
        }
        return n;
    }

    private static BlockSpec stairs(String id, String facing, String shape) {
        return shape == null ? new BlockSpec(id, Map.of("facing", facing))
                : new BlockSpec(id, Map.of("facing", facing, "shape", shape));
    }
}
