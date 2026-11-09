package com.blackn0va.baumeister.plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Findet zu einem Namen, den ein Modell oder Spieler schreibt, die echte ID aus dem Register des laufenden Servers
 * (Material: jeder Gegenstand, jeder Block dieser Minecraft-Fassung – vollständiger als jede Liste im Netz, die z. B.
 * auf minecraftsearch.com 752 Gegenstände ohne Blöcke führt).
 *
 * Vorfälle dev01 2026-11-04: Vera schrieb „redstone_comparator“ (echt: comparator) und sagte „Das kenne ich leider
 * nicht“; Baupläne mit „redstone“ als Block (echt: redstone_wire) wurden zum Ersatzblock Stein. Reihenfolge:
 * genau → Verwechslungen und deutsche Namen (Tabelle) → Mehrzahl/Präfixe → Wortmenge → ein bis zwei Tippfehler.
 * Mehrdeutig (zwei gleich gute Treffer) heißt: kein Treffer – lieber nachfragen als das Falsche geben.
 *
 * Threads: zustandslos.
 */
public final class ItemNames {

    /** Englische Verwechslungen und deutsche Namen → ID. Schlüssel normalisiert (klein, ä → ae, Leerzeichen → _). */
    // umlaut-ok: Schlüssel sind normalisiert (ä → ae), wie normalize() sie erzeugt.
    static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("redstone_comparator", "comparator"), Map.entry("redstone_repeater", "repeater"),
            // Craften (2026-11-07): Namen, die „crafte mir …“ braucht und die noch fehlten.
            Map.entry("stock", "stick"), Map.entry("stoecke", "stick"), Map.entry("stab", "stick"),
            Map.entry("staebe", "stick"), Map.entry("boot", "oak_boat"), Map.entry("druckplatte", "stone_pressure_plate"),
            Map.entry("fass", "barrel"), Map.entry("faesser", "barrel"), Map.entry("glasscheibe", "glass_pane"),
            Map.entry("glasscheiben", "glass_pane"),
            Map.entry("redstone_dust", "redstone"), Map.entry("redstone_lamp_block", "redstone_lamp"),
            Map.entry("redstone_powder", "redstone"), Map.entry("block_of_redstone", "redstone_block"),
            Map.entry("block_of_iron", "iron_block"), Map.entry("block_of_gold", "gold_block"),
            Map.entry("block_of_diamond", "diamond_block"), Map.entry("block_of_emerald", "emerald_block"),
            Map.entry("sign", "oak_sign"), Map.entry("wall_sign", "oak_wall_sign"), Map.entry("button", "stone_button"),
            Map.entry("pressure_plate", "stone_pressure_plate"), Map.entry("boat", "oak_boat"),
            Map.entry("wood", "oak_log"), Map.entry("planks", "oak_planks"), Map.entry("bed", "red_bed"),
            Map.entry("wool", "white_wool"), Map.entry("steak", "cooked_beef"), Map.entry("pork", "cooked_porkchop"),
            Map.entry("leather_cap", "leather_helmet"), Map.entry("leather_tunic", "leather_chestplate"),
            Map.entry("leather_pants", "leather_leggings"), Map.entry("bottle", "glass_bottle"),
            Map.entry("map", "map"), Map.entry("xp_bottle", "experience_bottle"),
            Map.entry("bottle_o_enchanting", "experience_bottle"), Map.entry("enchanting_bottle", "experience_bottle"),
            Map.entry("slime", "slime_ball"), Map.entry("nether_portal_frame", "obsidian"),
            Map.entry("cobweb_block", "cobweb"), Map.entry("workbench", "crafting_table"),
            Map.entry("crafting_bench", "crafting_table"), Map.entry("minecart_rail", "rail"),
            // Deutsch
            Map.entry("fackel", "torch"), Map.entry("fackeln", "torch"), Map.entry("brot", "bread"),
            // Aus echten Wünschen an Vera (dev01-Log bis 2026-11-07): „Gib mir Dynamit“, „Elytrin“, „Schwämme“.
            Map.entry("dynamit", "tnt"), Map.entry("elytrin", "elytra"), Map.entry("elytra_fluegel", "elytra"),
            Map.entry("schwamm", "sponge"), Map.entry("schwaemme", "sponge"), Map.entry("raketen", "firework_rocket"),
            Map.entry("rakete", "firework_rocket"), Map.entry("feuerwerk", "firework_rocket"),
            Map.entry("diamant", "diamond"), Map.entry("diamanten", "diamond"), Map.entry("eisen", "iron_ingot"),
            Map.entry("eisenbarren", "iron_ingot"), Map.entry("gold", "gold_ingot"), Map.entry("goldbarren", "gold_ingot"),
            Map.entry("kupfer", "copper_ingot"), Map.entry("kohle", "coal"), Map.entry("holzkohle", "charcoal"),
            Map.entry("holz", "oak_log"), Map.entry("stamm", "oak_log"), Map.entry("bretter", "oak_planks"),
            Map.entry("holzbretter", "oak_planks"), Map.entry("stein", "stone"), Map.entry("bruchstein", "cobblestone"),
            Map.entry("glas", "glass"), Map.entry("kies", "gravel"), Map.entry("erde", "dirt"), Map.entry("lehm", "clay"),
            Map.entry("wolle", "white_wool"), Map.entry("bett", "red_bed"), Map.entry("truhe", "chest"),
            Map.entry("kiste", "chest"), Map.entry("ofen", "furnace"), Map.entry("schmelzofen", "blast_furnace"),
            Map.entry("werkbank", "crafting_table"), Map.entry("amboss", "anvil"),
            Map.entry("zaubertisch", "enchanting_table"), Map.entry("braustand", "brewing_stand"),
            Map.entry("spitzhacke", "diamond_pickaxe"), Map.entry("schwert", "diamond_sword"),
            Map.entry("axt", "diamond_axe"), Map.entry("schaufel", "diamond_shovel"), Map.entry("hacke", "diamond_hoe"),
            Map.entry("bogen", "bow"), Map.entry("armbrust", "crossbow"), Map.entry("pfeil", "arrow"),
            Map.entry("pfeile", "arrow"), Map.entry("schild", "shield"), Map.entry("dreizack", "trident"),
            Map.entry("apfel", "apple"), Map.entry("goldener_apfel", "golden_apple"), Map.entry("fleisch", "cooked_beef"),
            Map.entry("karotte", "carrot"), Map.entry("karotten", "carrot"), Map.entry("kartoffel", "baked_potato"),
            Map.entry("kuchen", "cake"), Map.entry("keks", "cookie"), Map.entry("kekse", "cookie"),
            Map.entry("fisch", "cooked_cod"), Map.entry("eimer", "bucket"), Map.entry("wassereimer", "water_bucket"),
            Map.entry("lavaeimer", "lava_bucket"), Map.entry("milch", "milk_bucket"), Map.entry("milcheimer", "milk_bucket"),
            Map.entry("sprengstoff", "tnt"), Map.entry("hebel", "lever"), Map.entry("knopf", "stone_button"),
            Map.entry("kolben", "piston"), Map.entry("klebriger_kolben", "sticky_piston"),
            Map.entry("verstaerker", "repeater"), Map.entry("komparator", "comparator"),
            Map.entry("vergleicher", "comparator"), Map.entry("beobachter", "observer"), Map.entry("trichter", "hopper"),
            Map.entry("spender", "dispenser"), Map.entry("werfer", "dropper"), Map.entry("schiene", "rail"),
            Map.entry("schienen", "rail"), Map.entry("antriebsschiene", "powered_rail"),
            Map.entry("lore", "minecart"), Map.entry("leiter", "ladder"), Map.entry("tuer", "oak_door"),
            Map.entry("zaun", "oak_fence"), Map.entry("laterne", "lantern"), Map.entry("smaragd", "emerald"),
            Map.entry("smaragde", "emerald"), Map.entry("netherit", "netherite_ingot"),
            Map.entry("enderperle", "ender_pearl"), Map.entry("enderauge", "ender_eye"), Map.entry("lohenrute", "blaze_rod"),
            Map.entry("knochen", "bone"), Map.entry("knochenmehl", "bone_meal"), Map.entry("faden", "string"),
            Map.entry("feder", "feather"), Map.entry("leder", "leather"), Map.entry("papier", "paper"),
            Map.entry("buch", "book"), Map.entry("buecherregal", "bookshelf"), Map.entry("zuckerrohr", "sugar_cane"),
            Map.entry("zucker", "sugar"), Map.entry("weizen", "wheat"), Map.entry("samen", "wheat_seeds"),
            Map.entry("ei", "egg"), Map.entry("eier", "egg"), Map.entry("schneeball", "snowball"),
            Map.entry("schneebaelle", "snowball"), Map.entry("eis", "ice"), Map.entry("packeis", "packed_ice"),
            Map.entry("obsidian", "obsidian"), Map.entry("leuchtstein", "glowstone"), Map.entry("seelaterne", "sea_lantern"),
            Map.entry("ziegel", "bricks"), Map.entry("steinziegel", "stone_bricks"), Map.entry("sandstein", "sandstone"),
            Map.entry("quarz", "quartz"), Map.entry("lapis", "lapis_lazuli"), Map.entry("kompass", "compass"),
            Map.entry("uhr", "clock"), Map.entry("karte", "map"), Map.entry("schere", "shears"),
            Map.entry("angel", "fishing_rod"), Map.entry("angelrute", "fishing_rod"), Map.entry("sattel", "saddle"),
            Map.entry("leine", "lead"), Map.entry("namensschild", "name_tag"), Map.entry("fernrohr", "spyglass"),
            Map.entry("feuerzeug", "flint_and_steel"), Map.entry("feuerstein", "flint"), Map.entry("elytren", "elytra"),
            Map.entry("elitren", "elytra"), Map.entry("elitra", "elytra"),
            Map.entry("fluegel", "elytra"), // umlaut-ok: Schlüssel nach normalize (ü → ue)
            Map.entry("totem", "totem_of_undying"), Map.entry("helm", "diamond_helmet"),
            Map.entry("brustpanzer", "diamond_chestplate"), Map.entry("hose", "diamond_leggings"),
            Map.entry("beinschutz", "diamond_leggings"), Map.entry("stiefel", "diamond_boots"),
            Map.entry("kerze", "candle"), Map.entry("glocke", "bell"), Map.entry("blumentopf", "flower_pot"),
            Map.entry("setzling", "oak_sapling"), Map.entry("blume", "poppy"), Map.entry("rose", "poppy"));

    /** Einzelwörter für zusammengesetzte Namen („Diamant Spitzhacke“, „Eisen Helm“) → englische ID-Teile. */
    // umlaut-ok: normalisierte Schlüssel
    static final Map<String, String> WORDS = Map.ofEntries(
            Map.entry("diamant", "diamond"), Map.entry("diamanten", "diamond"), Map.entry("eisen", "iron"),
            Map.entry("gold", "golden"), Map.entry("goldene", "golden"), Map.entry("goldenen", "golden"),
            Map.entry("goldener", "golden"), Map.entry("goldenes", "golden"), Map.entry("holz", "wooden"),
            Map.entry("stein", "stone"), Map.entry("netherit", "netherite"), Map.entry("kupfer", "copper"),
            Map.entry("leder", "leather"), Map.entry("ketten", "chainmail"), Map.entry("spitzhacke", "pickaxe"),
            Map.entry("schwert", "sword"), Map.entry("axt", "axe"), Map.entry("schaufel", "shovel"),
            Map.entry("hacke", "hoe"), Map.entry("helm", "helmet"), Map.entry("brustpanzer", "chestplate"),
            Map.entry("hose", "leggings"), Map.entry("beinschutz", "leggings"), Map.entry("stiefel", "boots"),
            Map.entry("block", "block"), Map.entry("erz", "ore"), Map.entry("barren", "ingot"),
            Map.entry("apfel", "apple"), Map.entry("karotte", "carrot"), Map.entry("tuer", "door"),
            Map.entry("treppe", "stairs"), Map.entry("stufe", "slab"), Map.entry("zaun", "fence"),
            Map.entry("eiche", "oak"), Map.entry("fichte", "spruce"), Map.entry("birke", "birch"),
            Map.entry("tropen", "jungle"), Map.entry("akazie", "acacia"), Map.entry("schwarzeiche", "dark_oak"),
            Map.entry("kirsch", "cherry"), Map.entry("wolle", "wool"), Map.entry("beton", "concrete"),
            Map.entry("glas", "glass"), Map.entry("weiss", "white"), Map.entry("weisse", "white"),
            Map.entry("rot", "red"), Map.entry("rote", "red"), Map.entry("blau", "blue"), Map.entry("blaue", "blue"),
            Map.entry("gruen", "green"), Map.entry("gruene", "green"), Map.entry("gelb", "yellow"),
            Map.entry("gelbe", "yellow"), Map.entry("schwarz", "black"), Map.entry("schwarze", "black"));

    private ItemNames() {
    }

    /** Deutsche Anzeige-Namen häufiger Zutaten und Werkbank-Ergebnisse (offizielle deutsche Spielnamen). */
    private static final Map<String, String> GERMAN = Map.ofEntries(
            Map.entry("torch", "Fackeln"), Map.entry("stick", "Stöcke"), Map.entry("coal", "Kohle"),
            Map.entry("charcoal", "Holzkohle"), Map.entry("iron_ingot", "Eisenbarren"),
            Map.entry("gold_ingot", "Goldbarren"), Map.entry("copper_ingot", "Kupferbarren"),
            Map.entry("diamond", "Diamanten"), Map.entry("cobblestone", "Bruchstein"), Map.entry("string", "Faden"),
            Map.entry("crafting_table", "Werkbank"), Map.entry("furnace", "Ofen"), Map.entry("chest", "Truhe"),
            Map.entry("bucket", "Eimer"), Map.entry("ladder", "Leitern"), Map.entry("bread", "Brot"),
            Map.entry("wheat", "Weizen"), Map.entry("paper", "Papier"), Map.entry("book", "Buch"),
            Map.entry("leather", "Leder"), Map.entry("glass", "Glas"), Map.entry("glass_pane", "Glasscheiben"),
            Map.entry("sugar_cane", "Zuckerrohr"), Map.entry("feather", "Federn"), Map.entry("flint", "Feuerstein"),
            Map.entry("arrow", "Pfeile"), Map.entry("bow", "Bogen"), Map.entry("shield", "Schild"),
            Map.entry("redstone", "Redstone"), Map.entry("lever", "Hebel"), Map.entry("hopper", "Trichter"),
            Map.entry("barrel", "Fass"), Map.entry("bowl", "Schüssel"), Map.entry("shears", "Schere"),
            Map.entry("compass", "Kompass"), Map.entry("clock", "Uhr"), Map.entry("oak_boat", "Boot"));
    private static final Map<String, String> GERMAN_MATERIAL = Map.of("diamond", "Diamant", "iron", "Eisen",
            "golden", "Gold", "stone", "Stein", "wooden", "Holz", "netherite", "Netherit", "copper", "Kupfer",
            "leather", "Leder", "chainmail", "Ketten");
    private static final Map<String, String> GERMAN_TOOL = Map.of("pickaxe", "spitzhacke", "axe", "axt", "shovel",
            "schaufel", "hoe", "hacke", "sword", "schwert", "helmet", "helm", "chestplate", "brustpanzer", "leggings",
            "hose", "boots", "stiefel");

    /**
     * Deutscher Name für Antworten („Hier: 4 Fackeln – aus 1 Kohle, 1 Stöcke“ statt IDs): feste Namen, dann
     * Werkzeug/Rüstung aus Material und Teil („Eisenspitzhacke“), Planken und Stämme jeder Holzart, sonst leer.
     */
    public static Optional<String> german(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String key = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        String direct = GERMAN.get(key);
        if (direct != null) {
            return Optional.of(direct);
        }
        int cut = key.indexOf('_');
        if (cut > 0) {
            String mat = GERMAN_MATERIAL.get(key.substring(0, cut));
            String tool = GERMAN_TOOL.get(key.substring(cut + 1));
            if (mat != null && tool != null) {
                return Optional.of(mat + tool);
            }
        }
        if (key.endsWith("_planks")) {
            return Optional.of("Planken");
        }
        if (key.endsWith("_log")) {
            return Optional.of("Stämme");
        }
        return Optional.empty();
    }

    /** Klein, ohne „minecraft:“, Umlaute ausgeschrieben, Leer- und Bindestriche als „_“. */
    static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:")) {
            s = s.substring("minecraft:".length());
        }
        s = s.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        return s.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /**
     * @param raw   was geschrieben wurde („Redstone Comparator“, „Fackeln“, „minecraft:oak_plank“)
     * @param known alle zulässigen IDs ohne „minecraft:“ (z. B. alle Gegenstände oder alle Blöcke des Servers)
     */
    public static Optional<String> resolve(String raw, Collection<String> known) {
        String n = normalize(raw);
        if (n.isEmpty()) {
            return Optional.empty();
        }
        Set<String> ids = known instanceof Set<String> s ? s : Set.copyOf(known);
        return resolveStrict(raw, ids).or(() -> byTypo(n, ids));
    }

    /**
     * Wie resolve, aber ohne Tippfehler-Nähe: nur ID, Alias, Plural, Wortfolge oder deutsche Wort-für-Wort-Übersetzung.
     * Für Wege ohne LLM (CompanionChat.giveOrder): Dort darf „gib mir Bescheid“ nicht über die Nähe zu irgendeinem
     * Gegenstand zur Gabe werden – was hier nicht sicher trifft, geht an das Modell.
     */
    public static Optional<String> resolveStrict(String raw, Collection<String> known) {
        String n = normalize(raw);
        if (n.isEmpty()) {
            return Optional.empty();
        }
        Set<String> ids = known instanceof Set<String> s ? s : Set.copyOf(known);
        if (ids.contains(n)) {
            return Optional.of(n);
        }
        String alias = ALIASES.get(n);
        if (alias != null && ids.contains(alias)) {
            return Optional.of(alias);
        }
        for (String c : List.of(n + "s", n.endsWith("es") ? n.substring(0, n.length() - 2) : n,
                n.endsWith("s") ? n.substring(0, n.length() - 1) : n, n.replace("wooden_", "oak_"),
                n.replace("wood_", "oak_"), "oak_" + n, n.replaceFirst("^redstone_", ""), n + "_block",
                n.replaceFirst("^block_of_", "") + "_block")) {
            if (ids.contains(c)) {
                return Optional.of(c);
            }
        }
        Optional<String> words = byWords(n, ids);
        if (words.isPresent()) {
            return words;
        }
        // „ein dickes Schwert“, „coole Fackeln“: Eigenschaftswörter weg und noch einmal (dev01-Log 2026-11-06: „gib mir
        // ein diicken schwert“ ging ans Modell, weil der Name nicht im Register stand).
        String plain = stripAdjectives(n);
        if (!plain.equals(n) && !plain.isEmpty()) {
            Optional<String> again = resolveStrict(plain, ids);
            if (again.isPresent()) {
                return again;
            }
        }
        // Verhört oder vertippt: ein eindeutiger deutscher Name mit höchstens 1 (ab 8 Buchstaben 2) Abweichungen.
        Optional<String> near = nearAlias(n);
        if (near.isPresent() && ids.contains(near.get())) {
            return near;
        }
        // Deutsch Wort für Wort: „diamant_spitzhacke“ → „diamond_pickaxe“ (auch umgestellt über byWords).
        if (n.contains("_")) {
            StringBuilder t = new StringBuilder();
            boolean changed = false;
            for (String w : n.split("_")) {
                String e = WORDS.getOrDefault(w, w);
                changed |= !e.equals(w);
                t.append(t.isEmpty() ? "" : "_").append(e);
            }
            if (changed) {
                String tn = t.toString();
                if (ids.contains(tn)) {
                    return Optional.of(tn);
                }
                Optional<String> tw = byWords(tn, ids);
                if (tw.isPresent()) {
                    return tw;
                }
            }
        }
        return Optional.empty();
    }

    private static final java.util.regex.Pattern ADJECTIVES = java.util.regex.Pattern.compile(
            "(^|_)(ein|eine|einen|einem|paar|viele|vielen|dick|dicke|dicken|dickes|dicker|diicken|diickes|gross|grosse|"
            + "grossen|grosses|grosser|stark|starke|starken|starkes|beste|besten|bestes|cool|coole|coolen|cooles|"
            + "schoen|schoene|schoenen|schoenes|neu|neue|neuen|neues|krass|krasse|krassen|krasses|richtig|richtige|" // umlaut-ok: Formen nach normalize (ö → oe)
            + "richtiges|gut|gute|guten|gutes|mega|super|geil|geile|geiles)(?=_|$)");

    /** Eigenschafts- und Mengenwörter vor dem Namen weg: „dicken_schwert“ → „schwert“. */
    static String stripAdjectives(String n) {
        return ADJECTIVES.matcher(n).replaceAll("$1").replaceAll("_+", "_").replaceAll("^_+|_+$", "");
    }

    /** Nächster deutscher Name (ALIASES), nur wenn er eindeutig der nächste ist. */
    static Optional<String> nearAlias(String n) {
        if (n.length() < 5) {
            return Optional.empty();
        }
        int limit = n.length() >= 8 ? 2 : 1;
        String best = null;
        int bestD = Integer.MAX_VALUE;
        boolean tie = false;
        for (Map.Entry<String, String> e : ALIASES.entrySet()) {
            int d = distance(n, e.getKey(), limit);
            if (d < bestD) {
                bestD = d;
                best = e.getValue();
                tie = false;
            } else if (d == bestD && !e.getValue().equals(best)) {
                tie = true;
            }
        }
        return bestD <= limit && !tie ? Optional.ofNullable(best) : Optional.empty();
    }

    /** Alle Wörter des Namens stecken in der ID, höchstens ein Wort mehr: „pickaxe diamond“ → diamond_pickaxe. */
    private static Optional<String> byWords(String n, Set<String> ids) {
        List<String> want = List.of(n.split("_"));
        String best = null;
        int bestExtra = Integer.MAX_VALUE;
        boolean tie = false;
        for (String id : ids) {
            List<String> have = List.of(id.split("_"));
            if (!have.containsAll(want)) {
                continue;
            }
            int extra = have.size() - want.size();
            if (extra > 1) {
                continue;
            }
            if (extra < bestExtra) {
                best = id;
                bestExtra = extra;
                tie = false;
            } else if (extra == bestExtra) {
                tie = true;
            }
        }
        return best == null || tie ? Optional.empty() : Optional.of(best);
    }

    /** Tippfehler: ab 5 Zeichen einer, ab 9 Zeichen zwei – nur bei genau einem nächsten Kandidaten. */
    private static Optional<String> byTypo(String n, Set<String> ids) {
        if (n.length() < 5) {
            return Optional.empty();
        }
        int limit = n.length() >= 9 ? 2 : 1;
        List<String> best = new ArrayList<>();
        int bestD = limit + 1;
        for (String id : ids) {
            if (Math.abs(id.length() - n.length()) > limit) {
                continue;
            }
            int d = distance(n, id, limit);
            if (d < bestD) {
                bestD = d;
                best.clear();
                best.add(id);
            } else if (d == bestD) {
                best.add(id);
            }
        }
        return best.size() == 1 ? Optional.of(best.getFirst()) : Optional.empty();
    }

    private static int distance(String a, String b, int limit) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > limit) {
                return limit + 1;
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
