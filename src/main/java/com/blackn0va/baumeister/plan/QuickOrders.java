package com.blackn0va.baumeister.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aufträge, für die es kein LLM braucht: „Hochhaus mit 20 Stockwerken“, „bau ein XOR-Gatter“ und die Bauformen aus
 * QuickForms („bau eine Burg“, „eine Brücke über 20 Blöcke“). Daraus wird
 * direkt ein Plan, der denselben Weg geht wie ein LLM-Plan (Parser, Compiler, Überbau-Schutz, Rechteprüfung).
 *
 * Warum: Hochhaus und Maschine sind genau die Aufträge, an denen das kleine Modell gemessen scheiterte (dev01
 * 2026-10-28: Vollzylinder statt Hochhaus, Gatter in der Fassade), und für beide gibt es mit op skyscraper bzw. der
 * Maschinenbibliothek einen festen Bauweg. Ohne LLM gehen sie in Millisekunden statt Minuten und auch dann, wenn
 * der Tunnel zum LLM-PC unten ist.
 *
 * Bewusst eng: Ein Treffer nur, wenn nach dem Erkannten ausschließlich Füllwörter übrig sind. „Hochhaus mit 20
 * Stockwerken und einem Park davor“ bleibt beim LLM – lieber langsam richtig als schnell ohne Park. Wer die
 * Füllwortprüfung lockert, baut Aufträge mit Zusatzwünschen still ohne diese Wünsche.
 */
public final class QuickOrders {

    static final String DEFAULT_FACADE = "light_gray_concrete";
    static final String DEFAULT_WINDOW = "light_blue_stained_glass";
    static final int DEFAULT_SIDE = 16;
    private static final int MIN_SIDE = 5;
    private static final int MAX_SIDE = 48;
    /** Platz über dem Dach (Brüstung und etwas Luft), abgezogen von max-ausdehnung vor der Geschossrechnung. */
    private static final int HEADROOM = 6;

    private static final Set<String> FILLER = Set.of("bau", "baue", "bitte", "mir", "uns", "mal", "ein", "eine",
            "einen", "das", "die", "der", "hier", "mit", "hin");
    private static final Set<String> TOWER_WORDS = Set.of("hochhaus", "wolkenkratzer",
            "buerohaus", "plattenbau", "hochhaeuser", "buerohaeuser", "plattenbauten", "tuerme"); // umlaut-ok
    /** Mehrere Hochhäuser („3 mal 20 Stockwerke“, „drei Hochhäuser“): höchstens so viele, Abstand dazwischen. */
    static final int MAX_TOWERS = 8;
    static final int TOWER_GAP = 4;
    private static final Pattern SIZE = Pattern.compile("\\b(\\d{1,3}) ?x ?(\\d{1,3})\\b");

    private QuickOrders() {
    }

    /**
     * Wie unten, zusätzlich die Maschinenhalle (MachineHall): „bau mir eine fette Maschine“ – nur Maschinenwort,
     * Größenwort und Füllwörter. Braucht die Bibliothek selbst, weil die Aufstellung die Maße der Anlagen kennt.
     */
    public static Optional<String> match(String prompt, ModuleLibrary modules, int maxExtent) {
        Optional<String> special = bunker(prompt, maxExtent);
        if (special.isEmpty()) {
            special = pool(prompt, maxExtent);
        }
        if (special.isEmpty()) {
            special = calculator(prompt);
        }
        if (special.isEmpty()) {
            special = decoder(prompt);
        }
        if (special.isEmpty()) {
            special = farm(prompt);
        }
        if (special.isEmpty()) {
            special = display(prompt);
        }
        if (special.isEmpty()) {
            special = hole(prompt);
        }
        if (special.isPresent()) {
            return special;
        }
        Optional<String> cpu = cpuWorkshop(prompt, modules, maxExtent);
        if (cpu.isPresent()) {
            return cpu;
        }
        Optional<String> hall = machineHall(prompt, modules, maxExtent);
        if (hall.isPresent()) {
            return hall;
        }
        Set<String> names = new java.util.HashSet<>();
        for (ModuleLibrary.Module m : modules.all()) {
            names.add(m.name());
        }
        Optional<String> commandMachine = commandMachine(prompt, names);
        if (commandMachine.isPresent()) {
            return commandMachine;
        }
        return match(prompt, names, maxExtent);
    }

    /** „… befehlsknopf … mit dem Befehl …“ – der Befehl ist der ganze Rest des Auftrags (Plan B7). */
    private static final Pattern COMMAND_ORDER = Pattern.compile(
            "(?iu)\\b(befehlsknopf|befehlskette)\\b.*?\\bmit\\s+(?:dem\\s+|den\\s+)?befehl(?:en)?\\s*[:=]?\\s*(.+)$");

