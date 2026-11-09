package com.blackn0va.baumeister;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

/**
 * Werkbank-Rezepte des laufenden Servers als Felder mit erlaubten Zutaten – für Veras Craften (CompanionCraft rechnet
 * damit). Eigene Klasse, damit eine Messprobe (probes/craft) genau diesen Code gegen die echten Rezepte laufen lässt.
 *
 * Nur geformte und ungeformte Werkbank-Rezepte aus Material-Auswahlen; Rezepte mit exakten Gegenständen (Plugins) und
 * alles andere (Ofen, Schmiede, Umwandeln) liefern null.
 *
 * Threads: Hauptthread (Bukkit-Rezepte). Fehler: keine.
 */
final class CraftRecipes {

    /** Ein Rezept: Felder und wie viel eine Herstellung ergibt. */
    record Option(List<Set<String>> slots, int amount) {
    }

    private CraftRecipes() {
    }

    static List<Option> options(Material result) {
        List<Option> out = new ArrayList<>();
        for (Recipe r : Bukkit.getRecipesFor(new ItemStack(result))) {
            List<Set<String>> slots = slots(r);
            if (slots != null && !slots.isEmpty()) {
                out.add(new Option(slots, Math.max(1, r.getResult().getAmount())));
            }
        }
        return out;
    }

    /** Felder eines Werkbank-Rezepts als erlaubte Zutaten; null für andere Rezepte oder exakte Gegenstände. */
    static List<Set<String>> slots(Recipe r) {
        List<RecipeChoice> choices = new ArrayList<>();
        if (r instanceof ShapedRecipe sr) {
            for (String row : sr.getShape()) {
                for (char c : row.toCharArray()) {
                    RecipeChoice ch = sr.getChoiceMap().get(c);
                    if (ch != null) {
                        choices.add(ch);
                    }
                }
            }
        } else if (r instanceof ShapelessRecipe slr) {
            choices.addAll(slr.getChoiceList());
        } else {
            return null;
        }
        List<Set<String>> slots = new ArrayList<>();
        for (RecipeChoice ch : choices) {
            if (!(ch instanceof RecipeChoice.MaterialChoice mc)) {
                return null;
            }
            Set<String> ids = new TreeSet<>();
            for (Material mat : mc.getChoices()) {
                ids.add(mat.getKey().getKey());
            }
            slots.add(ids);
        }
        return slots;
    }
}
