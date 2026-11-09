package com.blackn0va.baumeister.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Schnellauftrag „bau mir einen Bunker“ / „bau mir hier Fort Knox“: Anlage unter UND über der Erde, ohne LLM.
 *
 * Vorher ging der Auftrag ans LLM (dev01 2026-10-30, Llama 3.1 8B und Qwen2.5 3B, „baue mir einen unterirdische
 * bunkeranlage“): dreimal je zwei Läufe à 26–62 s, ein Zylinder mit Radius 16 als Klotz, 40-mal dieselbe Zeile, eine
 * Scheibe vor der Wand – alle verworfen. Ein Bunker ist Wiederholung (Ebene über Ebene, Leiter durch alle Decken),
 * genau das, was der Compiler sicher rechnet und das kleine Modell nicht.
 *
 * Fort Knox nach den Maßen des United States Bullion Depository (Wikipedia, gelesen 2026-10-30): Gebäude 32 × 37 m,
 * 13 m hoch, granitverkleidet; darunter der Tresor über zwei Geschosse (unter 370 m², hier 17 × 17 innen); Wachposten
 * an den vier Ecken, Zaun, Torposten. 1 Block = 1 m. Der allgemeine Bunker: getarnter Eingangsbau, darunter 1–3
 * Ebenen (klein/normal/groß), Lager, Lüftung, auf der untersten Ebene ein Tresorraum.
 *
 * Lage (Plankoordinaten wie im Prompt: x rechts, z vom Spieler weg, y 0 = erste Schicht über dem Boden): Alles unter
 * der Erde liegt hinter dem Eingang (z ≥ 0), die Leiter läuft an einer Wand durch jede Decke – Löcher entstehen,
 * weil die Leiter als letzter Schritt die Deckenzelle überschreibt. Innenräume werden vorher mit air ausgehöhlt,
 * sonst blieben Erde und Stein im Raum stehen. Eisentüren öffnen nur mit Knopf: einer außen, einer innen.
 *
 * Threads: zustandslos. Fehler: keine – passt die Anlage nicht in bau.max-ausdehnung, gibt es leer zurück (dann
 * plant das LLM).
 */
public final class Bunker {

    static final Set<String> WORDS = Set.of("bunker", "bunkers", "bunkeranlage", "bunkeranlagen", "schutzbunker",
            "atombunker", "luftschutzbunker", "schutzraum");
    /** Beschreiben nur, was ohnehin gebaut wird – „unterirdische Bunkeranlage, nach unten und oben“. */
    static final Set<String> MODIFIERS = Set.of("unterirdisch", "unterirdische", "unterirdischen", "unterirdischer",
            "unterirdisches", "geheim", "geheime", "geheimen", "geheimer", "geheimes", "anlage", "nach", "unten",
            "oben", "und", "so", "wie", "echt", "echte", "echten", "echtes", "original", "originalgetreu", "nachbau",
            "in", "von", "fort");

    enum Kind {
        KNOX, BUNKER
    }

    /** levels und width nur für BUNKER; Fort Knox hat feste Maße. */
    record Order(Kind kind, int levels, int width) {
    }

    private static final String KNOX_WALL = "polished_diorite";
    private static final String KNOX_TRIM = "polished_andesite";
    private static final String CONCRETE = "gray_concrete";
    private static final int LEVEL = 6;  // Ebenenraster: Boden + 5 Luft, die Decke ist der Boden darüber

    private Bunker() {
    }

    /**
     * Erkennung auf normalisierten Wörtern: ein Bunkerwort oder „Fort Knox“, sonst nur Füll-, Größen- und
     * Beschreibungswörter. „fort“ allein (eine Festung) ist kein Treffer.
     */
    static Optional<Order> parse(List<String> tokens, Set<String> filler) {
        boolean bunker = false;
        boolean knox = false;
        boolean big = false;
        boolean small = false;
        for (String t : tokens) {
            if (WORDS.contains(t)) {
                bunker = true;
            } else if (t.equals("knox") || t.equals("fortknox")) {
                knox = true;
            } else if (MachineHall.BIG.contains(t)) {
                big = true;
            } else if (MachineHall.SMALL.contains(t)) {
                small = true;
            } else if (!filler.contains(t) && !MODIFIERS.contains(t)) {
                return Optional.empty();
            }
        }
        if (knox) {
            return Optional.of(new Order(Kind.KNOX, 0, 0));
        }
        if (!bunker || big && small) {
            return Optional.empty();
        }
        return Optional.of(big ? new Order(Kind.BUNKER, 3, 27) : small ? new Order(Kind.BUNKER, 1, 15)
                : new Order(Kind.BUNKER, 2, 21));
    }

