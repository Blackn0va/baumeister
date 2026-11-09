package com.blackn0va.baumeister.companion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Craften aus dem eigenen Inventar (Wunsch 2026-11-07):
 * „Vera, crafte mir 4 Fackeln“ – sie stellt her, was ein Spieler an der Werkbank könnte, und nimmt genau die Zutaten
 * dafür aus seinem Inventar. Kein Vervielfältiger: Ohne Zutaten nichts (im Kreativmodus gibt sie wie bei „gib mir“).
 *
 * Hier nur die Rechnung ohne Bukkit: Je Feld des Rezepts die erlaubten Zutaten (Rezept-Auswahl, z. B. jede Planke für
 * Stöcke), dazu der Bestand. Ein Feld nach dem anderen, das engste zuerst, und je Feld die Zutat mit dem größten Rest
 * – so bleiben bei gemischten Planken die seltenen übrig, und ein Feld mit nur einer erlaubten Zutat bekommt sie
 * sicher. CompanionService liest Rezepte und Inventar und setzt das Ergebnis um.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class CompanionCraft {

    /** Wie oft hergestellt werden kann (≤ gewünscht) und was dafür verbraucht wird. */
    public record Plan(int crafts, Map<String, Integer> use) {
    }

    /** Werkzeug- und Rüstungsfamilien: „Spitzhacke“ ohne Material → die beste, die die Zutaten hergeben. */
    static final List<String> MATERIALS = List.of("diamond", "iron", "golden", "copper", "stone", "wooden",
            "chainmail", "leather");
    private static final List<String> FAMILY = List.of("_pickaxe", "_axe", "_shovel", "_hoe", "_sword", "_helmet",
            "_chestplate", "_leggings", "_boots");

    private CompanionCraft() {
    }

    /** Höchstens times-mal; je Herstellung jedes Feld genau eine Zutat. */
    public static Plan plan(List<Set<String>> slots, Map<String, Integer> inventory, int times) {
        Map<String, Integer> left = new HashMap<>(inventory);
        Map<String, Integer> use = new LinkedHashMap<>();
        int crafts = 0;
        List<Set<String>> order = new ArrayList<>(slots);
        order.sort(Comparator.comparingInt(Set::size));
        while (crafts < times) {
            Map<String, Integer> round = new HashMap<>();
            boolean ok = true;
            for (Set<String> slot : order) {
                String pick = best(slot, left, round);
                if (pick == null) {
                    ok = false;
                    break;
                }
                round.merge(pick, 1, Integer::sum);
            }
            if (!ok) {
                break;
            }
            round.forEach((k, n) -> {
                left.merge(k, -n, Integer::sum);
                use.merge(k, n, Integer::sum);
            });
            crafts++;
        }
        return new Plan(crafts, use);
    }

    /** Felder, die schon für eine Herstellung fehlen – für die Antwort („mir fehlt …“). */
    public static List<Set<String>> missing(List<Set<String>> slots, Map<String, Integer> inventory) {
        Map<String, Integer> round = new HashMap<>();
        List<Set<String>> out = new ArrayList<>();
        List<Set<String>> order = new ArrayList<>(slots);
        order.sort(Comparator.comparingInt(Set::size));
        for (Set<String> slot : order) {
            String pick = best(slot, inventory, round);
            if (pick == null) {
                out.add(slot);
            } else {
                round.merge(pick, 1, Integer::sum);
            }
        }
        return out;
    }

    private static String best(Set<String> slot, Map<String, Integer> left, Map<String, Integer> round) {
        String pick = null;
        int most = 0;
        for (String id : slot) {
            int have = left.getOrDefault(id, 0) - round.getOrDefault(id, 0);
            if (have > most || have == most && have > 0 && pick != null && id.compareTo(pick) < 0) {
                most = have;
                pick = id;
            }
        }
        return most > 0 ? pick : null;
    }

    /**
     * Was probiert wird: „Spitzhacke“ (aufgelöst zu diamond_pickaxe) ohne genanntes Material → alle Familien von der
     * besten abwärts; mit Material („Eisenspitzhacke“) nur genau das.
     */
    public static List<String> variants(String id, boolean materialNamed) {
        if (materialNamed) {
            return List.of(id);
        }
        for (String suffix : FAMILY) {
            if (id.endsWith(suffix)) {
                List<String> out = new ArrayList<>();
                for (String m : MATERIALS) {
                    out.add(m + suffix);
                }
                return out;
            }
        }
        return List.of(id);
    }

    /** Nennt der Wortlaut ein Material („Eisen“, „Diamant“, „Holz“ …)? Normalisiert, Umlaute ausgeschrieben. */
    public static boolean namesMaterial(String words) {
        String t = words == null ? "" : words.toLowerCase(java.util.Locale.ROOT).replace("ä", "ae").replace("ö", "oe")
                .replace("ü", "ue");
        for (String w : List.of("diamant", "eisen", "gold", "kupfer", "stein", "holz", "ketten", "leder", "netherit",
                "diamond", "iron", "golden", "copper", "stone", "wooden", "wood", "chain", "leather", "netherite")) {
            if (t.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
