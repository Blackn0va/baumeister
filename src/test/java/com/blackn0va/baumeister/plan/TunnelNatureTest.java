package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.blackn0va.baumeister.plan.TunnelShape.Ground;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Wodurch der Baumeister gräbt (Natur) und wovor er aufhört (Gebautes) – Wunsch 2026-11-16, schöne Tunnel. */
class TunnelNatureTest {

    @Test
    void rockOreAndCaveGrowthAreNatureEverythingBuiltStopsHim() {
        for (String id : List.of("stone", "deepslate", "tuff", "granite", "gravel", "sand", "dirt",
                "deepslate_diamond_ore", "iron_ore", "ancient_debris", "calcite", "dripstone_block", "sculk",
                "raw_iron_block", "sulfur", "cinnabar", "netherrack", "end_stone", "obsidian")) {
            assertEquals(Ground.SOLID, TunnelNature.of(id, false, false), id);
        }
        for (String id : List.of("air", "cave_air", "glow_lichen", "pointed_dripstone", "short_grass", "oak_leaves",
                "amethyst_cluster", "moss_carpet", "snow", "sulfur_spike")) {
            assertEquals(Ground.OPEN, TunnelNature.of(id, false, false), id);
        }
        for (String id : List.of("water", "lava", "bubble_column", "seagrass", "kelp_plant")) {
            assertEquals(Ground.FLUID, TunnelNature.of(id, false, false), id);
        }
        assertEquals(Ground.FLUID, TunnelNature.of("pointed_dripstone", true, false), "Tropfstein unter Wasser");
        for (String id : List.of("oak_planks", "cobblestone", "stone_bricks", "torch", "wall_torch", "rail", "cobweb",
                "chest", "oak_log", "mossy_cobblestone", "glass", "suspicious_sand", "dirt_path", "farmland")) {
            assertEquals(Ground.BUILT, TunnelNature.of(id, false, false), id);
        }
        assertEquals(Ground.BUILT, TunnelNature.of("oak_planks", true, false), "Gesetztes mit Wasser bleibt Gebautes");
        for (String id : List.of("bedrock", "reinforced_deepslate", "end_portal_frame", "spawner", "barrier")) {
            assertEquals(Ground.FORBIDDEN, TunnelNature.of(id, false, false), id);
        }
        assertEquals(Ground.BUILT, TunnelNature.of("glowstone", false, false), "im Oberland gesetzt");
        assertEquals(Ground.SOLID, TunnelNature.of("glowstone", false, true), "im Nether Natur");
    }
}