    static Optional<String> plan(Order o, int maxExtent) {
        return o.kind() == Kind.KNOX ? knox(maxExtent) : bunker(o.levels(), o.width(), maxExtent);
    }

    // ---------------------------------------------------------------- Fort Knox

    static Optional<String> knox(int maxExtent) {
        // Gebäude 32 × 37, Vorderwand 6 vor dem Spieler, damit Zaun und Torposten davor Platz haben.
        int bx0 = -16;
        int bx1 = 15;
        int bz0 = 6;
        int bz1 = 42;
        // Tresor 19 × 19 (innen 17 × 17), mittig unter dem Gebäude, über zwei Geschosse unter dem Untergeschoss.
        int vx0 = -9;
        int vx1 = 9;
        int vz0 = 15;
        int vz1 = 33;
        int ladderX = bx0 + 5;  // neben dem hinteren Wachposten, sonst endete die Leiter unter seinem Boden
        if (Math.max(bx1 + 6 - (bx0 - 6), bz1 + 6 - (bz0 - 10)) + 1 > maxExtent) {
            return Optional.empty();
        }
        JsonArray s = new JsonArray();
        // Untergeschoss (Halle um den Tresor), Decke = Erdgeschossboden
        s.add(box("fill", "air", bx0 + 1, -7, bz0 + 1, bx1 - 1, -2, bz1 - 1));
        s.add(box("hollow", "stone_bricks", bx0, -8, bz0, bx1, -1, bz1));
        // Tresor: Stahl, zwei Geschosse (-13..-9 und -7..-2), Zwischenboden auf Höhe des Untergeschossbodens
        s.add(box("hollow", "iron_block", vx0, -14, vz0, vx1, -1, vz1));
        s.add(box("fill", "air", vx0 + 1, -13, vz0 + 1, vx1 - 1, -2, vz1 - 1));
        s.add(box("fill", "smooth_stone", vx0 + 1, -8, vz0 + 1, vx1 - 1, -8, vz1 - 1));
        // Goldbarren in drei Reihen je Geschoss, Gänge dazwischen und an beiden Enden
        for (int floorY : new int[] {-13, -7}) {
            for (int row = 0; row < 3; row++) {
                int z = vz0 + 3 + row * 5;
                s.add(box("fill", "gold_block", vx0 + 2, floorY, z, vx1 - 2, floorY + 1, z + 2));
            }
        }
        // Gebäude: ausgehöhlt (Gras und Hügel im Innenraum weg), Hülle, Zwischendecke, Brüstung
        s.add(box("fill", "air", bx0 + 1, 0, bz0 + 1, bx1 - 1, 11, bz1 - 1));
        s.add(box("hollow", KNOX_WALL, bx0, -1, bz0, bx1, 12, bz1));
        s.add(box("fill", "smooth_stone", bx0 + 1, 6, bz0 + 1, bx1 - 1, 6, bz1 - 1));
        s.add(box("walls", KNOX_WALL, bx0, 13, bz0, bx1, 13, bz1));
        // Wachposten an den vier Ecken, je ein Sehschlitz nach außen
        int[][] corners = {{bx0, bz0}, {bx1 - 3, bz0}, {bx0, bz1 - 3}, {bx1 - 3, bz1 - 3}};
        for (int[] c : corners) {
            s.add(box("hollow", KNOX_TRIM, c[0], 13, c[1], c[0] + 3, 16, c[1] + 3));
        }
        s.add(set("glass_pane", bx0 + 1, 15, bz0));
        s.add(set("glass_pane", bx1 - 1, 15, bz0));
        s.add(set("glass_pane", bx0 + 1, 15, bz1));
        s.add(set("glass_pane", bx1 - 1, 15, bz1));
        // Eingang vorn in der Mitte, Knopf außen und innen
        s.add(door("iron_door", 0, 0, bz0, "front"));
        s.add(set("stone_button[face=wall,facing=front]", 1, 1, bz0 - 1));
        s.add(set("stone_button[face=wall,facing=back]", 1, 1, bz0 + 1));
        // Schmale hohe Fenster, vorn vier je Geschoss, hinten zwei
        for (int x : new int[] {-12, -7, 6, 11}) {
            s.add(box("line", "glass_pane", x, 1, bz0, x, 4, bz0));
            s.add(box("line", "glass_pane", x, 8, bz0, x, 10, bz0));
        }
        for (int x : new int[] {-7, 6}) {
            s.add(box("line", "glass_pane", x, 1, bz1, x, 4, bz1));
            s.add(box("line", "glass_pane", x, 8, bz1, x, 10, bz1));
        }
        // Licht: in der Zwischendecke und im Dach, im Untergeschoss im Erdgeschossboden, im Tresor in beiden Decken
        s.add(box("line", "sea_lantern", -1, 6, bz0 + 3, -1, 6, bz1 - 3));
        s.add(box("line", "sea_lantern", -1, 12, bz0 + 3, -1, 12, bz1 - 3));
        s.add(box("line", "sea_lantern", bx0 + 3, -1, bz0 + 2, bx0 + 3, -1, bz1 - 2));
        s.add(box("line", "sea_lantern", bx1 - 3, -1, bz0 + 2, bx1 - 3, -1, bz1 - 2));
        s.add(box("line", "sea_lantern", 0, -1, vz0 + 2, 0, -1, vz1 - 2));
        s.add(box("line", "sea_lantern", 0, -8, vz0 + 2, 0, -8, vz1 - 2));
        // Tresortür (Eisen, Knopf außen und innen) in der Vorderwand des Tresors, vom Untergeschoss aus
        s.add(door("iron_door", 0, -7, vz0, "front"));
        s.add(set("stone_button[face=wall,facing=front]", 1, -6, vz0 - 1));
        s.add(set("stone_button[face=wall,facing=back]", 1, -6, vz0 + 1));
        // Leitern: im Tresor vom oberen ins untere Geschoss, im Gebäude vom Untergeschoss bis aufs Dach
        s.add(box("line", "ladder[facing=front]", vx0 + 1, -13, vz1 - 1, vx0 + 1, -8, vz1 - 1));
        s.add(box("line", "ladder[facing=front]", ladderX, -7, bz1 - 1, ladderX, 12, bz1 - 1));
        // Zaun mit Tor, Weg zur Tür, zwei Torposten. Zaun und Posten stecken einen Block im Boden und hängen über den
        // Weg am Gebäude – sonst gälte der Zaun ohne Geländescan als schwebend.
        s.add(box("walls", "iron_bars", bx0 - 6, -1, bz0 - 6, bx1 + 6, 2, bz1 + 6));
        s.add(box("fill", "air", -1, 0, bz0 - 6, 0, 2, bz0 - 6));
        s.add(box("fill", KNOX_TRIM, -2, -1, bz0 - 10, 1, -1, bz0 - 1));
        s.add(box("hollow", KNOX_TRIM, -5, -1, bz0 - 9, -3, 2, bz0 - 7));
        s.add(box("hollow", KNOX_TRIM, 2, -1, bz0 - 9, 4, 2, bz0 - 7));
        s.add(box("fill", "air", -3, 0, bz0 - 8, -3, 1, bz0 - 8));
        s.add(box("fill", "air", 2, 0, bz0 - 8, 2, 1, bz0 - 8));
        return Optional.of(plan("Fort Knox (United States Bullion Depository): granite building 32x37, 13 high, "
                + "corner guard posts, fence with gate posts; vault on two levels below the basement",
                "Fort Knox: Golddepot 32 × 37 m, 13 m hoch, Wachposten an den Ecken, Zaun – darunter der Tresor "
                        + "über zwei Ebenen voller Gold. Eisentüren per Knopf, Leiter hinten links.", s));
    }

