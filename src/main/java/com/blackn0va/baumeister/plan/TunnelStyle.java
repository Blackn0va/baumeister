package com.blackn0va.baumeister.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Wie ein Tunnel aussieht (Wunsch 2026-11-16: „er nutzt für die Tunnel schöne Lichter, nicht Fackeln, er baut schöne
 * Tunnel“): Wände und Decke ausgemauert, der Boden abgesetzt, oben in den Ecken ein Bogen aus umgedrehten Treppen,
 * alle {@link TunnelShape#LIGHT_EVERY} Abschnitte ein Gurtbogen (rib) mit einer Laterne darunter. Fackeln nie – auch
 * nicht, wenn sie in der Kiste liegen.
 *
 * Wer frei baut (Admin, Kreativmodus), bekommt die Palette der Gegend: über y 0 Steinziegel mit poliertem Andesit,
 * darunter Tiefenschiefer, im Nether poliertes Schwarzgestein mit Seelenlaternen, im End Endsteinziegel mit Purpur.
 * Alle anderen bauen aus ihrer Kiste (Wunsch 2026-11-16): Was dort liegt, bestimmt die Palette ({@link #fromChest}).
 *
 * arch und step sind Treppen-IDs ohne Zustände – Richtung und Hälfte setzt TunnelShape. arch null = kein Bogen, step
 * null = Stufen aus vollen Blöcken (nur ohne Treppen in der Kiste).
 *
 * Brücke (Wunsch 2026-11-16, „eine Brücke, die kein Ende hat und auch über Meere geht“): floor ist das Deck, wall die
 * Pfeiler, rib der Querbalken unter dem Deck an jedem Pfeiler, railing das Geländer (Mauer oder Zaun) mit einer Laterne
 * obenauf. Ein Leuchtblock statt Laterne wird selbst zum Geländerpfosten.
 *
 * Threads: unveränderlich. Fehler: keine (fehlendes Material steht in {@link Choice#missing}).
 */
public record TunnelStyle(String name, BlockSpec wall, BlockSpec floor, BlockSpec rib, BlockSpec arch,
        BlockSpec step, BlockSpec light, BlockSpec chain, BlockSpec railing) {

    /** Welt, in der gegraben wird – ohne Bukkit (World.Environment ordnet TunnelService zu). */
    public enum Realm {
        OVERWORLD, NETHER, END
    }

    /** Laternen, die von der Decke hängen können (hanging=true). Die übrigen Lichter sitzen in der Decke. */
    static final List<String> HANGING_LIGHTS = List.of("lantern", "soul_lantern", "copper_lantern",
            "exposed_copper_lantern", "weathered_copper_lantern", "oxidized_copper_lantern", "waxed_copper_lantern",
            "waxed_exposed_copper_lantern", "waxed_weathered_copper_lantern", "waxed_oxidized_copper_lantern");
    /** Volle Leuchtblöcke: sitzen bündig in der Decke. Kürbislaterne bewusst nicht – sie hat ein Gesicht. */
    static final List<String> BLOCK_LIGHTS = List.of("sea_lantern", "ochre_froglight", "verdant_froglight",
            "pearlescent_froglight", "shroomlight", "glowstone");
    static final List<String> CHAINS = List.of("iron_chain", "copper_chain", "exposed_copper_chain",
            "weathered_copper_chain", "oxidized_copper_chain", "waxed_copper_chain", "waxed_exposed_copper_chain",
            "waxed_weathered_copper_chain", "waxed_oxidized_copper_chain");

    /** Ein Bauwerkstein, aber roh – aus der Kiste nur, wenn kein schönerer daliegt. */
    private static final Set<String> RAW = Set.of("cobblestone", "cobbled_deepslate", "stone", "deepslate", "tuff",
            "andesite", "diorite", "granite", "calcite", "blackstone", "basalt", "netherrack", "end_stone",
            "sandstone", "red_sandstone", "mossy_cobblestone", "packed_mud", "dripstone_block", "terracotta");
    /** Bauwerksteine, die keine der Namensregeln trifft. */
    // Netherstämme ausdrücklich: Die Endung „_stem“ träfe auch Melonen-, Kürbis- und Tropfblattstiele (Pflanzen) –
    // so stand es in der ersten Fassung, gesehen in der Liste aller angenommenen IDs.
    private static final Set<String> WALL_EXTRA = Set.of("bricks", "purpur_block", "purpur_pillar", "quartz_block",
            "quartz_pillar", "prismarine", "dark_prismarine", "obsidian", "amethyst_block", "crimson_stem",
            "warped_stem", "stripped_crimson_stem", "stripped_warped_stem");
    private static final List<String> WALL_ENDS = List.of("_bricks", "_tiles", "_planks", "_concrete",
            "_terracotta", "_log", "_wood", "_hyphae");
    private static final List<String> WALL_STARTS = List.of("polished_", "smooth_", "cut_", "chiseled_");
    /** Formteile, Deko, Lichter, Erze – keine Mauersteine, auch wenn ein Name passt (smooth_stone_slab …). */
    private static final List<String> NOT_WALL_ENDS = List.of("_stairs", "_slab", "_wall", "_fence", "_fence_gate",
            "_door", "_trapdoor", "_button", "_pressure_plate", "_sign", "_pane", "_carpet", "_bed", "_banner",
            "_ore", "_powder", "_shulker_box", "_candle", "_bars", "_chain", "_lantern", "_torch", "_rod", "_head",
            "_skull", "_bulb", "_grate", "_lamp", "_glass");
    private static final Set<String> NOT_WALL = Set.of("chiseled_bookshelf");

    /** Kette erst ab dieser Innenhöhe – darunter hinge die Laterne zu tief. */
    private static final int CHAIN_FROM_HEIGHT = 4;

    /** Tunnel und Treppe: Palette zum Ort, siehe die Fassung mit TunnelOrder.Kind. */
    public static TunnelStyle forPlace(Realm realm, int y, String material, TunnelOrder.Size size) {
        return forPlace(realm, y, material, TunnelOrder.Kind.TUNNEL, size);
    }

    /**
     * Palette zum Ort (frei bauen). material: „aus Tiefenschiefer“ im Auftrag (RequestHints.material) – ersetzt Wand
     * und Boden (Brücke: Deck und Pfeiler), wenn es ein Mauerstein ist; Bogen, Stufen und Geländer dann aus seiner
     * Treppe bzw. Mauer oder Zaun, sonst wie die Gegend.
     */
    public static TunnelStyle forPlace(Realm realm, int y, String material, TunnelOrder.Kind kind,
            TunnelOrder.Size size) {
        boolean bridge = kind == TunnelOrder.Kind.BRIDGE;
        TunnelStyle base = switch (realm) {
            case NETHER -> bridge
                    ? bridgeOf("Schwarzsteinbrücke", "polished_blackstone_bricks", "polished_blackstone_bricks",
                            "chiseled_polished_blackstone", "polished_blackstone_brick_wall", "soul_lantern")
                    : of("Schwarzstein", "polished_blackstone_bricks", "polished_blackstone",
                            "chiseled_polished_blackstone", "polished_blackstone_brick_stairs",
                            "polished_blackstone_stairs", "soul_lantern");
            case END -> bridge
                    ? bridgeOf("Endsteinbrücke", "purpur_pillar", "end_stone_bricks", "purpur_block",
                            "end_stone_brick_wall", "lantern")
                    : of("Endstein", "end_stone_bricks", "purpur_block", "purpur_pillar", "end_stone_brick_stairs",
                            "purpur_stairs", "lantern");
            case OVERWORLD -> y < 0
                    ? bridge
                            ? bridgeOf("Tiefenschieferbrücke", "deepslate_bricks", "deepslate_tiles",
                                    "chiseled_deepslate", "deepslate_brick_wall", "lantern")
                            : of("Tiefenschiefer", "deepslate_bricks", "deepslate_tiles", "polished_deepslate",
                                    "deepslate_brick_stairs", "deepslate_tile_stairs", "lantern")
                    : bridge
                            ? bridgeOf("Steinbrücke", "stone_bricks", "stone_bricks", "chiseled_stone_bricks",
                                    "stone_brick_wall", "lantern")
                            : of("Steinziegel", "stone_bricks", "polished_andesite", "chiseled_stone_bricks",
                                    "stone_brick_stairs", "polished_andesite_stairs", "lantern");
        };
        BlockSpec chain = !bridge && size.height >= CHAIN_FROM_HEIGHT ? BlockSpec.of("iron_chain") : null;
        if (material == null || !isWallBlock(material)) {
            return new TunnelStyle(base.name, base.wall, base.floor, base.rib, base.arch, base.step, base.light, chain,
                    base.railing);
        }
        BlockSpec m = BlockSpec.of(material);
        if (bridge) {
            String railing = railingOf(material, BlockTiers::knownBlock);
            String log = material.endsWith("_planks")
                    ? material.substring(0, material.length() - "_planks".length()) + "_log" : null;
            BlockSpec pillar = log != null && BlockTiers.knownBlock(log) ? BlockSpec.of(log) : m;
            return new TunnelStyle(german(material), pillar, m, base.rib, null, null, base.light, null,
                    railing == null ? base.railing : BlockSpec.of(railing));
        }
        String stairs = stairsOf(material, BlockTiers::knownBlock);
        return new TunnelStyle(german(material), m, m, base.rib, stairs == null ? base.arch : BlockSpec.of(stairs),
                stairs == null ? base.step : BlockSpec.of(stairs), base.light, chain, null);
    }

    private static TunnelStyle of(String name, String wall, String floor, String rib, String arch, String step,
            String light) {
        return new TunnelStyle(name, BlockSpec.of(wall), BlockSpec.of(floor), BlockSpec.of(rib), BlockSpec.of(arch),
                BlockSpec.of(step), BlockSpec.of(light), null, null);
    }

    private static TunnelStyle bridgeOf(String name, String pillar, String deck, String beam, String railing,
            String light) {
        return new TunnelStyle(name, BlockSpec.of(pillar), BlockSpec.of(deck), BlockSpec.of(beam), null, null,
                BlockSpec.of(light), null, BlockSpec.of(railing));
    }

    /** Palette aus der Kiste, oder was fehlt (deutscher Satzteil „Bausteine (z. B. …)“). */
    public record Choice(TunnelStyle style, String missing) {
    }

    /**
     * Aus der Kiste bauen (Wunsch 2026-11-16, „ansonsten braucht er eine Kiste mit Materialien“). Wand: der häufigste
     * schöne Mauerstein, sonst der häufigste rohe (Bruchstein aus dem eigenen Abbau zählt auch). Boden: ein zweiter
     * Stein ab 16 Stück, polierte und Fliesen zuerst; sonst wie die Wand. Gurtbogen: Stamm, Säule oder Gemeißeltes ab
     * 4 Stück, sonst wie die Wand. Bogen nur mit der passenden Treppe; Licht muss sein – hängende Laternen vor
     * Leuchtblöcken, nie Fackeln. Die Mengen prüft der Bau beim Setzen (zahlen je Block), hier geht es nur ums
     * Aussehen.
     *
     * @param items     Gegenstands-ID → Anzahl in der Kiste
     * @param preferred „aus …“ im Auftrag, sonst null – wird genommen, wenn es in der Kiste liegt
     */
    public static Choice fromChest(Map<String, Integer> items, TunnelOrder.Kind kind, TunnelOrder.Size size,
            String preferred) {
        List<String> missing = new ArrayList<>();
        String wall = preferred != null && isWallBlock(preferred) && items.getOrDefault(preferred, 0) > 0 ? preferred
                : best(items, id -> isWallBlock(id) && !RAW.contains(id), 1)
                        .orElseGet(() -> best(items, id -> isWallBlock(id) && RAW.contains(id), 1).orElse(null));
        if (wall == null) {
            missing.add("Bausteine (z. B. Steinziegel, Bruchstein oder Bretter)");
        }
        String light = best(items, HANGING_LIGHTS::contains, 1)
                .orElseGet(() -> best(items, BLOCK_LIGHTS::contains, 1).orElse(null));
        if (light == null) {
            missing.add("Licht (Laternen, Seelenlaternen, Seelaternen oder Froschlichter – Fackeln nehme ich nicht)");
        }
        String step = null;
        if (kind == TunnelOrder.Kind.STAIRS_UP) {
            step = wall == null ? null : stairsIn(items, wall);
            if (step == null) {
                step = best(items, id -> id.endsWith("_stairs"), 1).orElse(null);
            }
            if (step == null) {
                missing.add("Treppenstufen (z. B. Steinziegeltreppen)");
            }
        }
        if (!missing.isEmpty()) {
            return new Choice(null, String.join(" und ", missing));
        }
        if (kind == TunnelOrder.Kind.BRIDGE) {
            // Geländer: passende Mauer bzw. passender Zaun, sonst irgendeiner aus der Kiste, sonst eine Brüstung aus
            // Decksteinen. Pfeiler wie das Deck, Querbalken aus Stamm oder Gemeißeltem, wenn da.
            String fitting = railingOf(wall, id -> items.getOrDefault(id, 0) > 0);
            String railing = fitting != null ? fitting
                    : best(items, id -> id.endsWith("_wall") && !id.endsWith("_sign") || id.endsWith("_fence"), 1)
                            .orElse(wall);
            String w = wall;
            String beam = best(items, id -> isWallBlock(id) && !id.equals(w) && ribbish(id), 4).orElse(wall);
            return new Choice(new TunnelStyle(german(wall), BlockSpec.of(wall), BlockSpec.of(wall), BlockSpec.of(beam),
                    null, null, BlockSpec.of(light), null, BlockSpec.of(railing)), null);
        }
        String w = wall;
        String floor = best(items, id -> isWallBlock(id) && !id.equals(w) && !RAW.contains(id) && floorish(id), 16)
                .orElseGet(() -> best(items, id -> isWallBlock(id) && !id.equals(w) && !RAW.contains(id), 16)
                        .orElse(wall));
        String f = floor;
        String rib = best(items, id -> isWallBlock(id) && !id.equals(w) && !id.equals(f) && ribbish(id), 4)
                .orElse(wall);
        String arch = stairsIn(items, wall);
        String chain = size.height >= CHAIN_FROM_HEIGHT && HANGING_LIGHTS.contains(light)
                ? best(items, CHAINS::contains, 1).orElse(null) : null;
        return new Choice(new TunnelStyle(german(wall), BlockSpec.of(wall), BlockSpec.of(floor), BlockSpec.of(rib),
                arch == null ? null : BlockSpec.of(arch), step == null ? null : BlockSpec.of(step),
                BlockSpec.of(light), chain == null ? null : BlockSpec.of(chain), null), null);
    }

    /**
     * Geländer zum Stein: stone_bricks → stone_brick_wall, cobblestone → cobblestone_wall, oak_planks → oak_fence,
     * quartz_block → quartz_wall (gibt es nicht → null). Nur, was known kennt.
     */
    static String railingOf(String deckId, Predicate<String> known) {
        List<String> candidates = new ArrayList<>(3);
        if (deckId.endsWith("_planks")) {
            candidates.add(deckId.substring(0, deckId.length() - "_planks".length()) + "_fence");
        }
        if (deckId.endsWith("_block")) {
            candidates.add(deckId.substring(0, deckId.length() - "_block".length()) + "_wall");
        }
        if (deckId.endsWith("s")) {
            candidates.add(deckId.substring(0, deckId.length() - 1) + "_wall");
        }
        candidates.add(deckId + "_wall");
        for (String c : candidates) {
            if (known.test(c)) {
                return c;
            }
        }
        return null;
    }

    /** Roher Naturstein (Stein, Tiefenschiefer, Bruchstein …) – ein selbst gegrabener Gang hat keinen eigenen Stil. */
    public static boolean isRaw(String id) {
        return RAW.contains(id);
    }

    /** Hängt das Licht von der Decke (Laterne) oder sitzt es in ihr (Seelaterne, Froschlicht …)? */
    public boolean hangingLight() {
        return HANGING_LIGHTS.contains(light.id());
    }

    /** Alle Block-IDs der Palette – TunnelService prüft sie gegen den laufenden Server. */
    public List<String> ids() {
        List<String> out = new ArrayList<>();
        for (BlockSpec b : new BlockSpec[] {wall, floor, rib, arch, step, light, chain, railing}) {
            if (b != null) {
                out.add(b.id());
            }
        }
        return out;
    }

    /**
     * Ein voller Mauerstein für Wand, Boden oder Gurtbogen? Nach Namensregeln über alle Block-IDs beider Fassungen
     * (tiers/block-ids.txt); TunnelStyleTest listet, was durchkommt. Erze, Formteile (Treppe, Stufe, Mauer …),
     * Lichter und befallene Steine nie.
     */
    public static boolean isWallBlock(String id) {
        if (id == null || !BlockTiers.knownBlock(id) || NOT_WALL.contains(id) || id.startsWith("infested_")) {
            return false;
        }
        for (String end : NOT_WALL_ENDS) {
            if (id.endsWith(end)) {
                return false;
            }
        }
        if (WALL_EXTRA.contains(id) || RAW.contains(id)) {
            return true;
        }
        for (String end : WALL_ENDS) {
            if (id.endsWith(end)) {
                return true;
            }
        }
        for (String start : WALL_STARTS) {
            if (id.startsWith(start)) {
                return true;
            }
        }
        return false;
    }

    /** Treppe zum Stein (stone_bricks → stone_brick_stairs, oak_planks → oak_stairs), die known kennt; sonst null. */
    static String stairsOf(String wallId, Predicate<String> known) {
        List<String> candidates = new ArrayList<>(3);
        if (wallId.endsWith("_planks")) {
            candidates.add(wallId.substring(0, wallId.length() - "_planks".length()) + "_stairs");
        }
        if (wallId.endsWith("_block")) {
            candidates.add(wallId.substring(0, wallId.length() - "_block".length()) + "_stairs");
        }
        if (wallId.endsWith("s")) {
            candidates.add(wallId.substring(0, wallId.length() - 1) + "_stairs");
        }
        candidates.add(wallId + "_stairs");
        for (String c : candidates) {
            if (known.test(c)) {
                return c;
            }
        }
        return null;
    }

    private static String stairsIn(Map<String, Integer> items, String wallId) {
        return stairsOf(wallId, id -> items.getOrDefault(id, 0) > 0);
    }

    private static boolean floorish(String id) {
        return id.startsWith("polished_") || id.startsWith("smooth_") || id.endsWith("_tiles");
    }

    private static boolean ribbish(String id) {
        return id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_pillar") || id.startsWith("chiseled_")
                || id.startsWith("stripped_");
    }

    /** Häufigster Eintrag, der passt und mindestens min Stück hat; bei Gleichstand die kleinere ID (stabil). */
    private static Optional<String> best(Map<String, Integer> items, Predicate<String> fits, int min) {
        return items.entrySet().stream()
                .filter(e -> e.getValue() >= min && fits.test(e.getKey()))
                .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey);
    }

    private static final Map<String, String> GERMAN = Map.ofEntries(
            Map.entry("stone_bricks", "Steinziegel"), Map.entry("deepslate_bricks", "Tiefenschieferziegel"),
            Map.entry("deepslate_tiles", "Tiefenschieferfliesen"),
            Map.entry("polished_deepslate", "polierter Tiefenschiefer"),
            Map.entry("polished_andesite", "polierter Andesit"),
            Map.entry("chiseled_stone_bricks", "gemeißelte Steinziegel"),
            Map.entry("stone_brick_stairs", "Steinziegeltreppen"),
            Map.entry("deepslate_brick_stairs", "Tiefenschieferziegeltreppen"),
            Map.entry("deepslate_tile_stairs", "Tiefenschieferfliesentreppen"),
            Map.entry("polished_andesite_stairs", "polierte Andesittreppen"), Map.entry("lantern", "Laternen"),
            Map.entry("soul_lantern", "Seelenlaternen"), Map.entry("copper_lantern", "Kupferlaternen"),
            Map.entry("iron_chain", "Eisenketten"), Map.entry("sea_lantern", "Seelaternen"),
            Map.entry("glowstone", "Leuchtstein"), Map.entry("shroomlight", "Pilzlichter"),
            Map.entry("ochre_froglight", "Froschlichter"), Map.entry("verdant_froglight", "Froschlichter"),
            Map.entry("pearlescent_froglight", "Froschlichter"),
            Map.entry("polished_blackstone_bricks", "polierte Schwarzsteinziegel"),
            Map.entry("polished_blackstone", "polierter Schwarzstein"),
            Map.entry("chiseled_polished_blackstone", "gemeißelter polierter Schwarzstein"),
            Map.entry("end_stone_bricks", "Endsteinziegel"), Map.entry("purpur_block", "Purpurblöcke"),
            Map.entry("purpur_pillar", "Purpursäulen"), Map.entry("cobblestone", "Bruchstein"),
            Map.entry("cobbled_deepslate", "Tiefenschiefer-Bruchstein"), Map.entry("bricks", "Ziegel"),
            Map.entry("cobblestone_stairs", "Bruchsteintreppen"), Map.entry("stone", "Stein"),
            Map.entry("deepslate", "Tiefenschiefer"), Map.entry("tuff_bricks", "Tuffsteinziegel"),
            Map.entry("smooth_stone", "glatter Stein"), Map.entry("oak_planks", "Eichenbretter"),
            Map.entry("spruce_planks", "Fichtenbretter"));

    /** Deutscher Name für Ansagen; ohne Eintrag ItemNames, sonst die ID mit Leerzeichen. */
    public static String german(String id) {
        String d = GERMAN.get(id);
        if (d != null) {
            return d;
        }
        if (id.endsWith("_stairs")) {
            return "Treppen (" + id.replace('_', ' ') + ")";
        }
        return ItemNames.german(id).orElse(id.replace('_', ' '));
    }
}
