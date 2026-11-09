package com.blackn0va.baumeister.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Plot-Modus: „bau das World Trade Center auf meinem Plot“ – der ganze PlotSquared-Plot ist der Bauplatz. Wunsch
 * 2026-10-31. Vorher baute der Baumeister immer
 * mittig vor den Spieler; auf einer Plotwelt lag ein großer Bau dann halb auf der Straße oder dem Nachbarplot, und
 * PlotSquared lehnte schon in der Prüfphase ab.
 *
 * Hier steht nur Rechnung (ohne Bukkit, testbar): Ausrichtung des Plots zur Blickrichtung, ob ein Bau passt, wohin er
 * kommt, und die Gestaltung drumherum – Rasen, Hecke, Weg mit Laternen zur Front, Blumen, Azaleen in den Ecken. Den
 * Hauptbau liefert wie sonst das LLM, die Recherche oder ein Schnellauftrag; er wird mittig auf den Plot gesetzt und
 * NACH der Gestaltung gebaut (er gewinnt jede Zelle, die beide setzen).
 *
 * Plotkoordinaten wie im Prompt: x nach rechts, z vom Spieler weg, y 0 = erste Schicht über dem Boden; (0,0,0) ist die
 * vordere linke Ecke des Plots aus Sicht des Spielers.
 *
 * Threads: zustandslos. Fehler: keine – was nicht passt, meldet {@link #problem}.
 */
public final class PlotLayout {

    /** Gestaltung (bis ~25 Schritte) plus ein LLM-Plan (bis PlanParser.MAX_STEPS). */
    public static final int MAX_STEPS = 100;
    /** Rand, den der Plan dem Hauptbau empfiehlt: Hecke, Blumen, ein Block Luft. */
    public static final int MARGIN = 3;

    private static final Pattern PHRASE = Pattern.compile(
            "\\s*,?\\s*\\b(?:(?:hier\\s+)?(?:auf|für|fuer|über|ueber|den|das)\\s+)?"  // umlaut-ok: Eingabe ohne Umlaute
                    + "(?:(?:dem|den|das|meinem|meinen|mein|meiner|diesem|diesen|unserem|unseren|unser)\\s+)?"
                    // „das ganze Grundstück“ (Akkusativ) wie „dem ganzen Grundstück“ – vorher nur -en (PlotLayoutTest).
                    + "(?:ganzen?\\s+|kompletten?\\s+|gesamten?\\s+)?"
                    + "(?:plot|plots|grundstück|grundstueck|grundstücks|parzelle)\\b"  // umlaut-ok
                    + "(?:\\s+(?:komplett|ganz))?"
                    // „…, auf dem ich stehe“ gehört zur Plot-Angabe, nicht zum Bauwerk
                    + "(?:\\s*,?\\s*(?:auf\\s+dem|wo)\\s+ich\\s+(?:gerade\\s+)?stehe)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    /** Plotrechteck in Weltkoordinaten (beide Ecken eingeschlossen). */
    public record Rect(int minX, int minZ, int maxX, int maxZ) {
    }

    /**
     * Ursprung (cornerX/cornerZ in der Welt) und Maße in Plotkoordinaten: width entlang „rechts“, depth entlang
     * „vorwärts“.
     */
    public record Frame(int cornerX, int cornerZ, int width, int depth) {
    }

    private PlotLayout() {
    }

    /** Nennt der Auftrag den Plot? „auf meinem Plot“, „den ganzen Plot“, „Grundstück“, „Parzelle“. */
    public static boolean mentioned(String prompt) {
        return prompt != null && PHRASE.matcher(prompt).find();
    }

    /** Auftrag ohne die Plot-Angabe – fürs LLM, die Recherche und die Schnellaufträge. */
    public static String strip(String prompt) {
        if (prompt == null) {
            return null;
        }
        String s = PHRASE.matcher(prompt).replaceAll("").replaceAll("\\s{2,}", " ").trim();
        return s.isEmpty() ? prompt.trim() : s;
    }

    /**
     * Die Plotecke, von der aus „rechts“ und „vorwärts“ über den ganzen Plot laufen. Vorwärts ist die Blickrichtung
     * des Spielers – er sieht den Plot also so, wie das LLM ihn beschrieben bekommt.
     */
    public static Frame frame(Rect plot, Orientation o) {
        Pos right = o.toWorldOffset(new Pos(1, 0, 0));
        Pos forward = o.toWorldOffset(new Pos(0, 0, 1));
        int x = right.x() + forward.x() > 0 ? plot.minX() : plot.maxX();
        int z = right.z() + forward.z() > 0 ? plot.minZ() : plot.maxZ();
        int sizeX = plot.maxX() - plot.minX() + 1;
        int sizeZ = plot.maxZ() - plot.minZ() + 1;
        return new Frame(x, z, right.x() != 0 ? sizeX : sizeZ, forward.x() != 0 ? sizeX : sizeZ);
    }

    /** Grundfläche, die der Hauptbau höchstens haben darf: eine Hecke rundum muss noch passen. */
    public static Optional<String> problem(CompiledBuild build, int width, int depth) {
        if (build.actions().isEmpty()) {
            return Optional.empty();
        }
        int w = build.max().x() - build.min().x() + 1;
        int d = build.max().z() - build.min().z() + 1;
        if (w <= width - 2 && d <= depth - 2) {
            return Optional.empty();
        }
        return Optional.of("The plot is only " + width + " x " + depth + " blocks, but the plan is " + w + " x " + d
                + ". Keep the whole build within " + (width - 2 * MARGIN) + " x " + (depth - 2 * MARGIN)
                + " blocks (the server adds lawn, hedge and paths around it).");
    }

    /** Verschiebung (dx, dz) in Plotkoordinaten, die den Hauptbau mittig auf den Plot setzt. */
    public static int[] centerShift(CompiledBuild build, int width, int depth) {
        int w = build.max().x() - build.min().x() + 1;
        int d = build.max().z() - build.min().z() + 1;
        return new int[] {(width - w) / 2 - build.min().x(), (depth - d) / 2 - build.min().z()};
    }

    /** Englischer Zusatz für die LLM-Nachricht. */
    public static String hint(int width, int depth) {
        return "\n\nPLOT: You build on the player's whole plot, " + width + " x " + depth + " blocks. Keep the main "
                + "building within " + (width - 2 * MARGIN) + " x " + (depth - 2 * MARGIN) + " blocks. The server "
                + "centres it on the plot and adds lawn, hedge, a path with lanterns and flowers around it - do not "
                + "build those yourself. Make the building as tall and detailed as the order asks.";
    }

    /**
     * Gestaltung um den Hauptbau (Plotkoordinaten), b = Grundriss des Hauptbaus NACH {@link #centerShift}. Was keinen
     * Platz hat, entfällt: Blumen nur, wo zwischen Hecke und Bau mindestens zwei Blöcke frei sind, der Weg nur, wenn
     * vor dem Bau Platz ist.
     */
    public static JsonArray landscape(int width, int depth, int bx0, int bz0, int bx1, int bz1) {
        int w1 = width - 1;
        int d1 = depth - 1;
        JsonArray s = new JsonArray();
        s.add(box("fill", "air", 0, 0, 0, w1, 2, d1));
        s.add(box("fill", "grass_block", 0, -1, 0, w1, -1, d1));
        s.add(box("walls", "oak_leaves[persistent=true]", 0, 0, 0, w1, 0, d1));
        int cx = Math.max(2, Math.min(width - 3, (bx0 + bx1) / 2));
        // Eingang in der Hecke und Weg zur Front des Hauptbaus
        s.add(box("fill", "air", cx - 1, 0, 0, cx + 1, 0, 0));
        if (bz0 >= 1) {
            s.add(box("fill", "dirt_path", cx - 1, -1, 0, cx + 1, -1, bz0 - 1));
            for (int z = 1; z < bz0 - 1; z += 3) {
                s.add(set("lantern", cx - 2, 0, z));
                s.add(set("lantern", cx + 2, 0, z));
            }
        }
        int left = bx0;
        int right = w1 - bx1;
        int front = bz0;
        int back = d1 - bz1;
        if (back >= 3) {
            s.add(box("line", "poppy", 2, 0, d1 - 1, w1 - 2, 0, d1 - 1));
        }
        if (left >= 3) {
            s.add(box("line", "cornflower", 1, 0, 2, 1, 0, d1 - 2));
        }
        if (right >= 3) {
            s.add(box("line", "dandelion", w1 - 1, 0, 2, w1 - 1, 0, d1 - 2));
        }
        if (front >= 3) {
            if (cx - 3 >= 2) {
                s.add(box("line", "allium", 2, 0, 1, cx - 3, 0, 1));
            }
            if (cx + 3 <= w1 - 2) {
                s.add(box("line", "allium", cx + 3, 0, 1, w1 - 2, 0, 1));
            }
        }
        if (Math.min(Math.min(left, right), Math.min(front, back)) >= 2) {
            s.add(set("flowering_azalea", 1, 0, 1));
            s.add(set("flowering_azalea", w1 - 1, 0, 1));
            s.add(set("flowering_azalea", 1, 0, d1 - 1));
            s.add(set("flowering_azalea", w1 - 1, 0, d1 - 1));
        }
        return s;
    }

    /** Gestaltung zuerst, dann der (schon verschobene) Hauptbau – er überschreibt, wo beide setzen. */
    public static String combine(String movedBuildingJson, JsonArray landscape) {
        JsonObject root = JsonParser.parseString(movedBuildingJson).getAsJsonObject();
        JsonArray steps = new JsonArray();
        landscape.forEach(steps::add);
        if (root.has("steps")) {
            root.getAsJsonArray("steps").forEach(steps::add);
        }
        root.add("steps", steps);
        return root.toString();
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