    // ---------------------------------------------------------------- allgemeiner Bunker

    static Optional<String> bunker(int levels, int width, int maxExtent) {
        int ux0 = -(width / 2);
        int ux1 = width / 2;
        int uz1 = width - 1;
        int bottom = -2 - LEVEL * levels;
        if (Math.max(width, 5 - bottom) + 1 > maxExtent) {
            return Optional.empty();
        }
        JsonArray s = new JsonArray();
        // Unter der Erde: eine Hülle über alle Ebenen, Oberkante -2 (eine Schicht Erde bleibt darüber)
        s.add(box("fill", "air", ux0 + 1, bottom + 1, 1, ux1 - 1, -3, uz1 - 1));
        s.add(box("hollow", CONCRETE, ux0, bottom, 0, ux1, -2, uz1));
        for (int k = 1; k < levels; k++) {
            int y = -2 - LEVEL * k;
            s.add(box("fill", CONCRETE, ux0 + 1, y, 1, ux1 - 1, y, uz1 - 1));
        }
        for (int k = 0; k < levels; k++) {
            int ceiling = -2 - LEVEL * k;
            int low = ceiling - 5;
            int high = ceiling - 1;
            // Gang in der Mitte, Hallen links und rechts, hinten offen
            s.add(box("fill", "polished_andesite", -4, low, 1, -4, high, uz1 - 5));
            s.add(box("fill", "polished_andesite", 4, low, 1, 4, high, uz1 - 5));
            s.add(box("line", "sea_lantern", 0, ceiling, 2, 0, ceiling, uz1 - 2));
            // Vorräte in der linken Halle, ein Gang an der Trennwand bleibt frei
            s.add(box("fill", "barrel", ux0 + 1, low, 3, -6, low + 1, uz1 - 6));
            if (k == 0) {
                s.add(set("crafting_table", 5, low, 2));
                s.add(set("furnace", 6, low, 2));
            }
            if (k == levels - 1 && width >= 21) {
                // Tresorraum hinten rechts, Tür zur offenen Gangseite
                s.add(box("hollow", "iron_block", 5, low - 1, uz1 - 5, ux1 - 1, ceiling, uz1 - 1));
                s.add(box("fill", "air", 6, low, uz1 - 4, ux1 - 2, high, uz1 - 2));
                s.add(box("fill", "gold_block", 7, low, uz1 - 4, ux1 - 2, low + 1, uz1 - 2));
                s.add(door("iron_door", 5, low, uz1 - 3, "left"));
                s.add(set("stone_button[face=wall,facing=left]", 4, low + 1, uz1 - 4));
                s.add(set("stone_button[face=wall,facing=right]", 6, low + 1, uz1 - 4));
            }
        }
        // Lüftung hinten links von ganz unten bis zwei Blöcke über den Boden
        s.add(box("line", "iron_bars", ux0 + 1, bottom + 1, uz1 - 1, ux0 + 1, 1, uz1 - 1));
        // Eingangsbau, mit Moos getarnt; Panzertür mit Knopf außen und innen
        s.add(box("fill", "air", -3, 0, 1, 3, 3, 7));
        s.add(box("hollow", CONCRETE, -4, -1, 0, 4, 4, 8));
        s.add(box("fill", "moss_block", -4, 5, 0, 4, 5, 8));
        s.add(door("iron_door", 0, 0, 0, "front"));
        s.add(set("stone_button[face=wall,facing=front]", 1, 1, -1));
        s.add(set("stone_button[face=wall,facing=back]", 1, 1, 1));
        // Leiter an der Vorderwand von der untersten Ebene bis in den Eingangsbau
        s.add(box("line", "ladder[facing=back]", -2, bottom + 1, 1, -2, 1, 1));
        String size = levels == 1 ? "eine Ebene" : levels + " Ebenen";
        return Optional.of(plan("underground bunker, " + levels + " level(s) of " + width + "x" + width
                + ", camouflaged entrance with iron door, ladder, storage, vent" + (width >= 21 ? ", vault" : ""),
                "Bunker: getarnter Eingang mit Panzertür (Knopf daneben), darunter " + size + ", " + width + " × "
                        + width + ", Lager, Lüftung" + (width >= 21 ? ", Tresorraum" : "") + ". Leiter links hinter "
                        + "der Tür.", s));
    }

