package com.blackn0va.baumeister.plan;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Wie Namensfehler des LLM aufgefangen werden – und welche Blöcke der Baumeister nie VON SICH AUS wählt.
 *
 * Seit 2026-11-08 (Plan B1) sperrt diese Klasse nichts mehr beim Übersetzen: Was ein Plan ausdrücklich nennt, bleibt
 * im Bau, und erst beim Setzen entscheidet die Stufe des Auftraggebers (BlockTiers, BuildJob), ob der Block kommt. Die
 * frühere Sperrliste prüfte nur IDs, ließ Luft immer durch (Grundgestein abtragen) und kannte keine Blockdaten.
 */
public final class BlockRules {

    /**
     * Nie automatisch gewählt: Zubehör, das der Compiler selbst aussucht (firstKnown), die Namenssuche für unbekannte
     * LLM-Namen und das Lehrset des eigenen Modells (PlanDecompiler). Das sind die Blöcke über der Stufe „für alle“
     * (BlockTiers) sowie Betten, Seelenanker und hohe Pflanzen (zweiteilig bzw. weltabhängig). Ein Tippfehler des
     * Modells soll nie zu Lava oder einem Befehlsblock werden, auch nicht bei einem Admin.
     */
    private static final Set<String> RESTRICTED_EXTRA = Set.of("respawn_anchor", "tall_grass", "large_fern",
            "sunflower", "lilac", "rose_bush", "peony", "tall_seagrass", "pitcher_plant", "small_dripleaf",
            "big_dripleaf_stem");

    /**
     * Erwartete (nicht gemessene) Fehlgriffe kleiner Modelle: generische Namen ohne Holzart.
     * Neue Einträge erst, wenn sie in plugins/Baumeister/pläne/ tatsächlich auftauchen.
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("planks", "oak_planks"),
            Map.entry("wood_planks", "oak_planks"),
            Map.entry("wooden_planks", "oak_planks"),
            Map.entry("wood_plank", "oak_planks"),
            Map.entry("log", "oak_log"),
            Map.entry("wood_log", "oak_log"),
            Map.entry("wooden_log", "oak_log"),
            Map.entry("door", "oak_door"),
            Map.entry("wooden_door", "oak_door"),
            Map.entry("wood_door", "oak_door"),
            Map.entry("stairs", "oak_stairs"),
            Map.entry("wooden_stairs", "oak_stairs"),
            Map.entry("wood_stairs", "oak_stairs"),
            Map.entry("slab", "oak_slab"),
            Map.entry("wooden_slab", "oak_slab"),
            Map.entry("fence", "oak_fence"),
            Map.entry("wooden_fence", "oak_fence"),
            Map.entry("leaves", "oak_leaves"),
            Map.entry("window", "glass_pane"),
            Map.entry("glass_window", "glass_pane"),
            Map.entry("stone_brick", "stone_bricks"),
            Map.entry("brick", "bricks"),
            Map.entry("brick_block", "bricks"),
            Map.entry("wool", "white_wool"),
            Map.entry("concrete", "white_concrete"),
            Map.entry("grass", "grass_block"),
            Map.entry("lamp", "lantern"),
            Map.entry("cobble", "cobblestone"),
            // „redstone“ ist der Gegenstand, als Block heißt der Staub redstone_wire. Vorher wurde daraus der
            // Ersatzblock Stein (dev01 2026-11-04 16:10, 2 431 Steine statt Leitungen).
            Map.entry("redstone", "redstone_wire"),
            Map.entry("redstone_dust", "redstone_wire"),
            Map.entry("sign", "oak_sign"),
            Map.entry("wall_sign", "oak_wall_sign"),
            Map.entry("button", "stone_button"),
            Map.entry("pressure_plate", "stone_pressure_plate"),
            Map.entry("redstone_comparator", "comparator"),
            Map.entry("redstone_repeater", "repeater"));

    /**
     * Gültige Blöcke, die im fertigen Bau von selbst verschwinden. Frosteis schmilzt nach Sekunden zu Wasser
     * (dev01 2026-11-04 17:16: „Fort Knox“ mit Wänden aus frosted_ice, 460 Blöcke). Wer das zurückdreht, baut wieder
     * Wände, die zerfließen.
     */
    private static final Map<String, String> UNSTABLE = Map.of("frosted_ice", "packed_ice");

    private BlockRules() {
    }

    /** Nie automatisch gewählt (siehe RESTRICTED_EXTRA); gesetzt wird er nur, wenn ein Plan ihn ausdrücklich nennt. */
    public static boolean isRestricted(String id) {
        return BlockTiers.COMMAND_BLOCKS.contains(id) || BlockTiers.DANGEROUS_BLOCKS.contains(id)
                || RESTRICTED_EXTRA.contains(id) || id.endsWith("_bed");
    }

    /**
     * Löst einen LLM-Block gegen den Server auf. Bekannt → unverändert (auch Lava oder Befehlsblock: die Stufe prüft
     * BuildJob beim Setzen). Unbekannt → Alias, dann Plural/Singular, dann Ersatzblock; jeweils mit Hinweis – die
     * Ersatzwege landen nie auf einem eingeschränkten Block (isRestricted).
     */
    public static Optional<BlockSpec> resolve(BlockSpec spec, BlockCatalog catalog, String fallbackId,
            List<String> warnings) {
        if (spec.isAir()) {
            return Optional.of(BlockSpec.of("air"));
        }
        String stable = UNSTABLE.get(spec.id());
        if (stable != null && catalog.isBlock(stable)) {
            warnings.add("Block " + spec.id() + " als " + stable + " gebaut (schmilzt sonst)");
            return Optional.of(spec.withId(stable));
        }
        if (catalog.isBlock(spec.id())) {
            return Optional.of(spec);
        }
        for (String candidate : candidates(spec.id())) {
            if (!isRestricted(candidate) && catalog.isBlock(candidate)) {
                warnings.add("Block " + spec.id() + " als " + candidate + " gelesen");
                return Optional.of(spec.withId(candidate));
            }
        }
        // Das ganze Register des Servers durchsuchen (ItemNames), erst dann der Ersatzblock.
        Optional<String> near = ItemNames.resolve(spec.id(), catalog.allIds());
        if (near.isPresent() && !isRestricted(near.get())) {
            warnings.add("Block " + spec.id() + " als " + near.get() + " gelesen");
            return Optional.of(spec.withId(near.get()));
        }
        warnings.add("Unbekannter Block " + spec.id() + " → " + fallbackId);
        return Optional.of(BlockSpec.of(fallbackId));
    }

    private static List<String> candidates(String id) {
        String alias = ALIASES.get(id);
        String plural = id + "s";
        String singular = id.endsWith("s") ? id.substring(0, id.length() - 1) : id;
        if (alias != null) {
            return List.of(alias, plural, singular);
        }
        return List.of(plural, singular, "oak_" + id);
    }
}
