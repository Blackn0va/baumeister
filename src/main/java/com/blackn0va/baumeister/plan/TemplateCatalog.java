package com.blackn0va.baumeister.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Zwischenschicht aus den Bauvorlagen von Minecraft (Wunsch 2026-11-04).
 *
 * Zwei Wege:
 * <ul>
 * <li><b>streng</b> – vor dem LLM wie QuickOrders: Nur wenn der Auftrag ausschließlich aus Vorlagen-Wörtern und
 * Füllwörtern besteht („bau mir ein Iglu“, „ein Wüstendorf mit 8 Häusern“, „eine Schmiede im Schneestil“). Ein
 * schlichtes „Haus“ ohne Stilwort bleibt beim LLM – es baut Material- und Größenwünsche, die Vorlage nicht.</li>
 * <li><b>locker</b> – Rückfall, wenn das LLM keinen Plan liefert (verworfen, Fehler, Host aus): Unbekannte Wörter
 * zählen nicht, ein „Haus“ reicht. Vorher bekam der Spieler dann nur „Ich bekomme keinen brauchbaren Plan hin“
 * (dev01 2026-11-04 14:15).</li>
 * </ul>
 * Dorf: Straße geradeaus vom Spieler weg, Häuser links und rechts mit der Tür zur Straße (Vorderseite aus der Vorlage,
 * TemplateModules), Dorfplatz am Ende. Die Aufstellung ist Rechnung nach den echten Maßen – wie bei MachineHall, die
 * das Modell gemessen nicht kann.
 *
 * Threads: zustandslos (Source entscheidet selbst). Fehler: fehlt eine Vorlage, fällt nur dieses Haus weg; fehlen
 * alle, gibt es keinen Treffer und der Auftrag geht seinen normalen Weg.
 */
public final class TemplateCatalog {

    /** Stil der Dorfvorlagen; Vorgabe ist das Biom unter dem Spieler. */
    public enum Biome {
        PLAINS("plains", "Wiesen"), DESERT("desert", "Wüsten"), SAVANNA("savanna", "Savannen"),
        SNOWY("snowy", "Schnee"), TAIGA("taiga", "Taiga");

        final String dir;
        final String german;

        Biome(String dir, String german) {
            this.dir = dir;
            this.german = german;
        }
    }

    enum Kind {
        SMALL("ein kleines Dorfhaus", "small_house"), MEDIUM("ein Dorfhaus", "medium_house"),
        BIG("ein großes Dorfhaus", "big_house"), TEMPLE("eine Kirche", "temple"),
        LIBRARY("eine Bibliothek", "library"), SMITH("eine Schmiede", "smith", "armorer"),
        BUTCHER("eine Metzgerei", "butcher"), STABLE("einen Stall", "stable"),
        SHEPHERD("eine Schäferei", "shepherd"), TANNERY("eine Gerberei", "tannery"),
        FISHER("eine Fischerhütte", "fisher"), CARTOGRAPHER("ein Kartografenhaus", "cartographer"),
        FLETCHER("ein Pfeilmacherhaus", "fletcher"), MASON("ein Steinmetzhaus", "mason"),
        FARM("ein Feld", "farm"), PEN("ein Tiergehege", "animal_pen"),
        PLAZA("einen Dorfplatz", "meeting_point", "fountain");

        final String german;
        final String[] stems;

        Kind(String german, String... stems) {
            this.german = german;
            this.stems = stems;
        }