    // ---------------------------------------------------------------- JSON

    /** reply höchstens 200 Zeichen: BuildService.acceptPlan kürzt danach (Texts.clean), mitten im Satz. */
    private static String plan(String design, String reply, JsonArray steps) {
        JsonObject p = new JsonObject();
        p.addProperty("design", design);
        p.addProperty("reply", reply);
        p.add("steps", steps);
        return p.toString();
    }

    private static JsonObject box(String op, String block, int x0, int y0, int z0, int x1, int y1, int z1) {
        JsonObject s = new JsonObject();
        s.addProperty("op", op);
        s.addProperty("block", block);
        s.add("from", vec(x0, y0, z0));
        s.add("to", vec(x1, y1, z1));
        return s;
    }

    private static JsonObject set(String block, int x, int y, int z) {
        JsonObject s = new JsonObject();
        s.addProperty("op", "set");
        s.addProperty("block", block);
        s.add("pos", vec(x, y, z));
        return s;
    }

    private static JsonObject door(String block, int x, int y, int z, String facing) {
        JsonObject s = new JsonObject();
        s.addProperty("op", "door");
        s.addProperty("block", block);
        s.add("pos", vec(x, y, z));
        s.addProperty("facing", facing);
        return s;
    }

    private static JsonArray vec(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }
}
