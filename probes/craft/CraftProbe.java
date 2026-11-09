package com.blackn0va.baumeister;

import com.blackn0va.baumeister.companion.CompanionCraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Messprobe Craften (2026-11-07): Liest CraftRecipes genau die Rezepte so, wie CompanionCraft sie braucht? Für zehn
 * Gegenstände Felder und Plan gegen ein festes Inventar; Ergebnis nach plugins/CraftProbe/rezepte.txt, dann Ende.
 * Ohne Baumeister-Jar auf dem Server laufen lassen (gleiche Klassen in zwei Plugins).
 */
public final class CraftProbe extends JavaPlugin {

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::run, 1L);
    }

    private void run() {
        Map<String, Map<String, Integer>> cases = new LinkedHashMap<>();
        cases.put("stick", Map.of("oak_planks", 1, "spruce_planks", 2));
        cases.put("torch", Map.of("coal", 2, "stick", 5));
        cases.put("crafting_table", Map.of("birch_planks", 4));
        cases.put("iron_pickaxe", Map.of("iron_ingot", 3, "stick", 2));
        cases.put("bread", Map.of("wheat", 6));
        cases.put("chest", Map.of("oak_planks", 8));
        cases.put("ladder", Map.of("stick", 7));
        cases.put("bucket", Map.of("iron_ingot", 3));
        cases.put("cake", Map.of("milk_bucket", 3, "sugar", 2, "egg", 1, "wheat", 3));
        cases.put("oak_boat", Map.of("oak_planks", 5));
        List<String> out = new ArrayList<>();
        out.add("server " + Bukkit.getVersion());
        for (Map.Entry<String, Map<String, Integer>> c : cases.entrySet()) {
            Material m = Material.matchMaterial(c.getKey());
            List<CraftRecipes.Option> opts = CraftRecipes.options(m);
            String best = "kein Rezept";
            for (CraftRecipes.Option o : opts) {
                CompanionCraft.Plan p = CompanionCraft.plan(o.slots(), c.getValue(), 2);
                String line = "herstellungen=" + p.crafts() + " je=" + o.amount() + " verbrauch=" + p.use()
                        + " felder=" + o.slots().size() + " erstes_feld=" + first(o.slots());
                if (best.equals("kein Rezept") || p.crafts() > 0) {
                    best = line;
                }
            }
            out.add(String.format("%-15s rezepte=%d %s", c.getKey(), opts.size(), best));
        }
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.write(getDataFolder().toPath().resolve("rezepte.txt"), out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().severe("probe | schreiben_fehlgeschlagen | " + e);
        }
        getLogger().info("probe | fertig");
        Bukkit.shutdown();
    }

    private static String first(List<Set<String>> slots) {
        Set<String> s = slots.getFirst();
        return s.size() <= 3 ? s.toString() : s.size() + " Möglichkeiten";
    }
}
