package com.blackn0va.baumeister.plan;

import com.blackn0va.baumeister.plan.BuildPlan.Op;
import com.blackn0va.baumeister.plan.BuildPlan.Step;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Harte Fakten aus dem deutschen Auftrag (Geschosszahl, Hochhaus, Material, Maschinenwunsch) – ohne LLM gelesen.
 *
 * Warum: Gemessen 2026-10-28 auf dev01 (Llama 3.1 8B) baute „40 stöckiges Hochhaus“ einen 40 Blöcke hohen
 * Vollzylinder und „20 stöckig“ 60 Logikgatter in die Fassade. Die Regel im System-Prompt allein reicht dem kleinen
 * Modell nicht. Deshalb stehen die Fakten zusätzlich ausdrücklich in der Nutzernachricht, und check() hält den Plan
 * dagegen – ein Widerspruch geht als Fehler in die vorhandene Reparaturrunde. Wer check() abschaltet, bekommt wieder
 * Gatter-Fassaden und Klötze statt Hochhäusern.
 *
 * Normalisierung und Material teilt sich die Klasse mit QuickOrders: Beide müssen „30-stöckiges“ und „Fichtenholz“
 * gleich verstehen, sonst baut der Schnellweg etwas anderes, als die Prüfung des LLM-Plans verlangt.
 */
public final class RequestHints {

    /** Stockwerke ohne Zahl im Auftrag, aber mit Hochhauswort: nur für den Rückfallplan. */
    static final int DEFAULT_FLOORS = 10;

    /**
     * Geschosszahlen als Zahlwort in normalisierter Form wie normalize sie liefert. Vorher kannte FLOORS nur Ziffern:
     * „Hochhaus mit zwanzig Stockwerken“ ließ „zwanzig“ als Nicht-Füllwort stehen, der Schnellweg lehnte ab, das LLM
     * brauchte Minuten, die Fakten nannten keine Geschosszahl und der Rückfallplan baute DEFAULT_FLOORS = 10.
     * Bewusst ohne „ein/eins“:
     * „ein Stockwerk“ ist meist Artikel oder ein Aufstockwunsch („bau noch ein Stockwerk drauf“), kein floors = 1.
     * Wer „ein“ hier aufnimmt, lässt check() Pläne gegen eine Geschosszahl prüfen, die niemand verlangt hat.
     */
    private static final Map<String, Integer> NUMBER_WORDS = numberWords();

    /**
     * Zahl (Ziffern oder Zahlwort) plus Geschosswort auf normalisiertem Text („20 stockwerken“, „3 etagen“,
     * „30 stoeckiges“, „zwanzig stockwerke“, „zehnstoeckig“; umlaut-ok). Höchstens vier Ziffern, damit Integer.parseInt
     * nie überläuft; alles über MAX_FLOORS wird ohnehin gekappt. Gelesen wird über {@link #floorsOf}. Längste Wörter
     * zuerst, sonst nimmt die Alternation bei „achtzehn stockwerke“ erst „acht“ und muss zurücklaufen.
     */
    static final Pattern FLOORS = Pattern.compile("\\b(\\d{1,4}|" + String.join("|", NUMBER_WORDS.keySet().stream()
            .sorted(java.util.Comparator.comparingInt(String::length).reversed()
                    .thenComparing(java.util.Comparator.naturalOrder())).toList()) + ") ?"
            + "(?:stockwerk(?:e|en)?|etage(?:n)?|geschoss(?:e|en)?|stoeckig(?:e|es|en|er)?)\\b"); // umlaut-ok

    /** Grundfläche „20 mal 24“ auf normalisiertem Text, siehe {@link #sizes}. */
    private static final Pattern SIZE_TIMES = Pattern.compile("\\b(\\d{1,3}) mal (\\d{1,3})\\b");

    /** Wörter, die ausdrücklich ein Hochhaus meinen – auch in Zusammensetzungen („Bürohochhaus“). */
    private static final Pattern HIGH_RISE_WORD = Pattern.compile(
            "hochhaus|hochhaeuser|wolkenkratzer|buerohaus|plattenbau"); // umlaut-ok: normalisierte Form

