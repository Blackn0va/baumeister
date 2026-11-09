package com.blackn0va.baumeister.plan;

import java.util.Set;

/** Feste Blockliste statt Material-Enum – der Kern soll ohne laufenden Server testbar bleiben. */
final class TestCatalog implements BlockCatalog {

    static final Set<String> KNOWN = Set.of(
            "air", "stone", "cobblestone", "oak_planks", "spruce_planks", "oak_log", "oak_door", "spruce_door",
            "glass_pane", "glass", "oak_stairs", "oak_slab", "dark_oak_stairs", "stone_bricks", "cobblestone_wall",
            "lantern", "torch", "oak_leaves", "oak_fence", "bricks", "white_wool", "tnt", "lava", "red_bed",
            "red_wool", "brick_stairs", "brick_slab", "smooth_stone", "light_gray_concrete",
            "light_blue_stained_glass", "polished_andesite", "sea_lantern", "ladder", "iron_bars",
            // Haus (op house): Holzart Fichte samt Zubehör, Einrichtung, Weg aus dem Prompt-Beispiel
            "spruce_log", "spruce_stairs", "spruce_slab", "spruce_fence", "spruce_pressure_plate",
            "crafting_table", "chest", "furnace", "bookshelf", "red_carpet", "dirt_path",
            "lever", "redstone_wire", "redstone_wall_torch", "redstone_torch", "repeater", "redstone_lamp");

    @Override
    public boolean isBlock(String id) {
        return KNOWN.contains(id);
    }

    static PlanCompiler compiler() {
        return new PlanCompiler(new TestCatalog(), "stone", new PlanCompiler.Limits(6000, 48));
    }
}
