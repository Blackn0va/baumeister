package com.blackn0va.baumeister.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Welche Stufe ({@link Tier}) ein Block samt Zustand und Daten braucht (Plan B1, Nutzer 2026-11-08).
 *
 * Freigabeliste statt Sperrliste: Vorher sperrte BlockRules eine Handvoll IDs, alles andere ging durch – auch jeder
 * Block, den eine neue Minecraft-Fassung mitbringt, und Luft über Grundgestein. Jetzt gilt:
 * <ul>
 *   <li>Block-IDs: nur die geprüften (tiers/block-ids.txt aus dem Datengenerator von 1.21.11 und 26.2) sind für alle;
 *       die Listen unten heben einzelne auf „gefährlich“ oder „Befehle“. Eine ID außerhalb der Liste braucht die
 *       höchste Stufe (fail-closed, ASVS V4.1.3).</li>
 *   <li>Blockdaten (NBT): je Block-Entity-Art eine Freigabeliste von Schlüsseln. Unbekannte Schlüssel, unbekannte
 *       Formen und Blöcke ohne bekannte Block-Entity → höchste Stufe.</li>
 *   <li>Texte: nur reiner Text. Klick-, Hover-, Insertion-, nbt-, score- und selector-Bestandteile → Befehle – auch
 *       als altes JSON in einer Zeichenkette.</li>
 *   <li>Gegenstände: Menge, Haltbarkeit, reiner Name, Verzauberungen bis zum Vanilla-Höchstwert u. a. → Inhalte;
 *       custom_data, entity_data, can_place_on, Attribute, verschachtelte Behälter … → Befehle; Gegenstand-IDs gestuft
 *       wie Blöcke (Befehlsblock → Befehle, Spawn-Ei/Lavaeimer/TNT → gefährlich).</li>
 *   <li>Abreißen und Überschreiben: {@link #removal} – Grundgestein, Barriere, Spawner … nur mit „gefährlich“,
 *       Befehls- und Konstruktionsblöcke nur mit „Befehle“. Luft ist kein Freibrief mehr.</li>
 * </ul>
 *
 * Entscheidungen (Plan ließ Raum, 2026-11-08): Bienenstock ohne Bienen ist für alle (keine Entity-Daten), mit Bienen
 * Befehle (Bienen tragen Entity-Daten samt Passengers). Kopf mit Name/UUID für alle, mit eigener Textur (properties)
 * Inhalte. Lava, Feuer, Wasser und TNT abzutragen ist für alle – nur Blöcke, die im Überleben niemand entfernen kann
 * (und Spawner), verlangen beim Überschreiben eine Stufe.
 *
 * Threads: zustandslos; die Freigabelisten werden beim ersten Zugriff einmal gelesen. Fehler: fehlende
 * Freigabeliste → UncheckedIOException (Plugin-Paket kaputt, nichts darf dann als geprüft gelten).
 */
public final class BlockTiers {

    /** Was die Welt an Stufen beeinflusst: Betten und Seelenanker explodieren, wo sie nicht „funktionieren“. */
    public record WorldFacts(boolean bedWorks, boolean anchorWorks) {

        /** Unbekannte Welt: Betten und Anker als gefährlich (fail-closed). */
        public static final WorldFacts UNKNOWN = new WorldFacts(false, false);
        public static final WorldFacts OVERWORLD = new WorldFacts(true, false);
        public static final WorldFacts NETHER = new WorldFacts(false, true);
    }

    /** Stufe und Grund (deutsch, für „Hinweis: … braucht das Recht …“). */
    public record Verdict(Tier tier, String reason) {

        public static final Verdict EVERYONE = new Verdict(Tier.EVERYONE, "");

        public Verdict max(Verdict other) {
            return other.tier.ordinal() > tier.ordinal() ? other : this;
        }
    }

    // ---------------------------------------------------------------- Blöcke

    /** Befehls-, Konstruktions- und Testblöcke: führen Befehle aus oder bauen ganze Strukturen. */
    static final Set<String> COMMAND_BLOCKS = Set.of("command_block", "chain_command_block",
            "repeating_command_block", "structure_block", "jigsaw", "test_block", "test_instance_block");

    /**
     * Gefährlich oder im Überleben nicht erhältlich (Admin-Blöcke). Aus der Blockliste von 26.2 nach Art geprüft
     * (definition.type im Bericht blocks.json): fire, soul_fire, liquid (lava), tnt, spawner, trial_spawner, vault,
     * barrier, light, structure_void, nether_portal, end_portal, end_portal_frame, end_gateway, moving_piston,
     * piston_head, infested, infested_rotated_pillar, wither_skull, wither_wall_skull, dragon_egg, creaking_heart,
     * lava_cauldron, frogspawn (laicht Kaulquappen, nicht abbaubar) und potent_sulfur (neu in 26.2, Verhalten mit
     * „erupting“-Zuständen nicht geprüft – bis dahin gefährlich).
     */
    static final Set<String> DANGEROUS_BLOCKS = Set.of("lava", "fire", "soul_fire", "tnt", "spawner",
            "trial_spawner", "vault", "bedrock", "reinforced_deepslate", "barrier", "light", "structure_void",
            "nether_portal", "end_portal", "end_portal_frame", "end_gateway", "moving_piston", "piston_head",
            "infested_stone", "infested_cobblestone", "infested_stone_bricks", "infested_mossy_stone_bricks",
            "infested_cracked_stone_bricks", "infested_chiseled_stone_bricks", "infested_deepslate",
            "wither_skeleton_skull", "wither_skeleton_wall_skull", "dragon_egg", "creaking_heart", "lava_cauldron",
            "frogspawn", "potent_sulfur");

    /** Beim Überschreiben „gefährlich“: Was ein Spieler im Überleben nicht entfernen kann, dazu Spawner/Tresor. */
    static final Set<String> REMOVAL_DANGEROUS = Set.of("bedrock", "reinforced_deepslate", "barrier", "light",
            "structure_void", "end_portal", "end_portal_frame", "end_gateway", "spawner", "trial_spawner", "vault",
            "moving_piston");

    // ---------------------------------------------------------------- Gegenstände

    static final Set<String> COMMAND_ITEMS = Set.of("command_block", "chain_command_block",
            "repeating_command_block", "command_block_minecart", "structure_block", "jigsaw", "test_block",
            "test_instance_block", "debug_stick");

    static final Set<String> DANGEROUS_ITEMS = Set.of("lava_bucket", "tnt", "tnt_minecart", "end_crystal",
            "flint_and_steel", "fire_charge", "dragon_egg", "trial_key", "ominous_trial_key", "spawner",
            "trial_spawner", "vault", "bedrock", "barrier", "light", "structure_void", "reinforced_deepslate",
            "end_portal_frame", "wither_skeleton_skull", "creaking_heart", "potent_sulfur", "infested_stone",
            "infested_cobblestone", "infested_stone_bricks", "infested_mossy_stone_bricks",
            "infested_cracked_stone_bricks", "infested_chiseled_stone_bricks", "infested_deepslate");

    /** Höchststufen der Verzauberungen, aus dem Datengenerator (data/minecraft/enchantment, 1.21.11 = 26.2). */
    static final Map<String, Integer> MAX_ENCHANTMENT = Map.ofEntries(Map.entry("aqua_affinity", 1),
            Map.entry("bane_of_arthropods", 5), Map.entry("binding_curse", 1), Map.entry("blast_protection", 4),
            Map.entry("breach", 4), Map.entry("channeling", 1), Map.entry("density", 5), Map.entry("depth_strider", 3),
            Map.entry("efficiency", 5), Map.entry("feather_falling", 4), Map.entry("fire_aspect", 2),
            Map.entry("fire_protection", 4), Map.entry("flame", 1), Map.entry("fortune", 3),
            Map.entry("frost_walker", 2), Map.entry("impaling", 5), Map.entry("infinity", 1), Map.entry("knockback", 2),
            Map.entry("looting", 3), Map.entry("loyalty", 3), Map.entry("luck_of_the_sea", 3), Map.entry("lunge", 3),
            Map.entry("lure", 3), Map.entry("mending", 1), Map.entry("multishot", 1), Map.entry("piercing", 4),
            Map.entry("power", 5), Map.entry("projectile_protection", 4), Map.entry("protection", 4),
            Map.entry("punch", 2), Map.entry("quick_charge", 3), Map.entry("respiration", 3), Map.entry("riptide", 3),
            Map.entry("sharpness", 5), Map.entry("silk_touch", 1), Map.entry("smite", 5), Map.entry("soul_speed", 3),
            Map.entry("sweeping_edge", 3), Map.entry("swift_sneak", 3), Map.entry("thorns", 3),
            Map.entry("unbreaking", 3), Map.entry("vanishing_curse", 1), Map.entry("wind_burst", 3));

    /** Texte: erlaubte Schlüssel einer Text-Komponente (reiner Text samt Farbe/Format, Übersetzung). */
    private static final Set<String> TEXT_KEYS = Set.of("text", "type", "color", "font", "bold", "italic",
            "underlined", "strikethrough", "obfuscated", "shadow_color", "extra", "translate", "with", "fallback");

    private static volatile Set<String> knownBlocks;
    private static volatile Set<String> knownItems;

    private BlockTiers() {
    }

    // ---------------------------------------------------------------- Einstieg

    /** Stufe eines Blocks samt Zustand und Daten, so wie er gesetzt werden soll. */
    public static Verdict place(BlockSpec spec, WorldFacts facts) {
        Verdict v = state(spec.id(), spec.props(), facts);
        if (spec.nbt() != null && !spec.nbt().entries().isEmpty()) {
            v = v.max(nbt(spec.id(), spec.nbt()));
        }
        return v;
    }

    /** Stufe allein aus ID und Zustand. */
    public static Verdict state(String id, Map<String, String> props, WorldFacts facts) {
        if (isAir(id)) {
            return Verdict.EVERYONE;
        }
        if (!knownBlocks().contains(id)) {
            return new Verdict(Tier.COMMANDS, "ungeprüfter Block " + id);
        }
        if (COMMAND_BLOCKS.contains(id)) {
            return new Verdict(Tier.COMMANDS, id);
        }
        if (DANGEROUS_BLOCKS.contains(id)) {
            return new Verdict(Tier.DANGEROUS, id);
        }
        if (id.endsWith("_bed") && !facts.bedWorks()) {
            return new Verdict(Tier.DANGEROUS, id + " (explodiert in dieser Welt)");
        }
        if (id.equals("respawn_anchor") && !facts.anchorWorks()) {
            return new Verdict(Tier.DANGEROUS, "respawn_anchor (explodiert in dieser Welt)");
        }
        if (id.equals("sculk_shrieker") && "true".equals(props.get("can_summon"))) {
            return new Verdict(Tier.DANGEROUS, "sculk_shrieker[can_summon=true] (ruft den Wärter)");
        }
        return Verdict.EVERYONE;
    }

    /**
     * Stufe, um einen vorhandenen Block abzureißen oder zu überschreiben. id ohne „minecraft:“. Unbekannte Blöcke
     * (neue Fassung) → höchste Stufe.
     */
    public static Verdict removal(String id) {
        if (isAir(id)) {
            return Verdict.EVERYONE;
        }
        if (!knownBlocks().contains(id)) {
            return new Verdict(Tier.COMMANDS, "ungeprüfter Block " + id + " (überschreiben)");
        }
        if (COMMAND_BLOCKS.contains(id)) {
            return new Verdict(Tier.COMMANDS, id + " (überschreiben)");
        }
        if (REMOVAL_DANGEROUS.contains(id)) {
            return new Verdict(Tier.DANGEROUS, id + " (überschreiben)");
        }
        return Verdict.EVERYONE;
    }

    /** Ist die ID geprüft (steht in der Freigabeliste)? */
    public static boolean knownBlock(String id) {
        return isAir(id) || knownBlocks().contains(id);
    }

    static boolean isAir(String id) {
        return id.equals("air") || id.equals("cave_air") || id.equals("void_air");
    }

    // ---------------------------------------------------------------- Blockdaten

    /** Stufe der Blockdaten eines Blocks (Schlüssel id, x, y, z sind vorher entfernt, siehe BlockSpec). */
    public static Verdict nbt(String blockId, Nbt.Compound tag) {
        String kind = kind(blockId);
        Verdict v = Verdict.EVERYONE;
        for (Map.Entry<String, Nbt> e : tag.entries().entrySet()) {
            v = v.max(entry(kind, e.getKey(), e.getValue()));
            if (v.tier() == Tier.COMMANDS) {
                return v;
            }
        }
        return v;
    }

    /** Block-Entity-Art nach ID; null = keine bekannte (dann sind alle Daten unbekannt). */
    static String kind(String id) {
        if (COMMAND_BLOCKS.contains(id) && id.endsWith("command_block")) {
            return "command_block";
        }
        if (COMMAND_BLOCKS.contains(id) || Set.of("spawner", "trial_spawner", "vault", "end_gateway", "end_portal")
                .contains(id)) {
            return "admin";
        }
        if (id.endsWith("_sign")) {
            return "sign";
        }
        if (id.endsWith("_banner")) {
            return "banner";
        }
        if (id.endsWith("_skull") || id.endsWith("_head") && !id.equals("piston_head")) {
            return "skull";
        }
        if (id.endsWith("shulker_box") || id.endsWith("copper_chest") || Set.of("chest", "trapped_chest", "barrel",
                "dispenser", "dropper", "hopper", "crafter").contains(id)) {
            return "container";
        }
        if (id.endsWith("campfire")) {
            return "campfire";
        }
        if (id.endsWith("_shelf")) {
            return "shelf";
        }
        if (id.startsWith("suspicious_")) {
            return "brushable";
        }
        return switch (id) {
            case "furnace", "blast_furnace", "smoker" -> "furnace";
            case "brewing_stand" -> "brewing_stand";
            case "chiseled_bookshelf" -> "chiseled_bookshelf";
            case "decorated_pot" -> "decorated_pot";
            case "lectern" -> "lectern";
            case "jukebox" -> "jukebox";
            case "beehive", "bee_nest" -> "beehive";
            case "beacon" -> "beacon";
            case "enchanting_table" -> "named";
            default -> null;
        };
    }

    private static Verdict entry(String kind, String key, Nbt value) {
        if (kind == null) {
            return new Verdict(Tier.COMMANDS, "Blockdaten an einem Block ohne geprüfte Daten (" + key + ")");
        }
        if (kind.equals("command_block")) {
            return new Verdict(Tier.COMMANDS, "Befehlsblock-Daten");
        }
        if (kind.equals("admin")) {
            return new Verdict(Tier.COMMANDS, "Daten eines Admin-Blocks (" + key + ")");
        }
        switch (key) {
            case "CustomName", "custom_name" -> {
                return text(value);
            }
            case "components" -> {
                return components(value);
            }
            case "Lock", "lock" -> {
                return new Verdict(Tier.COMMANDS, "Schloss am Block");
            }
            case "LootTable", "LootTableSeed" -> {
                return new Verdict(Tier.COMMANDS, "Beutetabelle");
            }
            default -> {
                // je Art unten
            }
        }
        Verdict v = switch (kind) {
            case "sign" -> switch (key) {
                case "front_text", "back_text" -> signText(value);
                case "is_waxed" -> number(value, Tier.EVERYONE);
                default -> null;
            };
            case "banner" -> key.equals("patterns") ? bannerPatterns(value) : null;
            case "skull" -> switch (key) {
                case "profile" -> profile(value);
                case "note_block_sound" -> value instanceof Nbt.Str ? new Verdict(Tier.CONTENTS, "Kopf mit Klang")
                        : null;
                default -> null;
            };
            case "container" -> switch (key) {
                case "Items" -> items(value);
                case "TransferCooldown", "crafting_ticks_remaining", "triggered" -> number(value, Tier.CONTENTS);
                case "disabled_slots" -> value instanceof Nbt.Arr ? new Verdict(Tier.CONTENTS, "Werkbankfelder")
                        : null;
                default -> null;
            };
            case "furnace" -> switch (key) {
                case "Items" -> items(value);
                case "BurnTime", "CookTime", "CookTimeTotal", "lit_time_remaining", "cooking_time_spent",
                        "cooking_total_time", "lit_total_time" -> number(value, Tier.CONTENTS);
                // Rezepte im Ofen schütten beim Herausnehmen Erfahrung aus – ein Erfahrungs-Vervielfältiger.
                case "RecipesUsed" -> new Verdict(Tier.COMMANDS, "Ofen-Erfahrung (RecipesUsed)");
                default -> null;
            };
            case "brewing_stand" -> switch (key) {
                case "Items" -> items(value);
                case "BrewTime", "Fuel" -> number(value, Tier.CONTENTS);
                default -> null;
            };
            case "chiseled_bookshelf" -> switch (key) {
                case "Items" -> items(value);
                case "last_interacted_slot" -> number(value, Tier.CONTENTS);
                default -> null;
            };
            case "shelf" -> switch (key) {
                case "Items" -> items(value);
                case "align_items_to_bottom" -> number(value, Tier.CONTENTS);
                default -> null;
            };
            case "decorated_pot" -> switch (key) {
                case "sherds" -> sherds(value);
                case "item" -> stack(value, false);
                default -> null;
            };
            case "lectern" -> switch (key) {
                case "Book" -> stack(value, false);
                case "Page" -> number(value, Tier.CONTENTS);
                default -> null;
            };
            case "jukebox" -> switch (key) {
                case "RecordItem" -> stack(value, false);
                case "ticks_since_song_started" -> number(value, Tier.CONTENTS);
                default -> null;
            };
            case "campfire" -> switch (key) {
                case "Items" -> items(value);
                case "CookingTimes", "CookingTotalTimes", "cooking_times", "cooking_total_times" ->
                        value instanceof Nbt.Arr ? new Verdict(Tier.CONTENTS, "Garzeiten") : null;
                default -> null;
            };
            case "beehive" -> switch (key) {
                // Bienen sind Entities samt beliebiger Daten (auch Passengers) – nur leer für alle.
                case "bees" -> value instanceof Nbt.ListTag l && l.items().isEmpty() ? Verdict.EVERYONE
                        : new Verdict(Tier.COMMANDS, "Bienen (Entity-Daten)");
                case "flower_pos", "FlowerPos" -> value instanceof Nbt.Arr || value instanceof Nbt.ListTag
                        ? Verdict.EVERYONE : null;
                default -> null;
            };
            case "brushable" -> switch (key) {
                case "item" -> stack(value, false);
                case "hit_direction" -> number(value, Tier.EVERYONE);
                default -> null;
            };
            case "beacon" -> switch (key) {
                case "primary_effect", "secondary_effect" -> value instanceof Nbt.Str
                        ? new Verdict(Tier.CONTENTS, "Leuchtfeuer-Wirkung") : null;
                case "Levels" -> number(value, Tier.CONTENTS);
                default -> null;
            };
            default -> null;
        };
        return v != null ? v : new Verdict(Tier.COMMANDS, "ungeprüfter Schlüssel " + key);
    }

    private static Verdict number(Nbt value, Tier tier) {
        return value instanceof Nbt.Num ? new Verdict(tier, tier == Tier.EVERYONE ? "" : "Zahlenwert") : null;
    }

    // ---------------------------------------------------------------- Texte

    /** Text-Komponente (seit 1.21.5 als NBT; als Zeichenkette auch altes JSON, siehe legacyJson). */
    static Verdict text(Nbt value) {
        return switch (value) {
            case Nbt.Str s -> legacyJson(s.value());
            case Nbt.ListTag l -> {
                Verdict v = Verdict.EVERYONE;
                for (Nbt item : l.items()) {
                    v = v.max(text(item));
                }
                yield v;
            }
            case Nbt.Compound c -> {
                Verdict v = Verdict.EVERYONE;
                for (Map.Entry<String, Nbt> e : c.entries().entrySet()) {
                    v = v.max(textEntry(e.getKey(), e.getValue()));
                }
                yield v;
            }
            default -> new Verdict(Tier.COMMANDS, "Text in unbekannter Form");
        };
    }

    private static Verdict textEntry(String key, Nbt value) {
        if (!TEXT_KEYS.contains(key)) {
            return new Verdict(Tier.COMMANDS, textReason(key));
        }
        return switch (key) {
            case "extra", "with" -> value instanceof Nbt.ListTag ? text(value)
                    : new Verdict(Tier.COMMANDS, "Text in unbekannter Form");
            // type: nur Text und Übersetzung – nbt, score, selector, keybind, object rechnet der Server aus.
            case "type" -> value instanceof Nbt.Str s && (s.value().equals("text") || s.value().equals("translatable"))
                    ? Verdict.EVERYONE : new Verdict(Tier.COMMANDS, "Text der Art " + Snbt.write(value));
            case "text", "translate", "fallback", "color", "font" -> value instanceof Nbt.Str ? Verdict.EVERYONE
                    : new Verdict(Tier.COMMANDS, "Text in unbekannter Form");
            // Farbe des Schattens als Zahl oder [r,g,b,a]; bold, italic, … nur als Zahl (Befund 12: vorher jede Liste).
            case "shadow_color" -> value instanceof Nbt.Num || value instanceof Nbt.ListTag l && l.items().size() == 4
                    && l.items().stream().allMatch(Nbt.Num.class::isInstance) ? Verdict.EVERYONE
                    : new Verdict(Tier.COMMANDS, "Textschatten in unbekannter Form");
            default -> value instanceof Nbt.Num ? Verdict.EVERYONE
                    : new Verdict(Tier.COMMANDS, "Text in unbekannter Form");
        };
    }

    private static String textReason(String key) {
        return switch (key) {
            case "click_event", "clickEvent" -> "Klick-Aktion im Text";
            case "hover_event", "hoverEvent" -> "Hover-Text";
            case "insertion" -> "Einfügetext (insertion)";
            case "nbt", "block", "entity", "storage", "source", "interpret" -> "NBT-Text";
            case "score" -> "Punktestand-Text (score)";
            case "selector", "separator" -> "Selektor-Text";
            default -> "ungeprüfter Text-Schlüssel " + key;
        };
    }

    /**
     * Zeichenkette als Text: seit 1.21.5 reiner Text. Sieht sie aus wie das JSON älterer Fassungen ({…} oder […]),
     * wird es wie eine Komponente geprüft – eine ältere oder anders konfigurierte Serverfassung könnte es auswerten.
     */
    static Verdict legacyJson(String s) {
        String t = s.trim();
        if (!t.startsWith("{") && !t.startsWith("[")) {
            return Verdict.EVERYONE;
        }
        // Vor dem Parsen die Tiefe: Gson bricht ab 255 Ebenen mit JsonParseException ab, und die galt unten als „kein
        // JSON“ – ein clickEvent in 254 verschachtelten Feldern (622 Zeichen, passt ins Modell-Limit) war so „für
        // alle“ (Gegenprobe 2026-11-08). Text braucht nie mehr als ein paar Ebenen; tiefer gilt als Befehl (fail-closed).
        if (jsonDepth(t) > MAX_TEXT_DEPTH) {
            return new Verdict(Tier.COMMANDS, "zu tief verschachtelter Text (JSON)");
        }
        JsonElement json;
        try {
            json = JsonParser.parseString(t);
        } catch (JsonParseException | IllegalStateException e) {
            // Kein JSON – dann ist es schlicht Text mit Klammer („[Laden]“).
            return Verdict.EVERYONE;
        }
        return jsonText(json);
    }

    /** Tiefer verschachtelter Text-JSON gilt als Befehl – so tief wie der SNBT-Leser (Snbt, 32 Ebenen). */
    static final int MAX_TEXT_DEPTH = 32;

    /**
     * Größte Klammertiefe von { und [ außerhalb von Zeichenketten. Gson liest nachsichtig (lenient), also zählen
     * Zeichenketten in " und in ' – Klammern darin sind Text.
     */
    static int jsonDepth(String s) {
        int depth = 0;
        int max = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{' || c == '[') {
                max = Math.max(max, ++depth);
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return max;
    }

    private static Verdict jsonText(JsonElement json) {
        if (json.isJsonArray()) {
            Verdict v = Verdict.EVERYONE;
            for (JsonElement e : (JsonArray) json) {
                v = v.max(jsonText(e));
            }
            return v;
        }
        if (!json.isJsonObject()) {
            return Verdict.EVERYONE;
        }
        Verdict v = Verdict.EVERYONE;
        for (Map.Entry<String, JsonElement> e : ((JsonObject) json).entrySet()) {
            if (!TEXT_KEYS.contains(e.getKey())) {
                return new Verdict(Tier.COMMANDS, textReason(e.getKey()) + " (JSON)");
            }
            if (e.getValue().isJsonArray() || e.getValue().isJsonObject()) {
                v = v.max(jsonText(e.getValue()));
            }
        }
        return v;
    }

    private static Verdict signText(Nbt value) {
        if (!(value instanceof Nbt.Compound c)) {
            return new Verdict(Tier.COMMANDS, "Schildtext in unbekannter Form");
        }
        Verdict v = Verdict.EVERYONE;
        for (Map.Entry<String, Nbt> e : c.entries().entrySet()) {
            Verdict part = switch (e.getKey()) {
                case "messages", "filtered_messages" -> e.getValue() instanceof Nbt.ListTag ? text(e.getValue())
                        : new Verdict(Tier.COMMANDS, "Schildtext in unbekannter Form");
                case "color" -> e.getValue() instanceof Nbt.Str ? Verdict.EVERYONE
                        : new Verdict(Tier.COMMANDS, "Schildfarbe in unbekannter Form");
                case "has_glowing_text" -> e.getValue() instanceof Nbt.Num ? Verdict.EVERYONE
                        : new Verdict(Tier.COMMANDS, "Leuchttext in unbekannter Form");
                default -> new Verdict(Tier.COMMANDS, "ungeprüfter Schlüssel " + e.getKey() + " am Schild");
            };
            v = v.max(part);
        }
        return v;
    }

    // ---------------------------------------------------------------- Banner, Köpfe, Töpfe

    private static Verdict bannerPatterns(Nbt value) {
        if (!(value instanceof Nbt.ListTag l)) {
            return new Verdict(Tier.COMMANDS, "Bannermuster in unbekannter Form");
        }
        for (Nbt item : l.items()) {
            if (!(item instanceof Nbt.Compound c)) {
                return new Verdict(Tier.COMMANDS, "Bannermuster in unbekannter Form");
            }
            for (Map.Entry<String, Nbt> e : c.entries().entrySet()) {
                boolean ok = (e.getKey().equals("pattern") || e.getKey().equals("color"))
                        && e.getValue() instanceof Nbt.Str;
                if (!ok) {
                    return new Verdict(Tier.COMMANDS, "eigenes Bannermuster (" + e.getKey() + ")");
                }
            }
        }
        return Verdict.EVERYONE;
    }

    private static Verdict profile(Nbt value) {
        if (value instanceof Nbt.Str) {
            return Verdict.EVERYONE;
        }
        if (!(value instanceof Nbt.Compound c)) {
            return new Verdict(Tier.COMMANDS, "Kopf-Profil in unbekannter Form");
        }
        Verdict v = Verdict.EVERYONE;
        for (Map.Entry<String, Nbt> e : c.entries().entrySet()) {
            Verdict part = switch (e.getKey()) {
                case "name" -> e.getValue() instanceof Nbt.Str ? Verdict.EVERYONE : null;
                case "id" -> e.getValue() instanceof Nbt.Arr a && a.type() == 'I' ? Verdict.EVERYONE : null;
                case "properties" -> e.getValue() instanceof Nbt.ListTag
                        ? new Verdict(Tier.CONTENTS, "Kopf mit eigener Textur") : null;
                default -> null;
            };
            if (part == null) {
                return new Verdict(Tier.COMMANDS, "ungeprüfter Schlüssel " + e.getKey() + " im Kopf-Profil");
            }
            v = v.max(part);
        }
        return v;
    }

    private static Verdict sherds(Nbt value) {
        if (!(value instanceof Nbt.ListTag l) || l.items().size() > 4) {
            return new Verdict(Tier.COMMANDS, "Topfscherben in unbekannter Form");
        }
        for (Nbt item : l.items()) {
            if (!(item instanceof Nbt.Str s)) {
                return new Verdict(Tier.COMMANDS, "Topfscherben in unbekannter Form");
            }
            String id = strip(s.value());
            // Nur bekannte IDs (Befund 12): „x_pottery_sherd“ allein reichte vorher.
            if (!id.equals("brick") && !(id.endsWith("_pottery_sherd") && knownItems().contains(id))) {
                return new Verdict(Tier.COMMANDS, "Topfscherbe " + id);
            }
        }
        return Verdict.EVERYONE;
    }

    /** Rüstungsverzierung: nur Verweise auf Material und Muster (Registry-IDs), keine eigenen Definitionen. */
    private static Verdict trim(Nbt value) {
        if (!(value instanceof Nbt.Compound c)) {
            return new Verdict(Tier.COMMANDS, "Verzierung in unbekannter Form");
        }
        for (Map.Entry<String, Nbt> e : c.entries().entrySet()) {
            boolean ok = (e.getKey().equals("material") || e.getKey().equals("pattern")) && e.getValue() instanceof Nbt.Str;
            if (!ok) {
                return new Verdict(Tier.COMMANDS, "eigene Verzierung (" + e.getKey() + ")");
            }
        }
        return new Verdict(Tier.CONTENTS, "verzierte Rüstung");
    }

    /** Höchstens 7 Sterne und Flugdauer 1–3 – mehr gibt kein Rezept im Überleben her. */
    static final int MAX_FIREWORK_EXPLOSIONS = 7;

    private static Verdict fireworks(Nbt value) {
        if (!(value instanceof Nbt.Compound c)) {
            return new Verdict(Tier.COMMANDS, "Feuerwerk in unbekannter Form");
        }
        Verdict v = new Verdict(Tier.CONTENTS, "Feuerwerk");
        for (Map.Entry<String, Nbt> e : c.entries().entrySet()) {
            switch (e.getKey()) {
                case "flight_duration" -> {
                    if (!(e.getValue() instanceof Nbt.Num n) || !n.isInteger() || n.asLong() < 1 || n.asLong() > 3) {
                        return new Verdict(Tier.COMMANDS, "Feuerwerk mit Flugdauer außerhalb 1–3");
                    }
                }
                case "explosions" -> {
                    if (!(e.getValue() instanceof Nbt.ListTag l) || l.items().size() > MAX_FIREWORK_EXPLOSIONS) {
                        return new Verdict(Tier.COMMANDS, "Feuerwerk mit mehr als " + MAX_FIREWORK_EXPLOSIONS
                                + " Sternen");
                    }
                    for (Nbt star : l.items()) {
                        v = v.max(explosion(star));
                    }
                }
                default -> {
                    return new Verdict(Tier.COMMANDS, "ungeprüfter Schlüssel " + e.getKey() + " im Feuerwerk");
                }
            }
        }
        return v;
    }

    private static Verdict explosion(Nbt value) {
        if (!(value instanceof Nbt.Compound c)) {
            return new Verdict(Tier.COMMANDS, "Feuerwerksstern in unbekannter Form");
        }
        for (Map.Entry<String, Nbt> e : c.entries().entrySet()) {
            boolean ok = switch (e.getKey()) {
                case "shape" -> e.getValue() instanceof Nbt.Str;
                case "colors", "fade_colors" -> e.getValue() instanceof Nbt.Arr a && a.type() == 'I'
                        && a.values().size() <= 8;
                case "has_trail", "has_twinkle" -> e.getValue() instanceof Nbt.Num;
                default -> false;
            };
            if (!ok) {
                return new Verdict(Tier.COMMANDS, "Feuerwerksstern mit " + e.getKey());
            }
        }
        return Verdict.EVERYONE;
    }

    // ---------------------------------------------------------------- Gegenstände

    /** Items-Liste eines Behälters (mit Slot). Eine leere Liste ist kein Inhalt. */
    static Verdict items(Nbt value) {
        if (!(value instanceof Nbt.ListTag l)) {
            return new Verdict(Tier.COMMANDS, "Behälterinhalt in unbekannter Form");
        }
        Verdict v = Verdict.EVERYONE;
        for (Nbt item : l.items()) {
            v = v.max(stack(item, true));
            if (v.tier() == Tier.COMMANDS) {
                return v;
            }
        }
        return v;
    }

    /** Ein Gegenstand: id, count, components (und Slot in Behältern). */
    static Verdict stack(Nbt value, boolean withSlot) {
        if (!(value instanceof Nbt.Compound c)) {
            return new Verdict(Tier.COMMANDS, "Gegenstand in unbekannter Form");
        }
        if (!(c.get("id") instanceof Nbt.Str idTag)) {
            return new Verdict(Tier.COMMANDS, "Gegenstand ohne id");
        }
        String id = strip(idTag.value());
        Verdict v = new Verdict(Tier.CONTENTS, "Gegenstände (" + id + ")").max(item(id));
        for (Map.Entry<String, Nbt> e : c.entries().entrySet()) {
            Verdict part = switch (e.getKey()) {
                case "id" -> Verdict.EVERYONE;
                case "count" -> e.getValue() instanceof Nbt.Num n && n.isInteger() && n.asLong() >= 1 && n.asLong() <= 99
                        ? Verdict.EVERYONE : new Verdict(Tier.COMMANDS, "Gegenstandsmenge außerhalb 1–99");
                case "Slot" -> withSlot && e.getValue() instanceof Nbt.Num n && n.isInteger() ? Verdict.EVERYONE
                        : new Verdict(Tier.COMMANDS, "Fach in unbekannter Form");
                case "components" -> components(e.getValue());
                default -> new Verdict(Tier.COMMANDS, "ungeprüfter Schlüssel " + e.getKey() + " im Gegenstand");
            };
            v = v.max(part);
        }
        return v;
    }

    /** Gegenstand-ID gestuft wie Blöcke; unbekannte IDs → höchste Stufe. */
    static Verdict item(String id) {
        if (!knownItems().contains(id)) {
            return new Verdict(Tier.COMMANDS, "ungeprüfter Gegenstand " + id);
        }
        if (COMMAND_ITEMS.contains(id)) {
            return new Verdict(Tier.COMMANDS, "Gegenstand " + id);
        }
        if (DANGEROUS_ITEMS.contains(id) || id.endsWith("_spawn_egg")) {
            return new Verdict(Tier.DANGEROUS, "Gegenstand " + id);
        }
        return new Verdict(Tier.CONTENTS, "Gegenstand " + id);
    }

    /** Bestandteile eines Gegenstands (components); nur die Freigabeliste ist Inhalt, alles andere Befehle. */
    static Verdict components(Nbt value) {
        if (!(value instanceof Nbt.Compound c)) {
            return new Verdict(Tier.COMMANDS, "Bestandteile in unbekannter Form");
        }
        Verdict v = Verdict.EVERYONE;
        for (Map.Entry<String, Nbt> e : c.entries().entrySet()) {
            if (e.getKey().startsWith("!")) {
                return new Verdict(Tier.COMMANDS, "entfernter Bestandteil " + e.getKey());
            }
            String k = strip(e.getKey());
            Nbt val = e.getValue();
            // Werte geprüft wie am Block (Sicherheitsprüfung 2026-11-08, Befund 3): Vorher galten diese Bestandteile
            // pauschal als Inhalt – ein Topf mit pot_decorations [bedrock, command_block] ließ beim Zerschlagen genau
            // diese Gegenstände fallen, ein Feuerwerk mit 256 Explosionen tötete sofort.
            Verdict part = switch (k) {
                case "damage" -> val instanceof Nbt.Num n && n.isInteger() && n.asLong() >= 0
                        ? new Verdict(Tier.CONTENTS, "Gegenstand mit Abnutzung")
                        : new Verdict(Tier.COMMANDS, "Abnutzung in unbekannter Form");
                case "dyed_color" -> val instanceof Nbt.Num n && n.isInteger()
                        ? new Verdict(Tier.CONTENTS, "gefärbter Gegenstand")
                        : new Verdict(Tier.COMMANDS, "Farbe in unbekannter Form");
                case "base_color" -> val instanceof Nbt.Str ? new Verdict(Tier.CONTENTS, "Schild mit Grundfarbe")
                        : new Verdict(Tier.COMMANDS, "Grundfarbe in unbekannter Form");
                case "instrument" -> val instanceof Nbt.Str ? new Verdict(Tier.CONTENTS, "Ziegenhorn")
                        : new Verdict(Tier.COMMANDS, "eigenes Instrument (Klang, Reichweite)");
                case "trim" -> trim(val);
                case "fireworks" -> fireworks(val);
                case "firework_explosion" -> new Verdict(Tier.CONTENTS, "Feuerwerksstern").max(explosion(val));
                case "banner_patterns" -> new Verdict(Tier.CONTENTS, "Gegenstand mit Bannermuster")
                        .max(bannerPatterns(val));
                case "pot_decorations" -> new Verdict(Tier.CONTENTS, "Topf mit Scherben").max(sherds(val));
                case "custom_name", "item_name" -> new Verdict(Tier.CONTENTS, "Gegenstand mit Namen").max(text(val));
                case "lore" -> val instanceof Nbt.ListTag ? new Verdict(Tier.CONTENTS, "Gegenstand mit Beschreibung")
                        .max(text(val)) : new Verdict(Tier.COMMANDS, "Beschreibung in unbekannter Form");
                case "enchantments", "stored_enchantments" -> enchantments(val);
                case "potion_contents" -> potion(val);
                case "profile" -> new Verdict(Tier.CONTENTS, "Kopf").max(profile(val));
                case "writable_book_content" -> new Verdict(Tier.CONTENTS, "Buch");
                case "written_book_content" -> writtenBook(val);
                case "custom_data" -> new Verdict(Tier.COMMANDS, "eigene Gegenstandsdaten (custom_data)");
                case "entity_data", "bucket_entity_data" -> new Verdict(Tier.COMMANDS, "Entity-Daten im Gegenstand");
                case "block_entity_data" -> new Verdict(Tier.COMMANDS, "Blockdaten im Gegenstand");
                case "can_place_on", "can_break" -> new Verdict(Tier.COMMANDS, "Abenteuer-Erlaubnis (" + k + ")");
                case "attribute_modifiers" -> new Verdict(Tier.COMMANDS, "Attribute am Gegenstand");
                case "consumable" -> new Verdict(Tier.COMMANDS, "eigene Wirkung beim Verzehr");
                case "container", "bundle_contents", "charged_projectiles" ->
                        new Verdict(Tier.COMMANDS, "verschachtelter Behälter (" + k + ")");
                case "container_loot" -> new Verdict(Tier.COMMANDS, "Beutetabelle im Gegenstand");
                default -> new Verdict(Tier.COMMANDS, "ungeprüfter Bestandteil " + k);
            };
            v = v.max(part);
            if (v.tier() == Tier.COMMANDS) {
                return v;
            }
        }
        return v;
    }

    private static Verdict enchantments(Nbt value) {
        if (!(value instanceof Nbt.Compound c)) {
            return new Verdict(Tier.COMMANDS, "Verzauberungen in unbekannter Form");
        }
        for (Map.Entry<String, Nbt> e : c.entries().entrySet()) {
            Integer max = MAX_ENCHANTMENT.get(strip(e.getKey()));
            if (max == null) {
                return new Verdict(Tier.COMMANDS, "ungeprüfte Verzauberung " + e.getKey());
            }
            if (!(e.getValue() instanceof Nbt.Num n) || !n.isInteger() || n.asLong() < 1 || n.asLong() > max) {
                return new Verdict(Tier.COMMANDS, "Verzauberung über dem Höchstwert (" + strip(e.getKey()) + ")");
            }
        }
        return new Verdict(Tier.CONTENTS, "verzauberter Gegenstand");
    }

    private static Verdict potion(Nbt value) {
        if (value instanceof Nbt.Str) {
            return new Verdict(Tier.CONTENTS, "Trank");
        }
        if (!(value instanceof Nbt.Compound c)) {
            return new Verdict(Tier.COMMANDS, "Trank in unbekannter Form");
        }
        for (Map.Entry<String, Nbt> e : c.entries().entrySet()) {
            boolean ok = e.getKey().equals("potion") && e.getValue() instanceof Nbt.Str
                    || e.getKey().equals("custom_color") && e.getValue() instanceof Nbt.Num;
            if (!ok) {
                return new Verdict(Tier.COMMANDS, "eigene Trankwirkung (" + e.getKey() + ")");
            }
        }
        return new Verdict(Tier.CONTENTS, "Trank");
    }

    private static Verdict writtenBook(Nbt value) {
        if (!(value instanceof Nbt.Compound c)) {
            return new Verdict(Tier.COMMANDS, "Buch in unbekannter Form");
        }
        Verdict v = new Verdict(Tier.CONTENTS, "Buch");
        for (Map.Entry<String, Nbt> e : c.entries().entrySet()) {
            Verdict part = switch (e.getKey()) {
                case "title", "author" -> Verdict.EVERYONE;
                case "generation", "resolved" -> e.getValue() instanceof Nbt.Num ? Verdict.EVERYONE : null;
                case "pages" -> e.getValue() instanceof Nbt.ListTag l ? pages(l) : null;
                default -> null;
            };
            if (part == null) {
                return new Verdict(Tier.COMMANDS, "ungeprüfter Schlüssel " + e.getKey() + " im Buch");
            }
            v = v.max(part);
        }
        return v;
    }

    /** Buchseiten: Text oder {raw: Text, filtered: Text}. */
    private static Verdict pages(Nbt.ListTag pages) {
        Verdict v = Verdict.EVERYONE;
        for (Nbt page : pages.items()) {
            if (page instanceof Nbt.Compound c && c.has("raw")) {
                for (Map.Entry<String, Nbt> e : c.entries().entrySet()) {
                    if (!e.getKey().equals("raw") && !e.getKey().equals("filtered")) {
                        return new Verdict(Tier.COMMANDS, "ungeprüfter Schlüssel " + e.getKey() + " auf einer Seite");
                    }
                    v = v.max(text(e.getValue()));
                }
            } else {
                v = v.max(text(page));
            }
        }
        return v;
    }

    private static String strip(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    // ---------------------------------------------------------------- Freigabelisten

    static Set<String> knownBlocks() {
        Set<String> s = knownBlocks;
        if (s == null) {
            s = load("/tiers/block-ids.txt");
            knownBlocks = s;
        }
        return s;
    }

    static Set<String> knownItems() {
        Set<String> s = knownItems;
        if (s == null) {
            s = load("/tiers/item-ids.txt");
            knownItems = s;
        }
        return s;
    }

    private static Set<String> load(String resource) {
        Set<String> out = new HashSet<>();
        try (InputStream in = BlockTiers.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Freigabeliste fehlt im Plugin: " + resource);
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    out.add(t);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Set.copyOf(out);
    }
}