    /**
     * Wortstämme für einen Maschinenwunsch. Bewusst ohne „speicher“ (auch Dachboden) und „farm“ (auch Bauernhof):
     * Ein falscher Treffer würde Module in normalen Häusern erlauben – genau das, was check() verhindern soll.
     */
    private static final List<String> MACHINE_STEMS = List.of("maschine", "redstone", "schaltung", "gatter", "logik",
            "kolben", "fahrstuhl", "aufzug", "flipflop", "addierer", "rechenwerk", "mechanismus", "sortierer",
            "taktgeber",
            // 2026-10-31: Schlösser und Klingeln (dev01: 26 Pläne mit kombischloss/klingel verworfen, weil der
            // Auftrag kein Maschinenwort hatte). Nicht „schloss“ allein – das ist auch eine Burg.
            "zahlenschloss", "codeschloss", "kombinationsschloss", "kombischloss", "klingel", "tresor",
            "akkumulator", "prozessor");

    /** Deutsches Material (normalisiert) → Block. Nur Blöcke, die es in 1.21 sicher gibt. */
    private static final Map<String, String> MATERIALS = Map.ofEntries(
            Map.entry("quarz", "quartz_block"),
            Map.entry("stein", "stone"),
            Map.entry("bruchstein", "cobblestone"),
            Map.entry("pflasterstein", "cobblestone"),
            Map.entry("steinziegel", "stone_bricks"),
            Map.entry("ziegel", "bricks"),
            Map.entry("ziegelstein", "bricks"),
            Map.entry("backstein", "bricks"),
            Map.entry("beton", "light_gray_concrete"),
            Map.entry("sandstein", "sandstone"),
            Map.entry("andesit", "polished_andesite"),
            Map.entry("diorit", "polished_diorite"),
            Map.entry("granit", "polished_granite"),
            Map.entry("tiefenschiefer", "deepslate_bricks"),
            Map.entry("schwarzstein", "polished_blackstone_bricks"),
            Map.entry("prismarin", "prismarine_bricks"),
            Map.entry("obsidian", "obsidian"),
            Map.entry("terrakotta", "terracotta"),
            Map.entry("kupfer", "cut_copper"),
            Map.entry("eisen", "iron_block"),
            Map.entry("holz", "oak_planks"),
            Map.entry("eichenholz", "oak_planks"),
            Map.entry("eiche", "oak_planks"),
            Map.entry("fichtenholz", "spruce_planks"),
            Map.entry("fichte", "spruce_planks"),
            Map.entry("birkenholz", "birch_planks"),
            Map.entry("birke", "birch_planks"),
            Map.entry("tropenholz", "jungle_planks"),
            Map.entry("akazienholz", "acacia_planks"),
            Map.entry("schwarzeichenholz", "dark_oak_planks"),
            Map.entry("dunkeleichenholz", "dark_oak_planks"),
            Map.entry("mangrovenholz", "mangrove_planks"),
            Map.entry("kirschholz", "cherry_planks"),
            Map.entry("bambus", "bamboo_planks"),
            Map.entry("glas", "glass"));

    /** Endungen, die vor dem Nachschlagen abgeschnitten werden dürfen („Ziegeln“, „Steinen“, „Holzes“). */
    private static final List<String> ENDINGS = List.of("en", "es", "n", "s", "e");

