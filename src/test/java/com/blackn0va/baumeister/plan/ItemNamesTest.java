package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class ItemNamesTest {

    /**
     * Alle Namen der Spigot-API. isItem/isBlock brauchen ohne Server ein Register (NPE), daher die Namen selbst; im
     * Plugin trennt BukkitBlockCatalog sauber. Für die Blöcke fehlt hier nur, was in diesen Tests zählt: „redstone“
     * ist ein Gegenstand, der Block heißt redstone_wire.
     */
    static final Set<String> ITEMS = ids();
    static final Set<String> BLOCKS = blocks();

    private static Set<String> ids() {
        Set<String> out = new HashSet<>();
        for (Material m : Material.values()) {
            if (!m.isLegacy() && !m.name().equals("AIR")) {
                out.add(m.name().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return out;
    }

    private static Set<String> blocks() {
        Set<String> out = new HashSet<>(ids());
        out.remove("redstone");
        return out;
    }

    @Test
    void incidentNamesResolve() {
        // dev01 2026-11-04 16:24: „Das kenne ich leider nicht: redstone_comparator“.
        assertEquals(Optional.of("comparator"), ItemNames.resolve("redstone_comparator", ITEMS));
        assertEquals(Optional.of("repeater"), ItemNames.resolve("Redstone Repeater", ITEMS));
        assertEquals(Optional.of("redstone"), ItemNames.resolve("redstone dust", ITEMS));
    }

    @Test
    void germanAndSloppyNames() {
        Map<String, String> cases = Map.of("Fackeln", "torch", "Brot", "bread", "minecraft:oak_plank", "oak_planks",
                "Diamant Spitzhacke", "diamond_pickaxe", "pickaxe diamond", "diamond_pickaxe", "Verstärker", "repeater",
                "cobblestones", "cobblestone", "diamnd_sword", "diamond_sword", "Werkbank", "crafting_table",
                "golden apple", "golden_apple");
        cases.forEach((in, want) -> assertEquals(Optional.of(want), ItemNames.resolve(in, ITEMS), in));
    }

    @Test
    void everyRegistryIdResolvesToItself() {
        for (String id : ITEMS) {
            assertEquals(Optional.of(id), ItemNames.resolve(id, ITEMS), id);
        }
    }

    @Test
    void aliasesPointToRealIds() {
        for (Map.Entry<String, String> e : ItemNames.ALIASES.entrySet()) {
            assertTrue(ITEMS.contains(e.getValue()) || BLOCKS.contains(e.getValue()),
                    "Alias zeigt ins Leere: " + e.getKey() + " → " + e.getValue());
        }
    }

    @Test
    void nonsenseAndAmbiguousGiveNothing() {
        for (String s : List.of("", "xyzzy", "haus", "blau")) {
            assertTrue(ItemNames.resolve(s, ITEMS).isEmpty(), s);
        }
    }

    @Test
    void redstoneAsBlockIsTheWireNotStone() {
        // dev01 2026-11-04 16:10: „fill redstone“ wurde Ersatzblock Stein (2 431 Steine).
        List<String> warnings = new java.util.ArrayList<>();
        BlockCatalog cat = new BlockCatalog() {
            @Override
            public boolean isBlock(String id) {
                return BLOCKS.contains(id);
            }

            @Override
            public Set<String> allIds() {
                return BLOCKS;
            }
        };
        assertEquals("redstone_wire", BlockRules.resolve(BlockSpec.of("redstone"), cat, "stone", warnings)
                .orElseThrow().id());
        assertEquals("comparator", BlockRules.resolve(BlockSpec.of("redstone_comparator"), cat, "stone", warnings)
                .orElseThrow().id());
        assertEquals("oak_sign", BlockRules.resolve(BlockSpec.of("sign"), cat, "stone", warnings).orElseThrow().id());
        assertEquals("stone_bricks", BlockRules.resolve(BlockSpec.of("stone_brikcs"), cat, "stone", warnings)
                .orElseThrow().id(), "Tippfehler über das ganze Register statt Ersatzblock");
        // dev01 2026-11-04 17:16: Wände aus Frosteis schmelzen weg.
        assertEquals("packed_ice", BlockRules.resolve(BlockSpec.of("frosted_ice"), cat, "stone", warnings)
                .orElseThrow().id());
    }

    /** Echte Wünsche aus dem Log von dev01, die am Nachschlagen scheiterten und ans Modell gingen. */
    @Test
    void strictResolveKnowsWhatPlayersSaid() {
        assertEquals(Optional.of("diamond_sword"), ItemNames.resolveStrict("diicken schwert", ITEMS));
        assertEquals(Optional.of("diamond_sword"), ItemNames.resolveStrict("ein dickes Schwert", ITEMS));
        assertEquals(Optional.of("tnt"), ItemNames.resolveStrict("Dynamit", ITEMS));
        assertEquals(Optional.of("elytra"), ItemNames.resolveStrict("Elytrin", ITEMS));
        assertEquals(Optional.of("sponge"), ItemNames.resolveStrict("Schwämme", ITEMS));
        assertEquals(Optional.of("bread"), ItemNames.resolveStrict("Brott", ITEMS), "vertippt");
    }

    @Test
    void strictResolveSkipsTypoGuesses() {
        // Weg ohne LLM (CompanionChat.giveOrder): nur sichere Treffer.
        assertEquals(Optional.of("elytra"), ItemNames.resolveStrict("Elytren", ITEMS));
        assertEquals(Optional.of("elytra"), ItemNames.resolveStrict("Elitren", ITEMS));
        assertEquals(Optional.of("bread"), ItemNames.resolveStrict("Brot", ITEMS));
        assertEquals(Optional.of("torch"), ItemNames.resolveStrict("Fackeln", ITEMS));
        assertEquals(Optional.empty(), ItemNames.resolveStrict("Bescheid", ITEMS));
        assertEquals(Optional.empty(), ItemNames.resolveStrict("einen Tipp", ITEMS));
        // resolve mit Tippfehler-Nähe bleibt für die Modell-Aktion give.
        assertTrue(ItemNames.resolve("diamnd", ITEMS).isPresent());
        assertTrue(ItemNames.resolveStrict("diamnd", ITEMS).isEmpty());
    }

    /** Deutsche Namen für Veras Antworten beim Craften (2026-11-07). */
    @Test
    void germanNamesForCraftReplies() {
        assertEquals(Optional.of("Fackeln"), ItemNames.german("torch"));
        assertEquals(Optional.of("Eisenspitzhacke"), ItemNames.german("iron_pickaxe"));
        assertEquals(Optional.of("Holzschwert"), ItemNames.german("wooden_sword"));
        assertEquals(Optional.of("Planken"), ItemNames.german("spruce_planks"));
        assertEquals(Optional.empty(), ItemNames.german("sculk_catalyst"));
        // ITEMS: Namen der API ohne Register (isItem bräuchte einen Server).
        assertEquals(Optional.of("stick"), ItemNames.resolveStrict("Stöcke", ITEMS));
        assertEquals(Optional.of("crafting_table"), ItemNames.resolveStrict("Werkbank", ITEMS));
    }
}
