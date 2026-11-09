package com.blackn0va.baumeister.companion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rezepte als gesprochener Satz – aus den Rezepten des Servers (CompanionService.recipe), nicht aus dem Gedächtnis des
 * Modells. Wunsch 2026-11-04. Ein 14B rät Rezepte oft falsch; der Server
 * kennt jedes Rezept seiner Version exakt.
 *
 * Rein, ohne Bukkit: Zutaten kommen als Namen (Block-IDs) an, ein Slot mit mehreren erlaubten Zutaten schon
 * zusammengefasst (choiceName).
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class RecipeText {

    private RecipeText() {
    }

    /** Geformtes Rezept: rows wie ShapedRecipe.getShape() („PPP“, „CIC“), key: Zeichen → Zutat; ' ' = frei. */
    public static String shaped(String result, int amount, List<String> rows, Map<Character, String> key) {
        Map<String, Integer> count = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        String[] names = rowNames(rows.size());
        for (int r = 0; r < rows.size(); r++) {
            List<String> slots = new ArrayList<>();
            for (char c : rows.get(r).toCharArray()) {
                String ing = key.get(c);
                if (c == ' ' || ing == null) {
                    slots.add("frei");
                } else {
                    slots.add(pretty(ing));
                    count.merge(pretty(ing), 1, Integer::sum);
                }
            }
            lines.add((names[r].isEmpty() ? "" : names[r] + ": ") + String.join(", ", slots));
        }
        return "Für " + amount + " " + pretty(result) + " brauchst du " + list(count) + ". Leg sie so in die "
                + "Werkbank – " + String.join("; ", lines) + ".";
    }

    public static String shapeless(String result, int amount, List<String> ingredients) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (String i : ingredients) {
            count.merge(pretty(i), 1, Integer::sum);
        }
        return "Für " + amount + " " + pretty(result) + " brauchst du " + list(count) + ", egal in welcher Anordnung.";
    }

    /** Ofen, Schmelzofen, Räucherofen, Lagerfeuer oder Steinsäge. */
    public static String processed(String result, int amount, String input, String station) {
        return "Für " + amount + " " + pretty(result) + " legst du " + pretty(input) + " in " + station + ".";
    }

    public static String uncraftable(String item) {
        return pretty(item) + " kann man nicht herstellen – das findet, erbeutet oder ertauscht man.";
    }

    /** Mehrere erlaubte Zutaten in einem Slot: „beliebige planks“ statt einer langen Liste. */
    public static String choiceName(List<String> ids) {
        if (ids.isEmpty()) {
            return "?";
        }
        if (ids.size() == 1) {
            return ids.getFirst();
        }
        String first = ids.getFirst();
        int us = first.lastIndexOf('_');
        if (us >= 0) {
            String suffix = first.substring(us);
            if (ids.stream().allMatch(i -> i.endsWith(suffix))) {
                return "beliebige " + suffix.substring(1);
            }
        }
        return first + " oder Ähnliches";
    }

    static String pretty(String id) {
        String s = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        return s.replace('_', ' ');
    }

    private static String[] rowNames(int n) {
        return switch (n) {
            case 1 -> new String[] {""};
            case 2 -> new String[] {"oben", "unten"};
            default -> new String[] {"oben", "Mitte", "unten"};
        };
    }

    private static String list(Map<String, Integer> count) {
        List<String> parts = new ArrayList<>();
        count.forEach((k, v) -> parts.add(v + " " + k));
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " und " + parts.getLast();
    }
}
