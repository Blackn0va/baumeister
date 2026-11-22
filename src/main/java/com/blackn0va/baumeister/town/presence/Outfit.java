package com.blackn0va.baumeister.town.presence;

import java.util.Map;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

/**
 * Ausrüstung zum Beruf (Vertrag v013, AK-40): Kopf, Beine, Füße und die zweite Hand. Die Lederjacke in Berufsfarbe
 * setzt weiter TownLife.dress, das Werkzeug in der Haupthand bei der Arbeit TownLife.hold (Roster.Person.tool).
 *
 * Fest je Beruf statt zufällig: Man soll die Berufe von weitem auseinanderhalten – der Schmied in Kettenhose und
 * Eisenstiefeln, der Bauer mit Hut und Saatgut. Leder in der Farbe des Bewohners, eine Spur dunkler als die Jacke.
 * Neuer Beruf im Roster ohne Zeile hier: Lederhose und -stiefel (DEFAULT); OutfitTest verlangt eine Zeile je Beruf.
 *
 * Nur EntityEquipment-Setter, keine Drop-Chancen: Mannequins sind keine Mobs, setXxxDropChance wirft (gemessen
 * 2026-11-21, Gedächtnis „Fallen“).
 *
 * Threads: Hauptthread. Fehler: wirft, was Bukkit wirft; Presence fängt (Schmuck, fail-open).
 */
public final class Outfit {

    /** Material-Namen (Bukkit, klein), null = nichts. */
    public record Pieces(String helmet, String leggings, String boots, String offhand) {
    }

    static final Pieces DEFAULT = new Pieces(null, "leather_leggings", "leather_boots", null);

    static final Map<String, Pieces> BY_JOB = Map.ofEntries(
            Map.entry("Bäckerin", new Pieces(null, "leather_leggings", "leather_boots", null)),
            Map.entry("Schmied", new Pieces(null, "chainmail_leggings", "iron_boots", null)),
            Map.entry("Gärtnerin", new Pieces("leather_helmet", "leather_leggings", "leather_boots", "poppy")),
            Map.entry("Fischer", new Pieces("leather_helmet", "leather_leggings", "leather_boots", "cod")),
            Map.entry("Händlerin", new Pieces(null, "leather_leggings", "leather_boots", "chest")),
            Map.entry("Holzfäller", new Pieces("leather_helmet", "leather_leggings", "leather_boots", null)),
            Map.entry("Bibliothekarin", new Pieces(null, "leather_leggings", "leather_boots", "writable_book")),
            Map.entry("Bauer", new Pieces("leather_helmet", "leather_leggings", "leather_boots", "wheat_seeds")),
            Map.entry("Malerin", new Pieces(null, "leather_leggings", "leather_boots", "yellow_dye")),
            Map.entry("Postbote", new Pieces("leather_helmet", "leather_leggings", "leather_boots", "map")),
            Map.entry("Ärztin", new Pieces(null, "leather_leggings", "leather_boots", null)),
            Map.entry("Musiker", new Pieces(null, "leather_leggings", "leather_boots", "jukebox")));

    private Outfit() {
    }

    public static Pieces of(String job) {
        return BY_JOB.getOrDefault(job, DEFAULT);
    }

    /** Leder eine Spur dunkler als die Jacke (75 %), damit Jacke und Hose nicht zu einem Block verschwimmen. */
    static int darker(int rgb) {
        int r = (rgb >> 16 & 0xFF) * 3 / 4;
        int g = (rgb >> 8 & 0xFF) * 3 / 4;
        int b = (rgb & 0xFF) * 3 / 4;
        return r << 16 | g << 8 | b;
    }

    /** Anziehen; Jacke und Haupthand bleiben, wie TownLife sie gesetzt hat. */
    public static void dress(Mannequin m, String job, int color) {
        EntityEquipment eq = m.getEquipment();
        if (eq == null) {
            return;
        }
        Pieces p = of(job);
        int leather = darker(color);
        ItemStack helmet = item(p.helmet(), leather);
        ItemStack legs = item(p.leggings(), leather);
        ItemStack boots = item(p.boots(), leather);
        ItemStack off = item(p.offhand(), leather);
        if (helmet != null) {
            eq.setHelmet(helmet);
        }
        if (legs != null) {
            eq.setLeggings(legs);
        }
        if (boots != null) {
            eq.setBoots(boots);
        }
        if (off != null) {
            eq.setItemInOffHand(off);
        }
    }

    private static ItemStack item(String name, int leather) {
        Material mat = name == null ? null : Material.matchMaterial(name);
        if (mat == null || !mat.isItem()) {
            return null;
        }
        ItemStack it = new ItemStack(mat);
        if (it.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(Color.fromRGB(leather));
            it.setItemMeta(meta);
        }
        return it;
    }
}