    /**
     * Befehlsknopf oder Befehlskette mit eigenem Befehl („bau einen Befehlsknopf mit dem Befehl time set day“, „… eine
     * Befehlskette mit den Befehlen say a; say b; say c“): das Bauteil plus je Befehl ein Schritt, der den Platzhalter
     * überschreibt (CommandMachines.overrideBlocks). Diese Schritte sind Plantext – jeder Befehl geht durch die
     * Bestätigung des Admins (Plan B5), wer nicht Admin ist, bekommt ihn nie. Ein Befehl, den CommandText ablehnt
     * („~“ mit Versatz dreht nicht mit, „^“), kommt nicht hinein; das Bauteil schon, mit Hinweis auf /bm befehlsblock
     * (dort geht „~“, der Block steht schon). Mehr als drei Befehle oder mehrere für den Knopf: leer → das LLM.
     */
    static Optional<String> commandMachine(String prompt, Set<String> moduleNames) {
        Matcher m = COMMAND_ORDER.matcher(prompt.strip());
        if (!m.find()) {
            return Optional.empty();
        }
        String name = m.group(1).toLowerCase(java.util.Locale.ROOT);
        if (!moduleNames.contains(name)) {
            return Optional.empty();
        }
        List<String> commands = new ArrayList<>();
        for (String part : m.group(2).split(";")) {
            String c = part.strip();
            if (c.length() >= 2 && (c.startsWith("\"") && c.endsWith("\"") || c.startsWith("„") && c.endsWith("“"))) {
                c = c.substring(1, c.length() - 1).strip();
            }
            if (c.startsWith("/")) {
                c = c.substring(1).strip();
            }
            if (!c.isEmpty()) {
                commands.add(c);
            }
        }
        String label = name.equals("befehlsknopf") ? "einen Befehlsknopf" : "eine Befehlskette";
        String refused = null;
        for (String c : commands) {
            try {
                CommandText.check(c);
            } catch (PlanException e) {
                refused = "„" + c + "“ setze ich nicht ein: " + e.getMessage();
                break;
            }
        }
        if (refused != null) {
            JsonObject module = moduleStep(name);
            return Optional.of(plan("module " + name, "Ich baue dir " + label + " mit dem Platzhalter-Befehl. "
                    + refused + " Schau danach auf den Befehlsblock und tippe /bm befehlsblock <befehl>.", module));
        }
        List<String> blocks = CommandMachines.overrideBlocks(name, commands);
        if (blocks == null) {
            return Optional.empty();
        }
        JsonObject plan = new JsonObject();
        plan.addProperty("design", "module " + name + " with " + blocks.size() + " command(s)");
        plan.addProperty("reply", "Ich baue dir " + label + " mit " + (blocks.size() == 1 ? "deinem Befehl"
                : "deinen " + blocks.size() + " Befehlen") + " – du bestätigst sie gleich per Klick.");
        JsonArray steps = new JsonArray();
        steps.add(moduleStep(name));
        List<Pos> at = new ArrayList<>();
        at.add(CommandMachines.IMPULSE);
        at.addAll(CommandMachines.CHAIN);
        for (int i = 0; i < blocks.size(); i++) {
            JsonObject set = new JsonObject();
            set.addProperty("op", "set");
            set.addProperty("block", blocks.get(i));
            set.add("pos", vec(at.get(i).x(), at.get(i).y(), at.get(i).z()));
            steps.add(set);
        }
        plan.add("steps", steps);
        return Optional.of(plan.toString());
    }

    private static JsonObject moduleStep(String name) {
        JsonObject step = new JsonObject();
        step.addProperty("op", "module");
        step.addProperty("name", name);
        step.add("pos", vec(0, 0, 0));
        step.addProperty("facing", "front");
        return step;
    }

    /** Bunker und Fort Knox (Bunker): unter und über der Erde, ohne LLM. */
    static Optional<String> bunker(String prompt, int maxExtent) {
        return Bunker.parse(RequestHints.tokens(RequestHints.normalize(prompt)), FILLER)
                .flatMap(o -> Bunker.plan(o, maxExtent));
    }

    /** Pool (Pool): Becken mit Wasser auf Bodenhöhe, Terrasse, Leiter, Licht – ohne LLM. */
    static Optional<String> pool(String prompt, int maxExtent) {
        return Pool.parse(RequestHints.tokens(RequestHints.normalize(prompt)), FILLER)
                .flatMap(s -> Pool.plan(s, maxExtent));
    }

