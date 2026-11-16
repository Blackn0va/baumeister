package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * Quer über die Karte (Wunsch 2026-11-16): Bretter, Zäune, Stämme, Schienen, Spinnweben und Ketten eines
     * Mineschachts, den die Welt erzeugt hat, sind Natur; was ein Spieler dort gebaut hat und alle anderen Bauwerke
     * bleiben Gebautes.
     */
    @Test
    void onlyAMineshaftsOwnBlocksAreDugThrough() {
        Ground built = Ground.BUILT;
        assertTrue(TunnelNature.diggableStructure("mineshaft"));
        assertTrue(TunnelNature.diggableStructure("mineshaft_mesa"));
        for (String other : List.of("fortress", "stronghold", "ancient_city", "trial_chambers", "village_plains")) {
            assertFalse(TunnelNature.diggableStructure(other), other);
            assertEquals(built, TunnelNature.inStructure(other, "oak_planks", false, built), other);
        }
        // Wandfackel: Vanilla hängt sie an Stützbalken (Paper-Probe 2026-11-16 hielt sonst daran an).
        for (String id : List.of("oak_planks", "oak_fence", "oak_log", "dark_oak_planks", "rail", "cobweb",
                "iron_chain", "wall_torch", "torch")) {
            assertEquals(Ground.SOLID, TunnelNature.inStructure("mineshaft", id, false, built), id);
        }
        assertEquals(Ground.FLUID, TunnelNature.inStructure("mineshaft", "oak_fence", true, built),
                "Wasser im Zaun: zuerst ausräumen");
        for (String id : List.of("glass", "lantern", "chest", "stone_bricks", "spruce_planks", "ladder")) {
            assertEquals(built, TunnelNature.inStructure("mineshaft", id, false, built), id);
        }
        assertEquals(Ground.FORBIDDEN, TunnelNature.inStructure("mineshaft", "spawner", false, Ground.FORBIDDEN));
        assertEquals(built, TunnelNature.inStructure("stronghold", "wall_torch", false, built), "nur im Schacht");
    }

    /** „trotzdem“ nur über Gebautes – Grundgestein, Weltrand und Spawner bleiben tabu. */
    @Test
    void forceTurnsBuiltIntoRockButNothingElse() {
        assertEquals(Ground.SOLID, TunnelNature.forced(Ground.BUILT, true));
        assertEquals(Ground.BUILT, TunnelNature.forced(Ground.BUILT, false));
        assertEquals(Ground.FORBIDDEN, TunnelNature.forced(Ground.FORBIDDEN, true));
        assertEquals(Ground.OPEN, TunnelNature.forced(Ground.OPEN, true));
    }

    /**
     * Wunsch 2026-11-16, „der Tunnel soll bis auf die letzte Schicht Grundgestein gehen … baut er aber auch ab, wenn's
     * im Weg ist“: alles Grundgestein über der untersten Schicht; im Nether nicht das Dach ab y 127.
     */
    @Test
    void bedrockAboveTheLastLayerMayBeDug() {
        assertTrue(TunnelNature.bedrockDiggable("bedrock", -63, -64, false));
        assertTrue(TunnelNature.bedrockDiggable("bedrock", -60, -64, false));
        assertFalse(TunnelNature.bedrockDiggable("bedrock", -64, -64, false), "die letzte Schicht bleibt");
        assertTrue(TunnelNature.bedrockDiggable("bedrock", 1, 0, true));
        assertTrue(TunnelNature.bedrockDiggable("bedrock", 125, 0, true));
        assertFalse(TunnelNature.bedrockDiggable("bedrock", 127, 0, true), "Netherdach");
        assertFalse(TunnelNature.bedrockDiggable("end_portal_frame", -10, -64, false));
    }
}