    /**
     * @param floors        Geschosse, schon auf 1..MAX_FLOORS gekappt
     * @param highRise      ab 5 Geschossen oder mit Hochhauswort
     * @param material      Block nach „aus …“
     * @param machineWanted Maschinenwort oder Name eines Bauteils im Auftrag
     * @param forms         Bauformen, die ein Wort im Auftrag meint (Burg, Mauer, Brücke, Garten, Baum)
     */
    public record Hints(OptionalInt floors, boolean highRise, Optional<String> material, boolean machineWanted,
            Set<Op> forms) {

        /** Ohne Bauformen – Stand vor castle/wall/bridge/garden/tree, für Aufrufer, die Hints selbst bauen. */
        public Hints(OptionalInt floors, boolean highRise, Optional<String> material, boolean machineWanted) {
            this(floors, highRise, material, machineWanted, Set.of());
        }

        /**
         * Englisch wie der System-Prompt. Die skyscraper-Pflicht steht nur bei highRise darin – sonst würde ein
         * „Haus mit 3 Etagen“ zum Hochhaus. Bei 45 steht „max 45“ dabei, damit das Modell die Kappung nicht als
         * Lesefehler ansieht und 60 hineinschreibt.
         */
        public String facts() {
            List<String> parts = new ArrayList<>();
            floors.ifPresent(n -> parts.add("storeys = " + n + (n == PlanParser.MAX_FLOORS
                    ? " (max " + PlanParser.MAX_FLOORS + ")" : "")));
            if (highRise) {
                parts.add("high-rise: use exactly ONE skyscraper step"
                        + (floors.isPresent() ? " with floors = " + floors.getAsInt() : "")
                        + ", never build storeys yourself");
            }
            material.ifPresent(m -> parts.add((isWindow(m) ? "window block = " : "main block = ") + m));
            parts.add(machineWanted ? "machine wanted: yes, use op module from the MODULE LIBRARY if one fits"
                    : "machine wanted: no, do not use op module");
            // Wie beim Hochhaus: Ohne ausdrücklichen Hinweis mauert das kleine Modell Zinnen und Bögen Block für
            // Block nach, statt die Form zu nehmen, die der Compiler sicher rechnet. „one step for each“ statt
            // „use ONE step“: Vorher ergab „zwei Brücken“ eine Brücke und „Mauer um das Haus“ eine gerade Mauer
            // auf einer Seite (Prüfer-Befund 2026-10-29). Die Mauer ist eine Strecke, ein Ring braucht vier.
            for (Op f : forms) {
                String op = f.name().toLowerCase(Locale.ROOT);
                parts.add(op + " wanted: use op " + op + ", one step for each " + op
                        + (f == Op.WALL ? " (around something: one wall step per side)" : ""));
            }
            return "REQUEST FACTS: " + String.join("; ", parts) + ".";
        }
    }

    /** Fundstelle eines Bauteilnamens in der Tokenfolge. */
    record NameHit(String name, int start, int length) {
    }

    private RequestHints() {
    }

    /** zwei … neunzehn, die Zehner bis fünfzig und „einundzwanzig“ … „neunundvierzig“ (normalisiert). */
    private static Map<String, Integer> numberWords() {
        Map<String, Integer> words = new java.util.HashMap<>();
        // „ein“ nur als Teil von „einundzwanzig“ … – allein steht es nicht in der Tabelle (siehe NUMBER_WORDS).
        List<String> ones = List.of("ein", "zwei", "drei", "vier", "fuenf", "sechs", "sieben", // umlaut-ok
                "acht", "neun");
        for (int i = 1; i < ones.size(); i++) {
            words.put(ones.get(i), i + 1);
        }
        List<String> small = List.of("zehn", "elf", "zwoelf", "dreizehn", "vierzehn", "fuenfzehn", "sechzehn", // umlaut-ok
                "siebzehn", "achtzehn", "neunzehn");
        for (int i = 0; i < small.size(); i++) {
            words.put(small.get(i), 10 + i);
        }
        List<String> tens = List.of("zwanzig", "dreissig", "vierzig", "fuenfzig"); // umlaut-ok
        for (int t = 0; t < tens.size(); t++) {
            int ten = 20 + 10 * t;
            words.put(tens.get(t), ten);
            if (ten <= 40) {
                for (int i = 0; i < ones.size(); i++) {
                    words.put(ones.get(i) + "und" + tens.get(t), ten + i + 1);
                }
            }
        }
        return Map.copyOf(words);
    }

    /** Geschosszahl aus einem Treffer von {@link #FLOORS}: Ziffern oder Zahlwort. */
    static int floorsOf(Matcher m) {
        String n = m.group(1);
        Integer word = NUMBER_WORDS.get(n);
        return word != null ? word : Integer.parseInt(n);
    }

    /**
     * Klein, ä→ae, ö→oe, ü→ue, ß→ss, alles außer Buchstaben/Ziffern → ein Leerzeichen. So sind „30-stöckiges“,
     * „30 stöckiges“ und „30 stoeckiges“ dasselbe, und „XOR-Gatter“ wird zur Tokenfolge „xor gatter“ wie xor_gatter
     * (umlaut-ok).
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        // „×“ → „x“: Vorher wurde „20×24“ zu „20 24“, SIZE/AREA fanden nichts, die übrigen Zahlen schickten ans LLM.
        String s = text.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss").replace("×", "x");
        return s.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /**
     * „20 mal 24“ → „20x24“ auf normalisiertem Text, idempotent. Vorher ging „Burg 30 mal 30“ ans LLM, obwohl es einen
     * festen Bauweg gibt: „mal“ ist ein Füllwort, aber die Zahlen blieben als Nicht-Füllwörter stehen. Nicht in
     * normalize selbst, weil normalize auch Bauteilnamen und Material vergleicht; „bau mal ein Hochhaus“ bleibt
     * unberührt, weil nur „Zahl mal Zahl“ greift.
     */
    static String sizes(String normalized) {
        return SIZE_TIMES.matcher(normalized).replaceAll("$1x$2");
    }