    /** 2-Bit-Addierer: im Logik-Compiler vorhanden und im Spiel geprüft (LogicProbe, 2026-10-29). */
    static final String ADDER = "S0 = A0 ^ B0; S1 = A1 ^ B1 ^ (A0 & B0); C = A1&B1 | (A1|B1)&A0&B0";
    static final Set<String> CALCULATOR = Set.of("taschenrechner", "rechner", "addierer", "rechenmaschine",
            "addiermaschine", "rechenwerk");
    /**
     * Was ein Spieler zum Taschenrechner dazuschreibt, ohne etwas anderes zu wollen – auch der Hinweis aufs Internet
     * (dev01 2026-10-30: „der auch tatsächlich rechnen kann, mit redstone. schaue im internet bitte nach einem plan“).
     */
    // Normalisierte Formen (ä → ae usw.) wie RequestHints.normalize – daher umlaut-ok.
    private static final Set<String> CALCULATOR_EXTRA = Set.of("der", "die", "das", "auch", "tatsaechlich", // umlaut-ok
            "wirklich", "echt", "richtig", "rechnen", "rechnet", "kann", "koennen", "funktioniert", // umlaut-ok
            "funktionierende", "funktionierenden", "funktionierender", "funktionierendes", "redstone",
            "aus", "plus", "addieren", "addiert", "zahlen", "bit", "2", "zwei", "schau", "schaue", "im",
            "internet", "nach", "einem", "plan", "bauplan", "nutze", "fuer", "den", "und", "kleinen", // umlaut-ok
            "kleiner", "kleine", "einfachen", "einfacher", "einfache");
    /**
     * „in 3D“, „übereinander“, „gestapelt“ (Wunsch 2026-11-07): Logik in
     * Stockwerken (LogicCircuit, „3d“ vor der Formel). Normalisierte Formen – daher umlaut-ok. Keine nackte „3“: die
     * steht auch in „3 zu 8“; „3-D“ zieht {@link #joinedTokens} zu „3d“ zusammen.
     */
    static final Set<String> STACKED = Set.of("3d", "dreidimensional", "stockwerke", "stockwerken", "etagen", "ebenen",
            "uebereinander", "gestapelt", "gestapelte", "gestapelten", "gestapelter", "vertikal", "vertikale", // umlaut-ok
            "vertikalen", "senkrecht", "hochkant", "turm", "kompakt", "kompakte", "kompakten", "platzsparend",
            "platzsparende", "platzsparenden", "mehrstoeckig", "mehrstoeckige", "mehrstoeckigen"); // umlaut-ok
    /** Bindewörter um die 3D-Angabe („in 3D“, „auf mehreren Ebenen“, „als Turm“) – erlaubt, wollen nichts. */
    private static final Set<String> STACKED_GLUE = Set.of("in", "auf", "als", "mehreren", "mehrere", "form");

