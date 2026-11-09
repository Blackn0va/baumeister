package com.blackn0va.baumeister.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Schnellauftrag „bau mir einen Pool“: eingelassenes Becken mit Wasser, ohne LLM.
 *
 * Vorfall dev01 2026-11-04 18:19–19:12: fünf Anläufe über das 14B-Modell. Erst Wasser unter einer Abdeckung
 * („eingemauert“, Reparaturrunde), dann 14 Blöcke Wasser in einer Mulde – „Das ist aber ein komischer Pool“, „Bau den
 * Pool um, es ist kein Pool“, „Der Pool sieht nicht gut aus“ –, zuletzt 547 Blöcke nach einer Überbau-Reparatur. Ein
 * Pool ist Rechteck, Tiefe, Rand, Leiter, Licht: genau das, was der Compiler sicher rechnet.
 *
 * Lage (Plankoordinaten wie im Prompt: x rechts, z vom Spieler weg, y 0 = erste Schicht über dem Boden, y −1 = Boden):
 * Wasseroberfläche auf Bodenhöhe (y −1), darüber frei – sonst meldet der Linter „eingemauert“. Rundum eine Terrasse
 * (2 breit) auf Bodenhöhe, darunter die Beckenwand. Über Becken und Terrasse wird bis y 3 ausgeräumt (Gras, Blumen).
 * Leiter im Wasser an der Vorderwand (wasserdurchlässig gesetzt, sonst entstünde eine Luftblase).
 *
 * Threads: zustandslos. Fehler: keine – passt der Pool nicht in bau.max-ausdehnung, gibt es leer zurück (LLM).
 */
public final class Pool {

    static final Set<String> WORDS = Set.of("pool", "pools", "swimmingpool", "schwimmbecken", "schwimmbad", "freibad",
            "badebecken", "planschbecken", "whirlpool", "infinitypool");
    /** Beschreiben nur, was ohnehin gebaut wird. */
    static final Set<String> MODIFIERS = Set.of("wasser", "leiter", "und", "noch", "schoenen", "schoener", // umlaut-ok
            "schoenes", "schoene", "zum", "schwimmen", "baden", "draussen", "im", "garten", "daneben", "neuen", // umlaut-ok
            "neuer", "neues", "richtigen", "richtiger", "richtiges", "echten", "echter", "echtes", "beleuchtung",
            "licht", "lichtern");

    enum Size {
        SMALL, MEDIUM, LARGE
    }

    private static final String DECK = "smooth_stone";
    private static final String WALL = "white_concrete";
    private static final String FLOOR = "light_blue_concrete";
    private static final String LIGHT = "sea_lantern";

    private Pool() {
    }

    static Optional<Size> parse(List<String> tokens, Set<String> filler) {
        boolean pool = false;
        boolean big = false;
        boolean small = false;
        for (String t : tokens) {
            if (WORDS.contains(t)) {
                pool = true;
            } else if (MachineHall.BIG.contains(t) || t.equals("grosses")) {
                big = true;
            } else if (MachineHall.SMALL.contains(t) || t.equals("kleines")) {
                small = true;
            } else if (!filler.contains(t) && !MODIFIERS.contains(t)) {
                return Optional.empty();
            }
        }
        if (!pool || big && small) {
            return Optional.empty();
        }
        return Optional.of(big ? Size.LARGE : small ? Size.SMALL : Size.MEDIUM);
    }

    /** Innenmaße Breite × Länge × Tiefe je Größe. */
    static int[] dims(Size s) {
        return switch (s) {
            case SMALL -> new int[] {5, 7, 2};
            case MEDIUM -> new int[] {7, 11, 3};
            case LARGE -> new int[] {11, 17, 4};
        };
    }

    static Optional<String> plan(Size size, int maxExtent) {
        int[] d = dims(size);
        int w = d[0];
        int len = d[1];
        int depth = d[2];
        // Wasser x0..x1, z0..z1; Terrasse 2 breit drumherum; Becken beginnt 3 vor dem Spieler (z 3).
        int x0 = -(w / 2);
        int x1 = x0 + w - 1;
        int z0 = 3;
        int z1 = z0 + len - 1;
        if (Math.max(w + 6, len + 6) > maxExtent || depth + 5 > maxExtent) {
            return Optional.empty();
        }
        JsonArray s = new JsonArray();
        // Ausräumen über Terrasse und Becken
        s.add(box("fill", "air", x0 - 3, 0, z0 - 3, x1 + 3, 3, z1 + 3));
        // Terrasse auf Bodenhöhe, Beckenkörper darunter (massiv, wird gleich ausgehöhlt)
        s.add(box("fill", DECK, x0 - 3, -1, z0 - 3, x1 + 3, -1, z1 + 3));
        s.add(box("fill", WALL, x0 - 1, -1 - depth, z0 - 1, x1 + 1, -2, z1 + 1));
        s.add(box("fill", FLOOR, x0, -1 - depth, z0, x1, -1 - depth, z1));
        // Licht im Boden, alle 4 Blöcke in der Mittellinie
        int mid = x0 + w / 2;
        for (int z = z0 + 1; z <= z1 - 1; z += 4) {
            s.add(set(LIGHT, mid, -1 - depth, z));
        }
        // Wasser bis zur Oberfläche auf Bodenhöhe
        s.add(box("fill", "water", x0, -depth, z0, x1, -1, z1));
        // Leiter an der Vorderwand (zum Spieler hin), wasserdurchlässig
        s.add(box("line", "ladder[facing=back,waterlogged=true]", mid, -depth, z0, mid, -1, z0));
        // Laternen auf den vier Terrassenecken
        for (int[] c : new int[][] {{x0 - 3, z0 - 3}, {x1 + 3, z0 - 3}, {x0 - 3, z1 + 3}, {x1 + 3, z1 + 3}}) {
            s.add(set("lantern", c[0], 0, c[1]));
        }
        String what = switch (size) {
            case SMALL -> "Kleiner Pool";
            case MEDIUM -> "Pool";
            case LARGE -> "Großer Pool";
        };
        return Optional.of(plan("swimming pool " + w + "x" + len + ", " + depth + " deep, water at ground level, "
                + "deck " + DECK + ", walls " + WALL + ", floor " + FLOOR + " with lights, ladder front",
                what + ": " + w + " × " + len + ", " + depth + " tief, mit Terrasse, Licht im Boden und Leiter – "
                        + "Badezeit!", s));
    }

    // ---------------------------------------------------------------- JSON

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

    private static JsonArray vec(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }
}