        boolean matches(String stem) {
            for (String s : stems) {
                if (stem.contains(s)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Liefert eine Vorlage nach ihrem Namen (VanillaTemplates liest die Datei aus dem Server-JAR). */
    @FunctionalInterface
    public interface Source {
        Optional<TemplateModules.Template> template(String name);
    }

    /** Ergebnis: Plan-JSON plus was es ist (fürs Log). */
    public record Match(String json, String what) {
    }

    // Dateinamen der Dorfvorlagen in Minecraft 1.21.11 (gemessen im Server-JAR von dev01, ohne Zombie-Varianten).
    private static final Map<Biome, List<String>> VILLAGE = Map.of(
            Biome.PLAINS, List.of("plains_animal_pen_1", "plains_animal_pen_2", "plains_animal_pen_3",
                    "plains_armorer_house_1", "plains_big_house_1", "plains_butcher_shop_1", "plains_butcher_shop_2",
                    "plains_cartographer_1", "plains_fisher_cottage_1", "plains_fletcher_house_1", "plains_fountain_01",
                    "plains_large_farm_1", "plains_library_1", "plains_library_2", "plains_masons_house_1",
                    "plains_medium_house_1", "plains_medium_house_2", "plains_meeting_point_1", "plains_meeting_point_2",
                    "plains_meeting_point_3", "plains_shepherds_house_1", "plains_small_farm_1", "plains_small_house_1",
                    "plains_small_house_2", "plains_small_house_3", "plains_small_house_4", "plains_small_house_5",
                    "plains_small_house_6", "plains_small_house_7", "plains_small_house_8", "plains_stable_1",
                    "plains_stable_2", "plains_tannery_1", "plains_temple_3", "plains_temple_4", "plains_tool_smith_1",
                    "plains_weaponsmith_1"),
            Biome.DESERT, List.of("desert_animal_pen_1", "desert_animal_pen_2", "desert_armorer_1",
                    "desert_butcher_shop_1", "desert_cartographer_house_1", "desert_farm_1", "desert_farm_2",
                    "desert_fisher_1", "desert_fletcher_house_1", "desert_large_farm_1", "desert_library_1",
                    "desert_mason_1", "desert_medium_house_1", "desert_medium_house_2", "desert_meeting_point_1",
                    "desert_meeting_point_2", "desert_meeting_point_3", "desert_shepherd_house_1",
                    "desert_small_house_1", "desert_small_house_2", "desert_small_house_3", "desert_small_house_4",
                    "desert_small_house_5", "desert_small_house_6", "desert_small_house_7", "desert_small_house_8",
                    "desert_tannery_1", "desert_temple_1", "desert_temple_2", "desert_tool_smith_1",
                    "desert_weaponsmith_1"),
            Biome.SAVANNA, List.of("savanna_animal_pen_1", "savanna_animal_pen_2", "savanna_animal_pen_3",
                    "savanna_armorer_1", "savanna_butchers_shop_1", "savanna_butchers_shop_2", "savanna_cartographer_1",
                    "savanna_fisher_cottage_1", "savanna_fletcher_house_1", "savanna_large_farm_1",
                    "savanna_large_farm_2", "savanna_library_1", "savanna_mason_1", "savanna_medium_house_1",
                    "savanna_medium_house_2", "savanna_meeting_point_1", "savanna_meeting_point_2",
                    "savanna_meeting_point_3", "savanna_meeting_point_4", "savanna_shepherd_1", "savanna_small_farm",
                    "savanna_small_house_1", "savanna_small_house_2", "savanna_small_house_3", "savanna_small_house_4",
                    "savanna_small_house_5", "savanna_small_house_6", "savanna_small_house_7", "savanna_small_house_8",
                    "savanna_tannery_1", "savanna_temple_1", "savanna_temple_2", "savanna_tool_smith_1",
                    "savanna_weaponsmith_1", "savanna_weaponsmith_2"),
            Biome.SNOWY, List.of("snowy_animal_pen_1", "snowy_animal_pen_2", "snowy_armorer_house_1",
                    "snowy_armorer_house_2", "snowy_butchers_shop_1", "snowy_butchers_shop_2",
                    "snowy_cartographer_house_1", "snowy_farm_1", "snowy_farm_2", "snowy_fisher_cottage",
                    "snowy_fletcher_house_1", "snowy_library_1", "snowy_masons_house_1", "snowy_masons_house_2",
                    "snowy_medium_house_1", "snowy_medium_house_2", "snowy_medium_house_3", "snowy_meeting_point_1",
                    "snowy_meeting_point_2", "snowy_meeting_point_3", "snowy_shepherds_house_1", "snowy_small_house_1",
                    "snowy_small_house_2", "snowy_small_house_3", "snowy_small_house_4", "snowy_small_house_5",
                    "snowy_small_house_6", "snowy_small_house_7", "snowy_small_house_8", "snowy_tannery_1",
                    "snowy_temple_1", "snowy_tool_smith_1", "snowy_weapon_smith_1"),
            Biome.TAIGA, List.of("taiga_animal_pen_1", "taiga_armorer_2", "taiga_armorer_house_1",
                    "taiga_butcher_shop_1", "taiga_cartographer_house_1", "taiga_fisher_cottage_1",
                    "taiga_fletcher_house_1", "taiga_large_farm_1", "taiga_large_farm_2", "taiga_library_1",
                    "taiga_masons_house_1", "taiga_medium_house_1", "taiga_medium_house_2", "taiga_medium_house_3",
                    "taiga_medium_house_4", "taiga_meeting_point_1", "taiga_meeting_point_2",
                    "taiga_shepherds_house_1", "taiga_small_farm_1", "taiga_small_house_1", "taiga_small_house_2",
                    "taiga_small_house_3", "taiga_small_house_4", "taiga_small_house_5", "taiga_tannery_1",
                    "taiga_temple_1", "taiga_tool_smith_1", "taiga_weaponsmith_1", "taiga_weaponsmith_2"));

    /** Einzelbauten außerhalb der Dörfer: Name → Vorlagenpfad. */
    private static final Map<String, String> SPECIAL = new LinkedHashMap<>();

    static {
        SPECIAL.put("iglu", "igloo/top");
        SPECIAL.put("segelschiff", "shipwreck/with_mast");
        SPECIAL.put("schiffswrack", "shipwreck/with_mast_degraded");
        SPECIAL.put("endschiff", "end_city/ship");
        for (int i = 1; i <= 10; i++) {
            SPECIAL.put("ruinenportal_" + i, "ruined_portal/portal_" + i);
        }
        for (int i = 1; i <= 3; i++) {
            SPECIAL.put("riesenportal_" + i, "ruined_portal/giant_portal_" + i);
        }
        SPECIAL.put("wachturm", "pillager_outpost/watchtower");
        SPECIAL.put("zelt_1", "pillager_outpost/feature_tent1");
        SPECIAL.put("zelt_2", "pillager_outpost/feature_tent2");
        SPECIAL.put("fossil_wirbel", "fossil/spine_1");
        SPECIAL.put("fossil_schaedel", "fossil/skull_1");  // umlaut-ok: Bauteilname, ASCII wie alle Namen
        SPECIAL.put("uralte_stadt", "ancient_city/city_center/city_center_1");
        SPECIAL.put("unterwasserruine", "underwater_ruin/big_brick_1");
        // Mehr Varianten (2026-11-14, Wunsch „Monumente, Gebäude … massenhaft“): nur Vorlagen, die für sich ein
        // ganzer Bau sind – halbe Schiffe, Iglu-Keller und Turmteile der Endstadt sind Bruchstücke (Jigsaw-Teile) und
        // bleiben draußen. Die ersten Namen bleiben, wie sie waren (gespeicherte Pläne verweisen darauf).
        SPECIAL.put("segelschiff_2", "shipwreck/rightsideup_full");
        String[] wrecks = {"rightsideup_full_degraded", "sideways_full", "sideways_full_degraded", "upsidedown_full",
            "upsidedown_full_degraded"};
        for (int i = 0; i < wrecks.length; i++) {
            SPECIAL.put("schiffswrack_" + (i + 2), "shipwreck/" + wrecks[i]);
        }
        SPECIAL.put("wachturm_2", "pillager_outpost/watchtower_overgrown");
        for (int i = 2; i <= 4; i++) {
            SPECIAL.put("fossil_wirbel_" + i, "fossil/spine_" + i);
            SPECIAL.put("fossil_schaedel_" + i, "fossil/skull_" + i);  // umlaut-ok: Bauteilname
        }
        for (int i = 1; i <= 14; i++) {
            SPECIAL.put("netherfossil_" + i, "nether_fossils/fossil_" + i);
        }
        int ruin = 2;
        for (String kind : new String[] {"brick", "cracked", "mossy"}) {
            for (int n : new int[] {1, 2, 3, 8}) {
                String key = "underwater_ruin/big_" + kind + "_" + n;
                if (!key.equals("underwater_ruin/big_brick_1")) {
                    SPECIAL.put("unterwasserruine_" + ruin++, key);
                }
            }
        }
        for (int n = 4; n <= 7; n++) {
            SPECIAL.put("unterwasserruine_" + ruin++, "underwater_ruin/big_warm_" + n);
        }
        for (String kind : new String[] {"brick", "cracked", "mossy", "warm"}) {
            for (int n = 1; n <= 8; n++) {
                SPECIAL.put("kleine_ruine_" + kind + "_" + n, "underwater_ruin/" + kind + "_" + n);
            }
        }
    }

    /** Alle Namen mit diesem Anfang (Varianten einer Familie), in der Reihenfolge von SPECIAL. */
    private static List<String> family(String... prefixes) {
        List<String> out = new ArrayList<>();
        for (String n : SPECIAL.keySet()) {
            for (String pre : prefixes) {
                if (n.equals(pre) || n.startsWith(pre + "_")) {
                    out.add(n);
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    private record Special(String german, List<String> names) {
    }

    // Wörter normalisiert wie RequestHints.normalize (ä → ae usw.), daher umlaut-ok in diesem Block.
    private static final Map<String, Special> SPECIAL_WORDS = new LinkedHashMap<>();

    static {
        Special igloo = new Special("ein Iglu", List.of("iglu"));
        Special ship = new Special("ein Segelschiff", family("segelschiff"));
        Special wreck = new Special("ein Schiffswrack", family("schiffswrack"));
        Special endShip = new Special("ein Endschiff", List.of("endschiff"));
        List<String> portals = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            portals.add("ruinenportal_" + i);
        }
        Special ruinedPortal = new Special("ein Ruinenportal", List.copyOf(portals));
        Special giantPortal = new Special("ein Riesen-Ruinenportal",
                List.of("riesenportal_1", "riesenportal_2", "riesenportal_3"));
        Special tower = new Special("einen Wachturm", family("wachturm"));
        Special tent = new Special("ein Zelt", List.of("zelt_1", "zelt_2"));
        Special fossil = new Special("ein Fossil", family("fossil_wirbel", "fossil_schaedel"));  // umlaut-ok
        Special ruin = new Special("eine Ruine", family("unterwasserruine"));
        Special smallRuin = new Special("eine kleine Ruine", family("kleine_ruine"));
        Special netherFossil = new Special("ein Netherfossil", family("netherfossil"));
        for (String w : List.of("iglu", "iglus", "iglo", "iglos", "igloo")) {  // „Iglo“: so schreibt es Whisper (2026-11-04)
            SPECIAL_WORDS.put(w, igloo);
        }
        for (String w : List.of("schiff", "segelschiff", "piratenschiff", "galeone", "segler")) {
            SPECIAL_WORDS.put(w, ship);
        }
        for (String w : List.of("schiffswrack", "wrack")) {
            SPECIAL_WORDS.put(w, wreck);
        }
        for (String w : List.of("endschiff", "endstadtschiff")) {
            SPECIAL_WORDS.put(w, endShip);
        }
        for (String w : List.of("ruinenportal", "portalruine")) {
            SPECIAL_WORDS.put(w, ruinedPortal);
        }
        SPECIAL_WORDS.put("riesenportal", giantPortal);
        for (String w : List.of("wachturm", "aussenposten", "pluendererturm", "pluendereraussenposten")) { // umlaut-ok
            SPECIAL_WORDS.put(w, tower);
        }
        for (String w : List.of("zelt", "zelte")) {
            SPECIAL_WORDS.put(w, tent);
        }
        for (String w : List.of("fossil", "fossilien", "dinosaurierskelett", "dinoskelett", "dinosaurierknochen")) {
            SPECIAL_WORDS.put(w, fossil);
        }
        for (String w : List.of("ruine", "ruinen", "unterwasserruine")) {
            SPECIAL_WORDS.put(w, ruin);
        }
        for (String w : List.of("ruinchen", "mauerrest", "mauerreste")) {
            SPECIAL_WORDS.put(w, smallRuin);
        }
        for (String w : List.of("netherfossil", "netherfossilien", "netherskelett", "riesenskelett")) {
            SPECIAL_WORDS.put(w, netherFossil);
        }
    }

    private static final Map<String, Kind> KIND_WORDS = new LinkedHashMap<>();

    static {
        for (String w : List.of("dorfhaus", "dorfhaeuser", "bauernhaus", "landhaus", "haeuschen")) { // umlaut-ok
            KIND_WORDS.put(w, Kind.SMALL);
        }
        for (String w : List.of("villa", "gutshaus", "herrenhaus")) {
            KIND_WORDS.put(w, Kind.BIG);
        }
        for (String w : List.of("schmiede", "waffenschmiede", "werkzeugschmiede", "ruestungsschmiede", // umlaut-ok
                "ruestungsmacher", "schmied")) { // umlaut-ok
            KIND_WORDS.put(w, Kind.SMITH);
        }
        for (String w : List.of("kirche", "tempel", "kapelle", "dorfkirche")) {
            KIND_WORDS.put(w, Kind.TEMPLE);
        }
        for (String w : List.of("bibliothek", "buecherei")) { // umlaut-ok
            KIND_WORDS.put(w, Kind.LIBRARY);
        }
        for (String w : List.of("metzgerei", "metzger", "fleischerei")) {
            KIND_WORDS.put(w, Kind.BUTCHER);
        }
        for (String w : List.of("stall", "pferdestall", "stallungen")) {
            KIND_WORDS.put(w, Kind.STABLE);
        }
        for (String w : List.of("schaeferei", "schaeferhaus", "schaefer")) { // umlaut-ok
            KIND_WORDS.put(w, Kind.SHEPHERD);
        }
        for (String w : List.of("gerberei", "gerber")) {
            KIND_WORDS.put(w, Kind.TANNERY);
        }
        for (String w : List.of("fischerhuette", "fischerhaus", "fischer")) { // umlaut-ok
            KIND_WORDS.put(w, Kind.FISHER);
        }
        for (String w : List.of("kartografenhaus", "kartographenhaus", "kartograf", "kartograph")) {
            KIND_WORDS.put(w, Kind.CARTOGRAPHER);
        }
        for (String w : List.of("pfeilmacher", "pfeilmacherhaus", "bogenmacher")) {
            KIND_WORDS.put(w, Kind.FLETCHER);
        }
        for (String w : List.of("steinmetz", "steinmetzhaus", "maurerhaus")) {
            KIND_WORDS.put(w, Kind.MASON);
        }
        for (String w : List.of("feld", "acker", "weizenfeld", "dorffeld", "felder")) {
            KIND_WORDS.put(w, Kind.FARM);
        }
        for (String w : List.of("gehege", "tiergehege", "koppel", "weide")) {
            KIND_WORDS.put(w, Kind.PEN);
        }
        for (String w : List.of("dorfplatz", "marktplatz", "dorfbrunnen", "treffpunkt", "brunnen")) {
            KIND_WORDS.put(w, Kind.PLAZA);
        }
    }

    /** Allgemeine Hauswörter: streng nur mit Stilwort („Wüstenhaus“, „Haus im Schneestil“), locker immer. */
    static final Set<String> GENERIC_HOUSE = Set.of("haus", "huette", "wohnhaus", "unterkunft", "heim", // umlaut-ok
            "zuhause", "haeuser", "huetten", "gebaeude"); // umlaut-ok
    static final Set<String> VILLAGE_WORDS = Set.of("dorf", "doerfchen", "dorfes", "siedlung", "weiler", // umlaut-ok
            "doerfer", "dorfs"); // umlaut-ok
    private static final Set<String> COUNT_NOUNS = Set.of("haeuser", "haeusern", "gebaeude", "gebaeuden", // umlaut-ok
            "huetten"); // umlaut-ok

    private static final Map<String, Biome> BIOME_WORDS = new LinkedHashMap<>();
    /** Wortanfänge für Komposita: „wuestendorf“, „schneehaus“, „taigaschmiede“. Längere zuerst. */ // umlaut-ok
    private static final Map<String, Biome> BIOME_PREFIXES = new LinkedHashMap<>();

    static {
        for (String w : List.of("ebene", "ebenen", "wiese", "wiesen", "normal", "normales", "normalen", "klassisch",
                "klassisches", "klassischen", "standard")) {
            BIOME_WORDS.put(w, Biome.PLAINS);
        }
        for (String w : List.of("wueste", "wuesten", "wuestenstil", "wuestenartig", "aegyptisch")) { // umlaut-ok
            BIOME_WORDS.put(w, Biome.DESERT);
        }
        for (String w : List.of("savanne", "savannen", "savannenstil", "steppe", "afrikanisch", "afrikanisches")) {
            BIOME_WORDS.put(w, Biome.SAVANNA);
        }
        for (String w : List.of("schnee", "schneestil", "verschneit", "verschneites", "verschneiten", "verschneite",
                "winter", "winterlich", "winterliches", "winterlichen", "eis", "tundra")) {
            BIOME_WORDS.put(w, Biome.SNOWY);
        }
        for (String w : List.of("taiga", "taigastil", "nordisch", "nordisches", "nordischen", "skandinavisch",
                "skandinavisches", "skandinavischen")) {
            BIOME_WORDS.put(w, Biome.TAIGA);
        }
        BIOME_PREFIXES.put("wuesten", Biome.DESERT);  // umlaut-ok
        BIOME_PREFIXES.put("savannen", Biome.SAVANNA);
        BIOME_PREFIXES.put("schnee", Biome.SNOWY);
        BIOME_PREFIXES.put("winter", Biome.SNOWY);
        BIOME_PREFIXES.put("taiga", Biome.TAIGA);
        BIOME_PREFIXES.put("wiesen", Biome.PLAINS);
        BIOME_PREFIXES.put("eis", Biome.SNOWY);
    }

    private static final Set<String> FILLER = Set.of("bau", "baue", "bauen", "bitte", "mir", "uns", "mal", "ein",
            "eine", "einen", "einem", "einer", "eines", "das", "die", "der", "den", "dem", "des", "hier", "mit", "hin",
            "im", "in", "stil", "vom", "von", "wie", "aus", "auf", "fuer", "mich", "doch", "kannst", "du", "kann", // umlaut-ok
            "koenntest", "wuerdest", "mach", "mache", "errichte", "stell", "stelle", "setz", "setze", "so", // umlaut-ok
            "typisch", "typisches", "typischen", "typische", "echtes", "echten", "echte", "original", "originales",
            "minecraft", "vanilla", "schoen", "schoenes", "schoene", "schoenen", "huebsch", "huebsches", "neu", // umlaut-ok
            "neues", "neue", "neuen", "kleines", "kleine", "kleinen", "klein", "gross", "grosses", "grosse", "grossen",
            "riesig", "riesiges", "riesige", "riesigen", "und", "noch", "ganz", "ganzes", "ganze", "ganzen",
            "richtiges", "richtige", "richtigen", "nettes", "nette", "ja", "gern", "gerne", "jetzt", "sofort", "dort",
            "da", "dir", "ich", "will", "moechte", "haette", "haben", "lass", "lassen", "vor", "neben", "nebenan", // umlaut-ok
            "dorfstil", "art");
    private static final Set<String> BIG = Set.of("gross", "grosses", "grosse", "grossen", "riesig", "riesiges",
            "riesige", "riesigen", "mega");
    private static final Set<String> SMALL_WORDS = Set.of("klein", "kleines", "kleine", "kleinen", "winzig");

    /** Häuserfolge eines Dorfes: Wohnhäuser zuerst, dann was ein Dorf lebendig macht. */
    private static final List<Kind> VILLAGE_ORDER = List.of(Kind.SMALL, Kind.MEDIUM, Kind.SMITH, Kind.SMALL,
            Kind.LIBRARY, Kind.FARM, Kind.SMALL, Kind.TEMPLE, Kind.BUTCHER, Kind.MEDIUM, Kind.PEN, Kind.SHEPHERD,
            Kind.SMALL, Kind.FISHER, Kind.MASON, Kind.TANNERY, Kind.CARTOGRAPHER, Kind.FLETCHER);
    static final int MAX_VILLAGE = 16;
    private static final int STREET_HALF = 2;
    private static final int GAP = 2;

    private TemplateCatalog() {
    }

    /** Vorlagenpfad (ohne „minecraft:“) zu einem Bauteilnamen, sonst leer. */
    public static Optional<String> key(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String special = SPECIAL.get(n);
        if (special != null) {
            return Optional.of(special);
        }
        for (Biome b : Biome.values()) {
            if (VILLAGE.get(b).contains(n)) {
                String dir = Kind.PLAZA.matches(n) ? "town_centers" : "houses";
                return Optional.of("village/" + b.dir + "/" + dir + "/" + n);
            }
        }
        return Optional.empty();
    }

    /** Alle Namen – für die Messprobe und den Test, dass jede Vorlage im Server-JAR steht. */
    public static List<String> names() {
        List<String> out = new ArrayList<>(SPECIAL.keySet());
        for (Biome b : Biome.values()) {
            out.addAll(VILLAGE.get(b));
        }
        return out;
    }

    /** Stil zum Biom-Schlüssel von Bukkit („minecraft:snowy_taiga“ → SNOWY). */
    public static Biome biomeOf(String biomeKey) {
        String k = biomeKey == null ? "" : biomeKey.toLowerCase(Locale.ROOT);
        // cold_ocean/frozen_river: kalte Gewässer → Schneedorf. Vorher Wiesen (dev01 2026-11-04 16:07, Biom cold_ocean).
        if (k.contains("snowy") || k.contains("frozen") || k.contains("ice") || k.contains("grove")
                || k.contains("peaks") || k.contains("cold")) {
            return Biome.SNOWY;
        }
        if (k.contains("desert") || k.contains("badlands") || k.contains("beach")) {
            return Biome.DESERT;
        }
        if (k.contains("savanna")) {
            return Biome.SAVANNA;
        }
        if (k.contains("taiga")) {
            return Biome.TAIGA;
        }
        return Biome.PLAINS;
    }

    /**
     * @param strict true = vor dem LLM (nur reine Vorlagen-Aufträge), false = Rückfall nach dem LLM
     * @param seed   Auswahl unter gleichwertigen Vorlagen; gleicher Seed = gleicher Plan
     */
    public static Optional<Match> match(String prompt, Source source, Biome here, int maxExtent, long seed,
            boolean strict) {
        String norm = RequestHints.sizes(RequestHints.normalize(prompt));
        List<String> tokens = RequestHints.tokens(norm);
        Biome biome = null;
        boolean village = false;
        boolean generic = false;
        boolean big = false;
        boolean small = false;
        int count = 0;
        Kind kind = null;
        Special special = null;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.matches("\\d{1,2}") && i + 1 < tokens.size() && COUNT_NOUNS.contains(tokens.get(i + 1))) {
                count = Integer.parseInt(t);
                continue;
            }
            if (BIG.contains(t)) {
                big = true;
            }
            if (SMALL_WORDS.contains(t)) {
                small = true;
            }
            Biome b = BIOME_WORDS.get(t);
            String rest = t;
            if (b == null) {
                for (Map.Entry<String, Biome> e : BIOME_PREFIXES.entrySet()) {
                    if (t.startsWith(e.getKey()) && t.length() > e.getKey().length() + 2) {
                        b = e.getValue();
                        rest = t.substring(e.getKey().length());
                        break;
                    }
                }
            } else {
                rest = "";
            }
            if (b != null) {
                biome = b;
            }
            if (rest.isEmpty()) {
                continue;
            }
            rest = nearKnown(rest);
            if (VILLAGE_WORDS.contains(rest)) {
                village = true;
            } else if (SPECIAL_WORDS.containsKey(rest)) {
                special = special == null ? SPECIAL_WORDS.get(rest) : special;
            } else if (KIND_WORDS.containsKey(rest)) {
                kind = kind == null ? KIND_WORDS.get(rest) : kind;
            } else if (GENERIC_HOUSE.contains(rest) || COUNT_NOUNS.contains(rest)) {
                generic = true;
            } else if (rest.equals("stadt") && (tokens.contains("uralte") || tokens.contains("antike"))) {
                special = new Special("die Mitte einer uralten Stadt", List.of("uralte_stadt"));
            } else if (b != null && rest.equals("stil")) {
                continue;  // „wuestenstil“ ohne Eintrag in BIOME_WORDS // umlaut-ok
            } else if (strict && !FILLER.contains(rest) && !rest.equals("uralte") && !rest.equals("antike")
                    && !rest.matches("\\d{1,2}")) {
                return Optional.empty();
            }
        }
        Biome style = biome != null ? biome : here;
        Random rnd = new Random(seed);
        if (village) {
            int n = count > 0 ? count : big ? 10 : small ? 4 : 6;
            return village(source, style, Math.max(2, Math.min(MAX_VILLAGE, n)), maxExtent, rnd);
        }
        if (special != null) {
            String name = special.names().get(rnd.nextInt(special.names().size()));
            return single(source, name, special.german(), maxExtent);
        }
        if (kind == null && generic && (!strict || biome != null)) {
            kind = big ? Kind.BIG : small ? Kind.SMALL : Kind.MEDIUM;
        }
        if (kind == null) {
            return Optional.empty();
        }
        Optional<String> stem = pick(style, kind, rnd, Set.of());
        if (stem.isEmpty()) {
            return Optional.empty();
        }
        String label = kind.german + (biome != null ? " im " + style.german + "stil" : "");
        return single(source, stem.get(), label, maxExtent);
    }

    /**
     * Hörfehler der Spracherkennung abfangen: Ein Wort ab 5 Zeichen, das um ein Zeichen von einem Vorlagenwort abweicht
     * („Kirsche“ → kirche, „Bibliotek“ → bibliothek), zählt als dieses. Vorher ging „ein Iglo“ (Whisper, dev01
     * 2026-11-04 16:08) ans LLM, das einen massiven Eiszylinder baute. Kürzere Wörter nicht – „feld“/„geld“,
     * „zelt“/„welt“ lägen zu nah. Allgemeine Hauswörter auch nicht: „Haus“ bleibt Sache des LLM.
     */
    static String nearKnown(String word) {
        if (word.length() < 5 || VILLAGE_WORDS.contains(word) || SPECIAL_WORDS.containsKey(word)
                || KIND_WORDS.containsKey(word) || FILLER.contains(word) || GENERIC_HOUSE.contains(word)) {
            return word;
        }
        String best = word;
        int found = 0;
        for (java.util.Collection<String> set : List.of(VILLAGE_WORDS, SPECIAL_WORDS.keySet(), KIND_WORDS.keySet())) {
            for (String k : set) {
                if (k.length() >= 5 && Math.abs(k.length() - word.length()) <= 1 && distance(word, k) <= 1) {
                    best = k;
                    found++;
                }
            }
        }
        return found == 1 ? best : word;  // zwei gleich nahe Kandidaten: lieber keiner
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** Eine Vorlage zur gewünschten Art im Stil; ohne Treffer im Stil der Wiesen-Stil, beim Stall das Gehege. */
    static Optional<String> pick(Biome biome, Kind kind, Random rnd, Set<String> used) {
        for (Kind k : kind == Kind.STABLE ? List.of(Kind.STABLE, Kind.PEN) : kind == Kind.BIG
                ? List.of(Kind.BIG, Kind.MEDIUM) : List.of(kind)) {
            for (Biome b : biome == Biome.PLAINS ? List.of(biome) : List.of(biome, Biome.PLAINS)) {
                List<String> all = VILLAGE.get(b).stream().filter(k::matches).toList();
                if (all.isEmpty()) {
                    continue;
                }
                List<String> fresh = all.stream().filter(s -> !used.contains(s)).toList();
                List<String> from = fresh.isEmpty() ? all : fresh;
                return Optional.of(from.get(rnd.nextInt(from.size())));
            }
        }
        return Optional.empty();
    }

    private static Optional<Match> single(Source source, String name, String german, int maxExtent) {
        Optional<TemplateModules.Template> t = source.template(name);
        if (t.isEmpty()) {
            return Optional.empty();
        }
        Pos size = t.get().module().size();
        if (Math.max(size.x(), Math.max(size.y(), size.z())) > maxExtent) {
            return Optional.empty();
        }
        JsonArray steps = new JsonArray();
        steps.add(module(name, 0, -1 - t.get().ground(), 0, "front"));
        JsonObject plan = new JsonObject();
        plan.addProperty("design", "minecraft template " + key(name).orElse(name));
        plan.addProperty("reply", "Ich baue dir " + german + " nach der Minecraft-Vorlage.");
        plan.add("steps", steps);
        return Optional.of(new Match(plan.toString(), "vorlage " + name));
    }

    /**
     * Straße (5 breit) von z = 0 geradeaus, Häuser abwechselnd rechts und links mit der Vorderseite zur Straße,
     * 2 Blöcke Abstand nach den echten Maßen, Dorfplatz am Ende. Wird es länger als maxExtent, gibt es weniger
     * Häuser – die Antwort nennt die echte Zahl.
     */
    private static Optional<Match> village(Source source, Biome biome, int wanted, int maxExtent, Random rnd) {
        List<String> chosen = new ArrayList<>();
        List<TemplateModules.Template> templates = new ArrayList<>();
        java.util.HashSet<String> used = new java.util.HashSet<>();
        for (int i = 0; chosen.size() < wanted && i < wanted * 3; i++) {
            Optional<String> stem = pick(biome, VILLAGE_ORDER.get(i % VILLAGE_ORDER.size()), rnd, used);
            if (stem.isEmpty()) {
                continue;
            }
            Optional<TemplateModules.Template> t = source.template(stem.get());
            if (t.isEmpty()) {
                continue;
            }
            used.add(stem.get());
            chosen.add(stem.get());
            templates.add(t.get());
        }
        Optional<String> plazaName = pick(biome, Kind.PLAZA, rnd, Set.of());
        Optional<TemplateModules.Template> plaza = plazaName.flatMap(source::template);
        while (!chosen.isEmpty()) {
            Optional<Match> m = layout(biome, chosen, templates, plazaName.orElse(null), plaza.orElse(null),
                    maxExtent);
            if (m.isPresent()) {
                return m;
            }
            chosen.removeLast();
            templates.removeLast();
        }
        return Optional.empty();
    }

    private static Optional<Match> layout(Biome biome, List<String> names, List<TemplateModules.Template> templates,
            String plazaName, TemplateModules.Template plaza, int maxExtent) {
        JsonArray houses = new JsonArray();
        int right = 1;
        int left = 1;
        int minX = -STREET_HALF;
        int maxX = STREET_HALF;
        int maxY = 0;
        for (int i = 0; i < names.size(); i++) {
            TemplateModules.Template t = templates.get(i);
            Pos s = t.module().size();
            int y = -1 - t.ground();
            maxY = Math.max(maxY, y + s.y());
            // Nach der Drehung zur Seite liegt die Tiefe (z) quer zur Straße und die Breite (x) entlang.
            if (i % 2 == 0) {
                houses.add(module(names.get(i), STREET_HALF + 1, y, right, "left"));
                maxX = Math.max(maxX, STREET_HALF + s.z());
                right += s.x() + GAP;
            } else {
                int x = -STREET_HALF - s.z();
                houses.add(module(names.get(i), x, y, left, "right"));
                minX = Math.min(minX, x);
                left += s.x() + GAP;
            }
        }
        int length = Math.max(right, left);
        int depth = length;
        JsonArray steps = new JsonArray();
        // Erst freiräumen, dann der Weg eine Schicht tief (wie Dorfwege: Pfad statt Gras), dann die Häuser.
        steps.add(box("fill", "air", -STREET_HALF, 0, 0, STREET_HALF, 3, length - 1));
        steps.add(box("fill", biome == Biome.DESERT ? "smooth_sandstone" : "dirt_path", -STREET_HALF, -1, 0,
                STREET_HALF, -1, length - 1));
        steps.addAll(houses);
        if (plaza != null) {
            Pos ps = plaza.module().size();
            int px = -ps.x() / 2;
            steps.add(module(plazaName, px, -1 - plaza.ground(), length, "front"));
            minX = Math.min(minX, px);
            maxX = Math.max(maxX, px + ps.x() - 1);
            depth = length + ps.z();
            maxY = Math.max(maxY, -1 - plaza.ground() + ps.y());
        }
        if (Math.max(maxX - minX + 1, Math.max(depth, maxY + 2)) > maxExtent) {
            return Optional.empty();
        }
        JsonObject plan = new JsonObject();
        plan.addProperty("design", "minecraft village (" + biome.dir + ") with " + String.join(", ", names));
        plan.addProperty("reply", "Ich baue dir ein " + biome.german + "dorf mit " + names.size() + " Häusern aus den "
                + "Minecraft-Vorlagen – die Straße geht geradeaus vor dir los" + (plaza != null
                        ? ", am Ende der Dorfplatz." : "."));
        plan.add("steps", steps);
        return Optional.of(new Match(plan.toString(), "dorf " + biome.dir + " häuser=" + names.size()));
    }

    private static JsonObject module(String name, int x, int y, int z, String facing) {
        JsonObject s = new JsonObject();
        s.addProperty("op", "module");
        s.addProperty("name", name);
        s.add("pos", vec(x, y, z));
        s.addProperty("facing", facing);
        return s;
    }

    private static JsonObject box(String op, String block, int x0, int y0, int z0, int x1, int y1, int z1) {
        JsonObject s = new JsonObject();
        s.addProperty("op", op);
        s.addProperty("block", block);
        s.add("from", vec(x0, y0, z0));
        s.add("to", vec(x1, y1, z1));
        return s;
    }

    private static JsonArray vec(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }
}
