package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Craften aus dem eigenen Inventar (2026-11-07): Zutaten zuteilen, Grenzen, Fehlendes, Werkzeugfamilien. */
class CompanionCraftTest {

    private static final Set<String> PLANKS = Set.of("oak_planks", "spruce_planks", "birch_planks");
    /** Stöcke: zwei Planken übereinander, jede Holzart (Rezept-Auswahl). */
    private static final List<Set<String>> STICKS = List.of(PLANKS, PLANKS);
    /** Fackeln: Kohle oder Holzkohle auf einem Stock. */
    private static final List<Set<String>> TORCH = List.of(Set.of("coal", "charcoal"), Set.of("stick"));

    @Test
    void takesExactlyWhatTheCraftsNeedAndMixesPlanks() {
        CompanionCraft.Plan p = CompanionCraft.plan(STICKS, Map.of("oak_planks", 1, "spruce_planks", 3), 2);
        assertEquals(2, p.crafts());
        assertEquals(4, p.use().values().stream().mapToInt(Integer::intValue).sum(), "2 Herstellungen × 2 Planken");
        assertTrue(p.use().getOrDefault("spruce_planks", 0) >= p.use().getOrDefault("oak_planks", 0),
                "vom größten Vorrat zuerst");
    }

    @Test
    void stopsWhereTheIngredientsEnd() {
        CompanionCraft.Plan p = CompanionCraft.plan(TORCH, Map.of("coal", 3, "stick", 2), 5);
        assertEquals(2, p.crafts(), "zwei Stöcke → zwei Herstellungen, nicht fünf");
        assertEquals(Map.of("coal", 2, "stick", 2), p.use());
        assertEquals(0, CompanionCraft.plan(TORCH, Map.of("coal", 3), 1).crafts(), "ohne Stock nichts");
    }

    /** Ein Gegenstand füllt nur ein Feld: eine Planke ist kein Stock-Rezept, sechs Stöcke keine Leiter (7). */
    @Test
    void oneItemNeverFillsTwoSlots() {
        assertEquals(0, CompanionCraft.plan(STICKS, Map.of("oak_planks", 1), 1).crafts());
        List<Set<String>> ladder = java.util.Collections.nCopies(7, Set.of("stick"));
        assertEquals(0, CompanionCraft.plan(ladder, Map.of("stick", 6), 1).crafts());
        assertEquals(1, CompanionCraft.plan(ladder, Map.of("stick", 7), 3).crafts());
    }

    @Test
    void namesWhatIsMissing() {
        List<Set<String>> missing = CompanionCraft.missing(TORCH, Map.of("charcoal", 1));
        assertEquals(List.of(Set.of("stick")), missing);
        assertTrue(CompanionCraft.missing(TORCH, Map.of("coal", 1, "stick", 1)).isEmpty());
    }

    @Test
    void plainPickaxeTriesTheBestFamilyFirst() {
        List<String> v = CompanionCraft.variants("diamond_pickaxe", false);
        assertEquals("diamond_pickaxe", v.getFirst());
        assertTrue(v.indexOf("iron_pickaxe") < v.indexOf("stone_pickaxe"));
        assertTrue(v.contains("wooden_pickaxe"));
        assertEquals(List.of("iron_pickaxe"), CompanionCraft.variants("iron_pickaxe", true));
        assertEquals(List.of("torch"), CompanionCraft.variants("torch", false));
        assertTrue(CompanionCraft.namesMaterial("Eisenspitzhacke"));
        assertFalse(CompanionCraft.namesMaterial("Spitzhacke"));
    }
}