    /**
     * Tokens wie RequestHints, „3 d“ (aus „3-D“, „3 D“) als ein Wort „3d“. Vorher stand „3“ selbst in der 3D-Liste,
     * und „bau einen 3 zu 8 Dekoder“ wurde ungefragt gestapelt.
     */
    static List<String> joinedTokens(String prompt) {
        List<String> raw = RequestHints.tokens(RequestHints.normalize(prompt));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            if (raw.get(i).equals("3") && i + 1 < raw.size() && raw.get(i + 1).equals("d")) {
                out.add("3d");
                i++;
            } else {
                out.add(raw.get(i));
            }
        }
        return out;
    }

    /**
     * Wo welche Lampe steht, aus der übersetzten Schaltung (LogicCircuit.lampsByFloor), nicht aus dem Gedächtnis:
     * Wie viele Ausgänge auf ein Stockwerk kommen, legt der Logik-Compiler fest. Kurz, weil BuildService.acceptPlan
     * die Antwort auf 200 Zeichen kürzt.
     */
    static String lampOrder(com.blackn0va.baumeister.logic.LogicCircuit.Circuit circuit) {
        java.util.SortedMap<Integer, List<String>> floors =
                com.blackn0va.baumeister.logic.LogicCircuit.lampsByFloor(circuit);
        if (floors.size() == 1) {
            return "Lampen hinten von links: " + String.join(", ", floors.get(floors.firstKey()));
        }
        List<String> parts = new ArrayList<>();
        floors.values().forEach(names -> parts.add(String.join(", ", names)));
        boolean single = floors.values().stream().allMatch(names -> names.size() == 1);
        return floors.size() + " Stockwerke, Lampen hinten" + (single ? "" : " von links") + ", unten zuerst: "
                + String.join(" | ", parts);
    }

    /**
     * „bau mir einen Taschenrechner, der rechnen kann“: der 2-Bit-Addierer aus dem Logik-Compiler. Vorher ging das ans
     * LLM (dev01 2026-10-30, fünf Anläufe mit Llama 3.1 8B): Volladdierer und Halbaddierer übereinander, Gatter ohne
     * Verbindung, ein „Display“ aus Glasscheiben – nichts davon rechnete. Größere Zahlen gehen nicht: Der
     * Logik-Compiler hat höchstens 5 Eingänge, ein 2-Bit-Addierer braucht 4.
     */
    static Optional<String> calculator(String prompt) {
        boolean calc = false;
        boolean stacked = false;
        for (String t : joinedTokens(prompt)) {
            if (CALCULATOR.contains(t)) {
                calc = true;
            } else if (STACKED.contains(t)) {
                stacked = true;
            } else if (!FILLER.contains(t) && !CALCULATOR_EXTRA.contains(t) && !STACKED_GLUE.contains(t)) {
                return Optional.empty();
            }
        }
        if (!calc) {
            return Optional.empty();
        }
        String expr = stacked ? "3d " + ADDER : ADDER;
        // Hebel- und Lampenfolge aus der übersetzten Schaltung, nicht aus dem Gedächtnis: Die Reihenfolge der Eingänge
        // legt der Logik-Compiler fest. Kurz, weil BuildService.acceptPlan die Antwort auf 200 Zeichen kürzt.
        com.blackn0va.baumeister.logic.LogicCircuit.Circuit circuit;
        try {
            circuit = com.blackn0va.baumeister.logic.LogicCircuit.compile(expr);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String levers = String.join(", ", circuit.inputs());
        JsonObject step = new JsonObject();
        step.addProperty("op", "logic");
        step.addProperty("expr", expr);
        JsonArray pos = new JsonArray();
        pos.add(0);
        pos.add(0);
        pos.add(0);
        step.add("pos", pos);
        step.addProperty("facing", "front");
        JsonArray steps = new JsonArray();
        steps.add(step);
        JsonObject plan = new JsonObject();
        plan.addProperty("design", "2-bit adder: A (A1 A0) + B (B1 B0) = C S1 S0");
        plan.addProperty("reply", "Taschenrechner: A + B, je 0–3 (A0/B0 Einer, A1/B1 Zweier). Hebel links von vorn: "
                + levers + ". " + lampOrder(circuit) + " = 1, 2, 4.");
        plan.add("steps", steps);
        return Optional.of(plan.toString());
    }

    /**
     * Farm-Wörter, die keinen Bauteilnamen treffen („XP-Farm“ trifft xp_farm schon über den Namen): „Mobfarm“,
     * „Erfahrungsfarm“, „Monsterfarm“ … → xp_farm; „Weizen-Farm“, „Golemfarm“, „iron farm“ … → die Farmen vom
     * 2026-11-07 (Wunsch). Eng wie der Taschenrechner: nur
     * Farm-Wort, Wörter genau dieser Farm, Füllwörter. Zwei Farmen in einem Auftrag („Weizen- und Kürbisfarm“) gehen
     * ans LLM – vorher gewann der letzte Treffer der Map, deren Reihenfolge Map.ofEntries nicht festlegt.
     */
    static final Map<String, String> FARMS = Map.ofEntries(
            Map.entry("xpfarm", "xp_farm"), Map.entry("expfarm", "xp_farm"), Map.entry("mobfarm", "xp_farm"),
            Map.entry("monsterfarm", "xp_farm"), Map.entry("erfahrungsfarm", "xp_farm"),
            Map.entry("mobgrinder", "xp_farm"), Map.entry("mobfalle", "xp_farm"), Map.entry("levelfarm", "xp_farm"),
            Map.entry("weizenfarm", "weizenfarm"), Map.entry("wheatfarm", "weizenfarm"),
            Map.entry("kornfarm", "weizenfarm"), Map.entry("getreidefarm", "weizenfarm"),
            Map.entry("eisenfarm", "eisenfarm"), Map.entry("ironfarm", "eisenfarm"), Map.entry("golemfarm", "eisenfarm"),
            Map.entry("huehnerfarm", "huehnerfarm"), Map.entry("huhnfarm", "huehnerfarm"), // umlaut-ok
            Map.entry("eierfarm", "huehnerfarm"), Map.entry("chickenfarm", "huehnerfarm"), // umlaut-ok
            Map.entry("eggfarm", "huehnerfarm"), // umlaut-ok
            Map.entry("kuerbisfarm", "kuerbisfarm"), Map.entry("pumpkinfarm", "kuerbisfarm"), // umlaut-ok
            Map.entry("melonenfarm", "melonenfarm"), Map.entry("melonfarm", "melonenfarm"),
            Map.entry("bambusfarm", "bambusfarm"), Map.entry("bamboofarm", "bambusfarm"),
            Map.entry("kaktusfarm", "kaktusfarm"), Map.entry("kakteenfarm", "kaktusfarm"),
            Map.entry("cactusfarm", "kaktusfarm"),
            Map.entry("bruchsteingenerator", "bruchsteingenerator"), Map.entry("steingenerator", "bruchsteingenerator"),
            Map.entry("cobblestonegenerator", "bruchsteingenerator"),
            Map.entry("cobblegenerator", "bruchsteingenerator"), Map.entry("bruchsteinfarm", "bruchsteingenerator"),
            Map.entry("cobblestonefarm", "bruchsteingenerator"));
    /** Was zu jeder Farm passt, ohne etwas anderes zu wollen. */
    private static final Set<String> FARM_EXTRA = Set.of("farm", "eine", "einen", "fuer", "zum", "kleine", // umlaut-ok
            "kleinen", "grosse", "grossen", "mich", "dich", "bitte", "funktionierende", "funktionierenden",
            "funktionierender", "einfache", "einfachen", "einfacher", "richtige", "richtigen", "automatische",
            "automatischen", "automatischer");
    /**
     * Wortteile genau einer Farm, wenn der Spieler das Farmwort trennt („Eisen-Farm“, „iron farm“) oder ergänzt
     * („Mobfarm ohne Spawner“). Je Farm statt in FARM_EXTRA: Sonst baute „Mob-Weizenfarm“ still die Weizenfarm.
     */
    private static final Map<String, Set<String>> FARM_WORDS = Map.of(
            "xp_farm", Set.of("xp", "exp", "mob", "mobs", "monster", "erfahrung", "erfahrungs", "level", "leveln",
                    "grinder", "ohne", "spawner"),
            "weizenfarm", Set.of("weizen", "wheat", "korn", "getreide"),
            "eisenfarm", Set.of("eisen", "iron", "golem", "golems", "eisengolem", "eisengolems"),
            "huehnerfarm", Set.of("huehner", "huhn", "eier", "chicken", "egg", "eggs"), // umlaut-ok
            "kuerbisfarm", Set.of("kuerbis", "kuerbisse", "pumpkin"), // umlaut-ok
            "melonenfarm", Set.of("melonen", "melone", "melon"),
            "bambusfarm", Set.of("bambus", "bamboo"),
            "kaktusfarm", Set.of("kaktus", "kakteen", "cactus"),
            "bruchsteingenerator", Set.of("bruchstein", "cobblestone", "cobble", "stein", "generator"));

    static Optional<String> farm(String prompt) {
        List<String> tokens = RequestHints.tokens(RequestHints.normalize(prompt));
        String joined = String.join("", tokens);
        Set<String> names = new java.util.HashSet<>();
        for (Map.Entry<String, String> e : FARMS.entrySet()) {
            if (joined.contains(e.getKey())) {
                names.add(e.getValue());
            }
        }
        if (names.size() != 1) {
            return Optional.empty();
        }
        String name = names.iterator().next();
        Set<String> own = FARM_WORDS.getOrDefault(name, Set.of());
        for (String t : tokens) {
            if (!FILLER.contains(t) && !FARM_EXTRA.contains(t) && !own.contains(t) && !FARMS.containsKey(t)) {
                return Optional.empty();
            }
        }
        return Optional.of(moduleJson(name));
    }

    static final String DECODER_3_8 = "D0 = !A&!B&!C; D1 = A&!B&!C; D2 = !A&B&!C; D3 = A&B&!C; D4 = !A&!B&C; "
            + "D5 = A&!B&C; D6 = !A&B&C; D7 = A&B&C";
    static final String DECODER_2_4 = "D0 = !A&!B; D1 = A&!B; D2 = !A&B; D3 = A&B";
    static final Set<String> DECODER = Set.of("dekoder", "decoder", "dekodierer", "decodierer", "adressdekoder",
            "demultiplexer", "demux");
    private static final Set<String> DECODER_EXTRA = Set.of("3", "8", "2", "4", "zu", "auf", "drei", "acht", "zwei",
            "vier", "bit", "binaer", "redstone", "funktionierenden", "funktionierender", "funktionierendes", // umlaut-ok
            "funktionierende", "einen", "kleinen", "grossen", "logik", "fuer", "adressen", "leitungen"); // umlaut-ok

    /**
     * „bau mir einen 3-zu-8-Dekoder“: aus 3 Hebeln leuchtet genau eine von 8 Lampen. 8 Ausgänge → 2 Stockwerke
     * übereinander (LogicCircuit, 2026-11-07), „in 3D“ → 4 Stockwerke mit je 2 Lampen. Auf dem Spigot-Testserver mit
     * allen 8 Belegungen geprüft (LogicProbe „3d“). „2 zu 4“ → eine Ebene mit 4 Lampen.
     */
    static Optional<String> decoder(String prompt) {
        boolean dec = false;
        boolean stacked = false;
        List<String> tokens = joinedTokens(prompt);
        for (String t : tokens) {
            if (DECODER.contains(t)) {
                dec = true;
            } else if (STACKED.contains(t)) {
                stacked = true;
            } else if (!FILLER.contains(t) && !DECODER_EXTRA.contains(t) && !STACKED_GLUE.contains(t)) {
                return Optional.empty();
            }
        }
        if (!dec) {
            return Optional.empty();
        }
        boolean small = tokens.contains("4") && tokens.contains("2") && !tokens.contains("8")
                || tokens.contains("vier") && !tokens.contains("acht");
        String expr = (stacked ? "3d " : "") + (small ? DECODER_2_4 : DECODER_3_8);
        com.blackn0va.baumeister.logic.LogicCircuit.Circuit circuit;
        try {
            circuit = com.blackn0va.baumeister.logic.LogicCircuit.compile(expr);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        JsonObject step = new JsonObject();
        step.addProperty("op", "logic");
        step.addProperty("expr", expr);
        JsonArray pos = new JsonArray();
        pos.add(0);
        pos.add(0);
        pos.add(0);
        step.add("pos", pos);
        step.addProperty("facing", "front");
        JsonArray steps = new JsonArray();
        steps.add(step);
        JsonObject plan = new JsonObject();
        plan.addProperty("design", small ? "2-to-4 decoder" : "3-to-8 decoder");
        plan.addProperty("reply", (small ? "Dekoder 2 zu 4" : "Dekoder 3 zu 8") + ": Hebel links von vorn "
                + String.join(", ", circuit.inputs()) + ", je Belegung leuchtet genau eine Lampe. "
                + lampOrder(circuit) + ".");
        plan.add("steps", steps);
        return Optional.of(plan.toString());
    }

    static final Set<String> DISPLAY = Set.of("display", "anzeige", "siebensegment", "siebensegmentanzeige",
            "ziffernanzeige", "zifferanzeige", "hexanzeige", "segmentanzeige", "digitalanzeige", "zahlenanzeige",
            "7segment", "7segmentanzeige", "bildschirm");
    private static final Set<String> DISPLAY_EXTRA = Set.of("7", "sieben", "segment", "segmente", "ziffer", "ziffern",
            "zahl", "zahlen", "hex", "binaer", "digital", "grosses", "grosse", "kleines", "neues"); // umlaut-ok

    /**
     * „bau ein funktionierendes Display“ (Nutzer 2026-11-06 – ging ans LLM, und das war gerade nicht erreichbar): die
     * 7-Segment-Anzeige aus dem Code (Machines „anzeige“, SevenSegment), ohne Modell. Eng wie der Taschenrechner.
     */
    static Optional<String> display(String prompt) {
        boolean wanted = false;
        for (String t : RequestHints.tokens(RequestHints.normalize(prompt))) {
            if (DISPLAY.contains(t)) {
                wanted = true;
            } else if (!FILLER.contains(t) && !CALCULATOR_EXTRA.contains(t) && !DISPLAY_EXTRA.contains(t)) {
                return Optional.empty();
            }
        }
        if (!wanted) {
            return Optional.empty();
        }
        JsonObject step = new JsonObject();
        step.addProperty("op", "module");
        step.addProperty("name", "anzeige");
        JsonArray pos = new JsonArray();
        pos.add(0);
        pos.add(0);
        pos.add(0);
        step.add("pos", pos);
        step.addProperty("facing", "front");
        JsonArray steps = new JsonArray();
        steps.add(step);
        JsonObject plan = new JsonObject();
        plan.addProperty("design", "7-segment display 0-F with decoder");
        plan.addProperty("reply", "7-Segment-Anzeige 0–F: Hebel links von vorn A (1), B (2), C (4), D (8). Die Ziffer "
                + "liegt hinten im Boden – von oben ablesen.");
        plan.add("steps", steps);
        return Optional.of(plan.toString());
    }

    static final Set<String> HOLE = Set.of("loch", "grube", "schacht", "graben");
    private static final Set<String> HOLE_EXTRA = Set.of("grab", "grabe", "graben", "mach", "mache", "machen",
            "kannst", "du", "sollst", "kleines", "kleine", "grosses", "grosse", "tiefes", "tiefe", "flaches", "mal");

    /**
     * „Kannst du hier ein Loch graben?“, „Du sollst ein Loch graben“ (dev01-Log, viermal): eine Grube vor dem Spieler,
     * ohne Modell. 3 × 3, 3 tief; „kleines“ 2 × 2 × 2, „großes“ 5 × 5 × 5. Gegraben wird nur unter Bodenhöhe (y < 0),
     * dort, wo der Plan ohnehin baut – also mit denselben Rechten (WorldGuard, Grundstücke) und rückgängig machbar.
     */
    static Optional<String> hole(String prompt) {
        boolean wanted = false;
        int size = 3;
        for (String t : RequestHints.tokens(RequestHints.normalize(prompt))) {
            if (HOLE.contains(t) && !t.equals("graben")) {
                wanted = true;
            } else if (t.startsWith("klein")) {
                size = 2;
            } else if (t.startsWith("gross") || t.startsWith("tief")) {
                size = 5;
            } else if (!FILLER.contains(t) && !HOLE_EXTRA.contains(t)) {
                return Optional.empty();
            }
        }
        if (!wanted) {
            return Optional.empty();
        }
        JsonObject step = new JsonObject();
        step.addProperty("op", "fill");
        step.addProperty("block", "air");
        JsonArray from = new JsonArray();
        from.add(0);
        from.add(-size);
        from.add(0);
        JsonArray to = new JsonArray();
        to.add(size - 1);
        to.add(-1);
        to.add(size - 1);
        step.add("from", from);
        step.add("to", to);
        JsonArray steps = new JsonArray();
        steps.add(step);
        JsonObject plan = new JsonObject();
        plan.addProperty("design", "hole " + size + "x" + size + "x" + size);
        plan.addProperty("reply", "Ich grabe dir ein Loch, " + size + " × " + size + " und " + size + " tief.");
        plan.add("steps", steps);
        return Optional.of(plan.toString());
    }

    /** Eng wie alle Schnellaufträge: jedes andere Wort (ein Bauteilname, „mit Fenstern“ …) schickt ans LLM. */
    /** „bau mir einen Prozessor/Computer aus Redstone“ – die Prozessor-Werkstatt (MachineHall.cpuWorkshop). */
    static Optional<String> cpuWorkshop(String prompt, ModuleLibrary modules, int maxExtent) {
        boolean cpu = false;
        for (String t : RequestHints.tokens(RequestHints.normalize(prompt))) {
            if (MachineHall.CPU_WORDS.contains(t)) {
                cpu = true;
            } else if (!FILLER.contains(t) && !t.equals("redstone") && !t.equals("aus")
                    && !MachineHall.BIG.contains(t)) {
                return Optional.empty();
            }
        }
        return cpu ? MachineHall.cpuWorkshop(modules, maxExtent) : Optional.empty();
    }

    static Optional<String> machineHall(String prompt, ModuleLibrary modules, int maxExtent) {
        boolean machine = false;
        boolean big = false;
        boolean small = false;
        for (String t : RequestHints.tokens(RequestHints.normalize(prompt))) {
            if (MachineHall.WORDS.contains(t)) {
                machine = true;
            } else if (MachineHall.BIG.contains(t)) {
                big = true;
            } else if (MachineHall.SMALL.contains(t)) {
                small = true;
            } else if (!FILLER.contains(t)) {
                return Optional.empty();
            }
        }
        if (!machine || big && small) {
            return Optional.empty();
        }
        return MachineHall.plan(modules, MachineHall.count(big, small), maxExtent);
    }

    /**
     * @param prompt      Auftrag wie getippt
     * @param moduleNames Namen aus der Bauteilbibliothek
     * @param maxExtent   bau.max-ausdehnung – kappt Geschosse und Grundfläche, damit der Compiler nicht ablehnt
     * @return Plan-JSON oder leer, wenn der Auftrag ans LLM gehört
     */
    public static Optional<String> match(String prompt, Set<String> moduleNames, int maxExtent) {
        // sizes direkt nach normalize, vor QuickForms und SIZE: Wer es entfernt, schickt „Hochhaus 20 mal 24“ wieder
        // ans LLM (QuickOrdersTest.sizeWithTimesSignOrMalIsRead).
        String text = RequestHints.sizes(RequestHints.normalize(prompt));
        if (text.isEmpty()) {
            return Optional.empty();
        }
        // Bauformen (Burg, Mauer, Brücke, Garten, Baum): eigene, ebenso enge Erkennung. Sie greift nur bei einem
        // Formwort und reinen Füllwörtern – Hochhaus- und Maschinenaufträge kommen dort nie durch. Nennt der Auftrag
        // ein Bauteil der Bibliothek, hat das Vorrang: Ein selbst angelegtes Bauteil „baum“ oder „mauer“ würde sonst
        // von der Bauform überdeckt (Prüfer-Befund 2026-10-29).
        if (RequestHints.findNames(RequestHints.tokens(text), moduleNames).isEmpty()) {
            Optional<String> form = QuickForms.match(text, maxExtent);
            if (form.isPresent()) {
                return form;
            }
        }
        // Zahl + Geschosswort: genau einmal, sonst ist unklar, welche Zahl gemeint ist.
        Integer floors = null;
        Matcher fm = RequestHints.FLOORS.matcher(text);
        StringBuilder rest = new StringBuilder();
        while (fm.find()) {
            if (floors != null) {
                return Optional.empty();
            }
            floors = RequestHints.floorsOf(fm);
            fm.appendReplacement(rest, " ");
        }
        fm.appendTail(rest);
        // Grundfläche „20x24“
        int[] size = null;
        Matcher sm = SIZE.matcher(rest.toString());
        StringBuilder rest2 = new StringBuilder();
        while (sm.find()) {
            if (size != null) {
                return Optional.empty();
            }
            size = new int[] {Integer.parseInt(sm.group(1)), Integer.parseInt(sm.group(2))};
            sm.appendReplacement(rest2, " ");
        }
        sm.appendTail(rest2);

        List<String> tokens = new ArrayList<>(RequestHints.tokens(RequestHints.normalize(rest2.toString())));
        boolean[] used = new boolean[tokens.size()];
        // „aus <material>“ – nur ein Material; ein unbekanntes Wort danach bleibt stehen und schickt ans LLM.
        String material = null;
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (!tokens.get(i).equals("aus")) {
                continue;
            }
            Optional<String> m = RequestHints.material(tokens.get(i + 1));
            if (m.isPresent()) {
                if (material != null) {
                    return Optional.empty();
                }
                material = m.get();
                used[i] = true;
                used[i + 1] = true;
            }
        }
        int towerWords = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (!used[i] && TOWER_WORDS.contains(tokens.get(i))) {
                towerWords++;
                used[i] = true;
            }
        }
        // Anzahl: Zahl direkt vor „mal“ oder vor einem Turmwort („3 mal 20 stockwerke“, „drei hochhaeuser“). Genau
        // einmal – zwei Anzahlen sind unklar und gehen ans LLM. Danach muss ein Turmwort ODER „mal“ + Geschosse da sein:
        // „3 mal 20 stockwerke“ meint Hochhäuser, auch ohne das Wort.
        int count = 1;
        boolean counted = false;
        boolean times = false;
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (used[i]) {
                continue;
            }
            String next = tokens.get(i + 1);
            java.util.OptionalInt n = RequestHints.number(tokens.get(i));
            if (n.isEmpty() || !next.equals("mal") && !TOWER_WORDS.contains(next)) {
                continue;
            }
            if (counted) {
                return Optional.empty();
            }
            counted = true;
            count = n.getAsInt();
            used[i] = true;
            if (next.equals("mal")) {
                times = true;
                used[i + 1] = true;
            }
        }
        if (counted && (count < 1 || count > MAX_TOWERS)) {
            return Optional.empty();
        }
        List<RequestHints.NameHit> hits = RequestHints.findNames(tokens, moduleNames);
        for (RequestHints.NameHit h : hits) {
            for (int k = h.start(); k < h.start() + h.length(); k++) {
                used[k] = true;
            }
        }
        for (int i = 0; i < tokens.size(); i++) {
            if (!used[i] && !FILLER.contains(tokens.get(i))) {
                return Optional.empty();
            }
        }

        if ((towerWords > 0 || times) && hits.isEmpty() && floors != null) {
            return towers(count, floors, size, material, maxExtent);
        }
        // Maschine: genau ein Bauteil, nichts weiter verlangt – mehrdeutig oder mit Material/Maß ist Sache des LLM.
        if (towerWords == 0 && hits.size() == 1 && floors == null && size == null && material == null) {
            return Optional.of(moduleJson(hits.getFirst().name()));
        }
        return Optional.empty();
    }

    /**
     * count Hochhäuser in einer Reihe nach rechts, je TOWER_GAP Abstand. Passt die Reihe nicht in bau.max-ausdehnung,
     * werden es weniger – die Antwort nennt die gebaute Zahl, statt still eins zu bauen.
     */
    private static Optional<String> towers(int count, int requested, int[] size, String material, int maxExtent) {
        int maxFloors = Math.min(PlanParser.MAX_FLOORS, (maxExtent - HEADROOM) / PlanCompiler.FLOOR_HEIGHT);
        int maxSide = Math.min(MAX_SIDE, maxExtent);
        if (maxFloors < 1 || maxSide < MIN_SIDE) {
            return Optional.empty();
        }
        int floors = Math.clamp(requested, 1, maxFloors);
        int w = size == null ? Math.min(DEFAULT_SIDE, maxSide) : Math.clamp(size[0], MIN_SIDE, maxSide);
        int d = size == null ? Math.min(DEFAULT_SIDE, maxSide) : Math.clamp(size[1], MIN_SIDE, maxSide);
        int fit = Math.max(1, (maxExtent + TOWER_GAP) / (w + TOWER_GAP));
        int n = Math.min(count, fit);
        String facade = material != null && !RequestHints.isWindow(material) ? material : DEFAULT_FACADE;
        String window = material != null && RequestHints.isWindow(material) ? material : DEFAULT_WINDOW;
        if (n == 1) {
            String reply = "Ein Hochhaus mit " + floors + " Stockwerken, " + w + "×" + d
                    + (count > 1 ? " – mehr passen nicht nebeneinander" : "") + " – ich fange sofort an!";
            return Optional.of(skyscraperJson(w, d, floors, facade, window, reply));
        }
        String reply = n + " Hochhäuser mit je " + floors + " Stockwerken, je " + w + "×" + d
                + (n < count ? " – mehr als " + n + " passen nicht nebeneinander" : "") + " – ich fange sofort an!";
        JsonArray steps = new JsonArray();
        for (int i = 0; i < n; i++) {
            steps.add(skyscraperStep(i * (w + TOWER_GAP), w, d, floors, facade, window));
        }
        JsonObject plan = new JsonObject();
        plan.addProperty("design", n + " skyscrapers " + w + "x" + d + " in a row, " + floors + " floors each, facade "
                + facade + ", windows " + window);
        plan.addProperty("reply", reply);
        plan.add("steps", steps);
        return Optional.of(plan.toString());
    }

    private static JsonObject skyscraperStep(int x0, int w, int d, int floors, String facade, String window) {
        JsonObject step = new JsonObject();
        step.addProperty("op", "skyscraper");
        step.addProperty("block", facade);
        step.addProperty("window_block", window);
        step.add("from", vec(x0, 0, 0));
        step.add("to", vec(x0 + w - 1, 0, d - 1));
        step.addProperty("floors", floors);
        return step;
    }

    /** Ein einziger skyscraper-Schritt ab (0,0,0); verschoben wird wie bei jedem Plan erst im BuildService. */
    static String skyscraperJson(int w, int d, int floors, String facade, String window, String reply) {
        JsonObject step = skyscraperStep(0, w, d, floors, facade, window);
        return plan("skyscraper " + w + "x" + d + ", " + floors + " floors, facade " + facade + ", windows " + window,
                reply, step);
    }

    private static String moduleJson(String name) {
        return moduleJson(name, null);
    }

    /** Ein module-Schritt ab (0,0,0); reply null = Farm-Antwort bzw. „Ich baue dir das Bauteil …“. */
    public static String moduleJson(String name, String reply) {
        return moduleJson(name, reply, 0);
    }

    /**
     * Wie moduleJson, aber die unterste Lage dy Blöcke unter den Füßen (dy ≤ 0): für Spiel-Vorlagen mit Teilen unter
     * der Oberfläche (Fallgrube, Keller). Ab (0,0,0) schwebte der Wüstentempel 14 Blöcke über seiner Schatzkammer.
     */
    public static String moduleJson(String name, String reply, int dy) {
        JsonObject step = new JsonObject();
        step.addProperty("op", "module");
        step.addProperty("name", name);
        step.add("pos", vec(0, Math.min(0, dy), 0));
        step.addProperty("facing", "front");
        // Farmen (2026-11-08): deutscher Name und was bereitliegen soll, die Schritte sagt BuildService nach dem Bau.
        // Vorher „Ich baue dir das Bauteil 'eisenfarm'.“ – und kein Wort zu Betten, Bewohnern oder Zombie.
        String text = reply != null ? reply
                : FarmGuide.acceptReply(name).orElse("Ich baue dir das Bauteil '" + name + "'.");
        return plan("module " + name, text, step);
    }

    private static String plan(String design, String reply, JsonObject step) {
        JsonObject plan = new JsonObject();
        plan.addProperty("design", design);
        plan.addProperty("reply", reply);
        JsonArray steps = new JsonArray();
        steps.add(step);
        plan.add("steps", steps);
        return plan.toString();
    }

    private static JsonArray vec(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }
}
