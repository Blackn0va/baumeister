package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.BlockCatalog;

import java.util.Locale;

import org.bukkit.Material;

/** Kennt jeden Block, den der laufende Server kennt – nicht mehr, nicht weniger. */
final class BukkitBlockCatalog implements BlockCatalog {

    /** Alle Blöcke des Servers; einmal gerechnet, das Register ändert sich im Lauf nicht. */
    static final java.util.Set<String> BLOCKS = ids(true);
    /** Alle Gegenstände (Vera: geben, Rezept). */
    static final java.util.Set<String> ITEMS = ids(false);

    private static java.util.Set<String> ids(boolean blocks) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Material m : Material.values()) {
            if (!m.isLegacy() && !m.isAir() && (blocks ? m.isBlock() : m.isItem())) {
                out.add(m.getKey().getKey());
            }
        }
        return java.util.Set.copyOf(out);
    }

    @Override
    public java.util.Set<String> allIds() {
        return BLOCKS;
    }

    @Override
    public boolean isBlock(String id) {
        // matchMaterial(String) liefert ohne zweiten Parameter keine LEGACY_-Einträge.
        Material m = Material.matchMaterial(id.toUpperCase(Locale.ROOT));
        return m != null && m.isBlock();
    }
}