    static List<String> tokens(String normalized) {
        return normalized.isEmpty() ? List.of() : Arrays.asList(normalized.split(" "));
    }

    /** Material zu einem normalisierten Wort, auch gebeugt („ziegeln“ → bricks). */
    static Optional<String> material(String word) {
        String direct = MATERIALS.get(word);
        if (direct != null) {
            return Optional.of(direct);
        }
        for (String end : ENDINGS) {
            if (word.length() > end.length() + 2 && word.endsWith(end)) {
                String hit = MATERIALS.get(word.substring(0, word.length() - end.length()));
                if (hit != null) {
                    return Optional.of(hit);
                }
            }
        }
        return Optional.empty();
    }

    /** Glas gehört bei Hochhäusern in window_block, nicht in die Fassade. */
    static boolean isWindow(String blockId) {
        return blockId.endsWith("glass") || blockId.endsWith("glass_pane");
    }

    static boolean isHighRiseWord(String token) {
        return HIGH_RISE_WORD.matcher(token).find();
    }

    /**
     * Bauteilnamen als ganze Tokenfolge (xor_gatter ↔ „xor gatter“) oder zusammengeschrieben („xorgatter“). An jeder
     * Stelle gewinnt der längste Name, damit „lampe rot“ nicht zugleich „lampe“ ist.
     */
    static List<NameHit> findNames(List<String> tokens, Collection<String> names) {
        List<String[]> split = new ArrayList<>();
        List<String> joined = new ArrayList<>();
        List<String> original = new ArrayList<>();
        for (String name : names) {
            String n = normalize(name.replace('_', ' '));
            if (n.isEmpty()) {
                continue;
            }
            split.add(n.split(" "));
            joined.add(n.replace(" ", ""));
            original.add(name);
        }
        List<NameHit> hits = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            int bestLen = 0;
            String best = null;
            for (int k = 0; k < split.size(); k++) {
                String[] parts = split.get(k);
                int len = 0;
                if (i + parts.length <= tokens.size()
                        && Arrays.equals(parts, tokens.subList(i, i + parts.length).toArray(String[]::new))) {
                    len = parts.length;
                } else if (tokens.get(i).equals(joined.get(k))) {
                    len = 1;
                }
                if (len > bestLen) {
                    bestLen = len;
                    best = original.get(k);
                }
            }
            if (best != null) {
                hits.add(new NameHit(best, i, bestLen));
                i += bestLen;
            } else {
                i++;
            }
        }
        return hits;
    }

    public static Hints parse(String prompt, Collection<String> moduleNames) {
        String n = normalize(prompt);
        OptionalInt floors = OptionalInt.empty();
        Matcher m = FLOORS.matcher(n);
        if (m.find()) {
            floors = OptionalInt.of(Math.clamp(floorsOf(m), 1, PlanParser.MAX_FLOORS));
        }
        List<String> tokens = tokens(n);
        boolean highRiseWord = tokens.stream().anyMatch(RequestHints::isHighRiseWord);
        boolean highRise = highRiseWord || floors.isPresent() && floors.getAsInt() >= 5;
        Optional<String> material = Optional.empty();
        for (int i = 0; i < tokens.size() && material.isEmpty(); i++) {
            if (!tokens.get(i).equals("aus")) {
                continue;
            }
            // „aus Fichtenholz“, auch mit einem Beiwort dazwischen („aus hellem Quarz“)
            for (int k = i + 1; k <= i + 2 && k < tokens.size() && material.isEmpty(); k++) {
                material = material(tokens.get(k));
            }
        }
        // Die Wörter der Maschinenhalle und der Prozessor-Werkstatt zählen als ganze Wörter mit, aus MachineHall selbst:
        // Vorher fehlten „fabrik“ und „computer“ hier, und der Plan zu „bau mir eine Fabrik“ verlor in PlanReview alle
        // Anlagen als „Verzierung“ (Lehrset-Generator 2026-10-31, 15 Pläne). Ganze Wörter statt Stämme, damit
        // „Fabrikantenvilla“ kein Maschinenwunsch wird. Wer hier eine eigene Liste führt, lässt beide wieder
        // auseinanderlaufen (RequestHintsTest.everyMachineHallWordIsAMachineWish).
        boolean machine = tokens.stream()
                .anyMatch(t -> t.equals("takt") || MACHINE_STEMS.stream().anyMatch(t::contains)
                        || MachineHall.WORDS.contains(t) || MachineHall.CPU_WORDS.contains(t))
                || !findNames(tokens, moduleNames).isEmpty();
        // forms als EnumSet (feste Reihenfolge der Ops): Set.copyOf würfelt die Reihenfolge, der Text an das LLM soll
        // aber bei gleichem Auftrag gleich bleiben.
        return new Hints(floors, highRise, material, machine, Collections.unmodifiableSet(QuickForms.formsIn(tokens)));
    }

    /**
     * Widersprüche zwischen Plan und Auftrag, englisch für die Reparaturrunde. Leer = in Ordnung. Zwei Fälle, gemessen
     * 2026-10-28: Hochhaus ohne skyscraper-Schritt, falsche Geschosszahl. Module ohne Maschinenwunsch standen hier bis
     * 2026-10-31 als dritter Fall und kosteten 26 verworfene Pläne – jetzt {@link #unwantedModules}, und PlanReview
     * entscheidet ohne LLM (annehmen oder entfernen).
     */
    public static List<String> check(BuildPlan plan, Hints hints) {
        List<String> problems = new ArrayList<>();
        List<Step> steps = plan.steps();
        boolean hasSkyscraper = steps.stream().anyMatch(s -> s.op() == Op.SKYSCRAPER);
        if (hints.highRise() && !hasSkyscraper) {
            problems.add("The player asked for a high-rise"
                    + (hints.floors().isPresent() ? " with " + hints.floors().getAsInt() + " storeys" : "")
                    + ", but the plan has no skyscraper step. Use exactly ONE skyscraper step"
                    + (hints.floors().isPresent() ? " with floors = " + hints.floors().getAsInt() : "")
                    + " and add only details around it; never build storeys from walls, cylinders or modules.");
        }
        if (hints.floors().isPresent()) {
            int want = hints.floors().getAsInt();
            for (int i = 0; i < steps.size(); i++) {
                Step s = steps.get(i);
                if (s.op() == Op.SKYSCRAPER && s.floors() != want) {
                    problems.add("Step " + (i + 1) + " (skyscraper) has floors = " + s.floors()
                            + ", but the player asked for " + want + " storeys"
                            + (want == PlanParser.MAX_FLOORS ? " (max " + PlanParser.MAX_FLOORS + ")" : "")
                            + ". Set floors to " + want + ".");
                }
            }
        }
        return problems;
    }

    /** Modul-Schritte (0-basiert), obwohl kein Maschinenwunsch erkannt wurde; leer bei Maschinenwunsch. */
    public static List<Integer> unwantedModules(BuildPlan plan, Hints hints) {
        if (hints.machineWanted()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < plan.steps().size(); i++) {
            if (plan.steps().get(i).op() == Op.MODULE) {
                out.add(i);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Bewährte Variante nach dem letzten gescheiterten Versuch: 16×16-skyscraper mit der verlangten Geschosszahl und
     * dem verlangten Material. Läuft danach im BuildService durch compile (Config-Limits) und den Überbau-Schutz wie
     * jeder LLM-Plan – der Rückfall ist keine Abkürzung an den Prüfungen vorbei.
     */
    public static BuildPlan defaultPlan(Hints hints) throws PlanException {
        int floors = hints.floors().orElse(DEFAULT_FLOORS);
        String block = hints.material().filter(m -> !isWindow(m)).orElse(QuickOrders.DEFAULT_FACADE);
        String window = hints.material().filter(RequestHints::isWindow).orElse(QuickOrders.DEFAULT_WINDOW);
        return PlanParser.parse(QuickOrders.skyscraperJson(QuickOrders.DEFAULT_SIDE, QuickOrders.DEFAULT_SIDE, floors,
                block, window, "Ich baue dir die bewährte Variante: ein Hochhaus mit " + floors + " Stockwerken."));
    }
}
