package com.blackn0va.baumeister.plan;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Die Nachricht an das LLM zu einem Auftrag – für Plugin (BuildService) und Client-Mod (client/) dieselbe. Vorher stand
 * sie in BuildService, das Bukkit braucht; die Client-Mod (Wunsch 2026-10-31) hätte sie sonst nachbauen müssen, und
 * zwei Fassungen laufen auseinander.
 *
 * Auftrag plus Lage am Bauplatz plus verfügbare Bauteile plus die aus dem Auftrag gelesenen Fakten. Steht im User-Teil,
 * nicht im System-Prompt: Der System-Prompt bleibt über alle Aufträge gleich, und llama-server rechnet ihn aus dem
 * Cache statt jedes Mal neu.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class PlanPrompt {

    private PlanPrompt() {
    }

    /**
     * „Rechte: inhalte=ja, gefaehrlich=nein, befehle=nein“ – was der Auftraggeber im Moment des Auftrags bauen darf
     * (Plan B5). Muss wortgleich zur Zeile im System-Prompt passen (Abschnitt BLOCK DATA, GrammarDriftTest), sonst
     * liest das Modell sie nicht als seine Rechte.
     */
    public static String rightsLine(boolean contents, boolean dangerous, boolean commands) {
        return "Rechte: inhalte=" + yesNo(contents) + ", gefaehrlich=" + yesNo(dangerous) // umlaut-ok: Prompt-Wort
                + ", befehle=" + yesNo(commands);
    }

    private static String yesNo(boolean b) {
        return b ? "ja" : "nein";
    }

    /**
     * Redstone- und Maschinenregeln, die vorher im System-Prompt standen (Abschnitt TECHNICAL BUILDS). Gemessen
     * 2026-11-21 (llama-server /tokenize): ~750 der 4 506 Token des System-Prompts, die 22 der 24 PlanBench-Aufträge
     * (Häuser, Türme, Kirche …) nie brauchen, die aber jede Antwort näher an die 8 192-Token-Grenze schieben. Jetzt
     * gehen sie unter derselben Bedingung wie die MODULE LIBRARY mit ({@link #offersModules}). Wandfackeln und Halt
     * (gelten auch für Häuser) und „Logic first“ blieben im System-Prompt. Wer die Regeln zurück in den System-Prompt
     * holt, zahlt sie wieder bei jedem Haus.
     */
    static final String TECHNICAL_RULES = """
            TECHNICAL RULES (redstone, machines):
            - Redstone rules you must respect when you place anything yourself: a redstone_wire powers every wire, \
            lamp and mechanism NEXT to it and the block it points into, so two separate signals need one empty block\
             between them; a wire only climbs one block up per step and only if nothing sits on the lower wire; a \
            powered solid block switches off a torch attached to it (that is how NOT works); a repeater only passes \
            the signal forward and never changes height; signal strength drops by 1 per wire block (15 max), a \
            repeater restores it.
            - If a module in the MODULE LIBRARY fits the request, use it instead of designing the mechanism \
            yourself. Library machines are complete and tested in the game: place each one whole with op module, \
            never rebuild, change or overlap its blocks, and keep 1 block of free space around it. Logic gates, \
            clocks and piston doors come from the library; you only add the building, platform, walls and decoration\
             around them.
            - To combine machines (e.g. a gate fed by a clock, or a hall full of gates), place them side by side in \
            a row on the same y with 2 blocks between them; the player wires them up.
            - A general request for "a machine" ("eine Maschine", "eine große/fette Maschine", "was Technisches") \
            means several USEFUL working machines from the library - for example schmelzanlage, zuckerrohrfarm, \
            item_sortierer, sammelstelle, wasserfahrstuhl, automatiktuer - set up as a machine hall. Logic gates, \
            adders and counters only when the player asks for logic, gates, a calculator or a circuit. "Groß", \
            "fett", "riesig" means more machines and a bigger hall, never one small module.
            - Machine hall: modules stand on y=0. The floor is ONE layer at y=-1 under the whole hall. Walls stand \
            at least 2 blocks outside every module and are hollow (op walls), the roof sits 2 blocks above the \
            tallest module. Never fill, carve or set anything inside the space of a module - not even air. Lights go\
             on the walls or the floor, never on top of a module.
            - facing may also be up or down: piston and sticky_piston push toward facing; observer[facing=X] watches\
             side X and outputs to the opposite side; hopper[facing=down|front|...] outputs toward facing; dropper \
            and dispenser shoot toward facing.
            - repeater[facing=X,delay=1..4] and comparator[facing=X,mode=compare|subtract]: the signal ENTERS from \
            side X and leaves on the opposite side.
            - Useful blocks: redstone_wire, redstone_torch, redstone_block, redstone_lamp, lever, stone_button, \
            stone_pressure_plate, repeater, comparator, piston, sticky_piston, observer, hopper, chest, dropper, \
            dispenser, note_block, daylight_detector, target, rail, powered_rail, detector_rail, iron_door, \
            iron_trapdoor, slime_block, honey_block.
            """;

    /**
     * @param previousPlan   letzter Bau dieses Spielers (Gedächtnis) oder null
     * @param reference      Recherche-Ergebnis oder null – steht direkt unter dem Auftrag
     */
    public static String userMessage(String prompt, SiteSurvey survey, int ahead, ModuleLibrary modules,
            String previousPlan, String previousPrompt, long minutesAgo, String reference) {
        return userMessage(prompt, survey, ahead, modules, previousPlan, previousPrompt, minutesAgo, reference, null);
    }

    /**
     * Wie oben, dazu rights: die Zeile aus {@link #rightsLine} direkt unter dem Auftrag oder null. Ohne sie gilt im
     * System-Prompt alles als „nein“ (Client-Mod, Tests) – das Modell schreibt dann keine Daten, für die Rechte nötig
     * sind. Geprüft wird trotzdem beim Setzen (BlockTiers, PlacementGate); die Zeile spart nur verworfene Zellen.
     */
    public static String userMessage(String prompt, SiteSurvey survey, int ahead, ModuleLibrary modules,
            String previousPlan, String previousPrompt, long minutesAgo, String reference, String rights) {
        StringBuilder sb = new StringBuilder("Bauauftrag: ").append(prompt).append('\n');
        if (rights != null) {
            sb.append(rights).append('\n');
        }
        sb.append('\n');
        if (reference != null) {
            sb.append(reference).append('\n');
        }
        // Ohne die drei Anschlussstellen je Bauwerk: Bei einem Anbau legt AttachPlanner genau eine Wand fest (unten),
        // sonst laden sie nur zum Anbauen ein. Spart ~400 Zeichen je Bauwerk an Prompt-Token.
        sb.append(survey.describe(3000, -ahead, false));
        sb.append(survey.views());
        if (previousPlan != null) {
            String plan = previousPlan.length() > 3500 ? previousPlan.substring(0, 3500) + " …(gekürzt)" : previousPlan;
            sb.append("\nPREVIOUS BUILD (you built it for this player ").append(minutesAgo)
                    .append(" min ago for \"").append(previousPrompt).append("\"; same coordinates as this plan, ")
                    .append("it is part of the scan above):\n").append(plan).append("\nIf the player wants to change, ")
                    .append("extend, repair or recolour THIS build, answer with mode \"update\" and the COMPLETE ")
                    .append("updated plan (all its steps, changed as asked, plus new ones). Otherwise mode \"new\".\n");
        }
        List<ModuleLibrary.Module> all = modules.all();
        List<String> names = new ArrayList<>();
        for (ModuleLibrary.Module mod : all) {
            names.add(mod.name());
        }
        // Liste nur, wenn der Auftrag nach Technik klingt (offersModules). Gemessen 2026-11-21 (llama-server /tokenize,
        // Qwen2.5): die Namensliste allein ~450 Token in jedem der 22 Nicht-Maschinen-Testaufträge von PlanBench –
        // Prompt-Zeit und Platz, der der Antwort vor der 8 192-Token-Grenze fehlt.
        if (!all.isEmpty() && offersModules(prompt, names)) {
            sb.append("\nMODULE LIBRARY (tested builds; use op module with name, pos = front-left-bottom corner, "
                    + "facing = side the module front points to):\n");
            if (wantsMachineDetails(prompt, names)) {
                // Nennt der Auftrag Bauteile beim Namen („Zuckerrohrfarm und Schmelzanlage“), nur deren Bedienhinweise,
                // die übrigen mit Name und Größe. Gemessen 2026-11-21 (PlanBench): Bauprompt dieses Auftrags 7 521
                // Token bei 8 192 Kontext, die Antwort lief in beiden Runden bei 8 191 Token ab. Ein allgemeiner
                // Maschinenwunsch ohne Namen bekommt weiter alle Beschreibungen – dort wählt das Modell erst aus.
                List<String> named = RequestHints.namedModules(prompt, names);
                List<String> brief = new ArrayList<>();
                for (ModuleLibrary.Module mod : all) {
                    if (named.isEmpty() || named.contains(mod.name())) {
                        sb.append("- ").append(mod.name()).append(": ").append(mod.size().x()).append('x')
                                .append(mod.size().y()).append('x').append(mod.size().z()).append(" (w x h x d). ")
                                .append(mod.description()).append('\n');
                    } else {
                        brief.add(mod.name() + " " + mod.size().x() + "x" + mod.size().y() + "x" + mod.size().z());
                    }
                }
                if (!brief.isEmpty()) {
                    sb.append("Also available: ").append(String.join(", ", brief)).append(" (w x h x d)\n");
                }
            } else {
                // Nur Name und Größe: Maschinenwunsch ohne Redstone-Wort (z. B. „Tresor“) braucht die Bedienhinweise
                // nicht, kann aber ein Bauteil einbauen.
                List<String> brief = new ArrayList<>();
                for (ModuleLibrary.Module mod : all) {
                    brief.add(mod.name() + " " + mod.size().x() + "x" + mod.size().y() + "x" + mod.size().z());
                }
                sb.append(String.join(", ", brief)).append(" (w x h x d)\n");
            }
        }
        // Am Ende, direkt vor der Antwort: Gemessen 2026-10-28 überlas das 8B-Modell die Hochhaus- und Modulregel
        // im System-Prompt. Die Fakten stehen deshalb hier ausdrücklich, und handleReply prüft den Plan dagegen.
        // Ebenso die Anbauwand: harte Vorgabe direkt vor der Antwort, PlanReview rückt den Plan danach an sie. Nicht
        // mit Gedächtnis: Dann kommt meist mode update, das bewusst nicht ausgerichtet wird – „fixed by the server“
        // wäre unwahr und kostete nur Prompt-Token (Gegenprobe AttachMessageTest.followUpWithMemoryGetsNoAttachText).
        if (previousPlan == null) {
            AttachPlanner.choose(survey, prompt).ifPresent(w -> sb.append('\n').append(w.instruction()));
        }
        if (offersModules(prompt, names)) {
            sb.append('\n').append(TECHNICAL_RULES);
        }
        sb.append('\n').append(RequestHints.parse(prompt, names).facts()).append('\n');
        return sb.toString();
    }

    /**
     * Auftrag für eine Korrekturrunde – ohne Umgebungs-Scan und Modulbeschreibungen. Gemessen 2026-11-07 (Host-Log des
     * Wunsch): Bauprompt 6 381 Token (System-Prompt 4 105, „farm“ holt alle
     * Modulbeschreibungen), Plan 1 622 Token. Die Korrekturrunde schickte alles noch einmal plus Plan plus Fehler: 8 028
     * Token bei 8 192 Kontext, „Prompt zu lang“, der Auftrag war verloren. Der Plan des Modells steht in der Runde
     * darunter und trägt Lage und Koordinaten schon; nötig sind Auftrag, Fakten und die Fehlerliste.
     */
    public static String repairUserMessage(String prompt, Collection<String> moduleNames) {
        return repairUserMessage(prompt, moduleNames, null);
    }

    /** Wie oben, mit der Rechte-Zeile der ersten Runde (oder null) – die Rechte gelten in jeder Runde gleich. */
    public static String repairUserMessage(String prompt, Collection<String> moduleNames, String rights) {
        return "Bauauftrag: " + prompt + "\n" + (rights != null ? rights + "\n" : "")
                + "\n(Correction round: the scan of the surroundings and the module details "
                + "were in the first round; your plan below already uses them – keep its coordinates.)\n\n"
                + RequestHints.parse(prompt, moduleNames).facts() + "\n";
    }

    /**
     * Rechte-Zeile einer Nachricht (erste Runde oder vorige Korrektur), sonst null. Zählt nur als zweite Zeile direkt
     * unter „Bauauftrag: …“, wo userMessage sie hinschreibt: Der Auftrag des Spielers steht ohne Zeilenumbrüche drin
     * (Texts.clean), und eine Zeile weiter unten (Recherche-Text, Scan) wird nie als Rechte gelesen.
     */
    static String rightsIn(String message) {
        String[] lines = (message == null ? "" : message).split("\n", 3);
        return lines.length >= 2 && lines[0].startsWith("Bauauftrag: ") && RIGHTS_LINE.matcher(lines[1]).matches()
                ? lines[1] : null;
    }

    private static final java.util.regex.Pattern RIGHTS_LINE = java.util.regex.Pattern.compile(
            "Rechte: inhalte=(?:ja|nein), gefaehrlich=(?:ja|nein), befehle=(?:ja|nein)"); // umlaut-ok

    /**
     * Gespräch der nächsten Korrekturrunde: System-Prompt (gleicher Anfang, llama-server rechnet ihn aus dem Cache),
     * Auftrag nach {@link #repairUserMessage}, NUR der letzte Plan und seine Fehlerliste. Vorher hängte jede Runde an das
     * volle Gespräch an – mit zwei Runden standen zwei ganze Pläne darin. Wer das zurückdreht, verliert die Korrektur
     * jedes großen Plans („Prompt zu lang“). Für Plugin (BuildService) und Client-Mod (ClientPlanner) dieselbe.
     */
    public static List<JsonObject> repairConversation(List<JsonObject> messages, String prompt,
            Collection<String> moduleNames, String assistant, String request) {
        List<JsonObject> next = new ArrayList<>(4);
        next.add(messages.getFirst());
        String rights = messages.size() > 1 && messages.get(1).has("content")
                ? rightsIn(messages.get(1).get("content").getAsString()) : null;
        next.add(LlmProtocol.message("user", repairUserMessage(prompt, moduleNames, rights)));
        next.add(LlmProtocol.message("assistant", assistant));
        next.add(LlmProtocol.message("user", request));
        return next;
    }

    /**
     * Wörter, bei denen die vollen Modulbeschreibungen mitgehen. Gemessen 2026-11-04: Die Beschreibungen der 35
     * eingebauten Module sind ~6100 Zeichen (~1850 Token) – ein Viertel des Bauprompts von 7451 Token, bei jedem
     * Auftrag. Mit 8192 Kontext blieben einem Haus nur 741 Token für den Plan, und der Prompt kostete 6,6 s (14B auf
     * dem GPU-PC). Wer die Liste leert, schickt wieder bei jedem Haus alle Bedienhinweise mit.
     * Im Zweifel lieber mit Details („tür“ trifft auch ein Haus mit Tür).
     */
    static final List<String> MACHINE_WORDS = List.of("redstone", "maschine", "schaltung", "computer", "rechner",
            "prozessor", "cpu", "alu", "addier", "gatter", "logik", "speicher", "flipflop", "flip-flop", "latch",
            "takt", "zähler", "farm", "fahrstuhl", "aufzug", "falle", "sortier", "schmelz", "kolben", "tür", "tor ",
            "klingel", "glocke", "lampe", "licht", "schalter", "hebel", "knopf", "sammelstelle", "vergleich",
            "register", "akku", "automat", "mechani", "piston",
            // T-015 Runde 2 (Prüfung des Dirigenten 2026-11-21): Diese Aufträge bekamen vor T-015 die volle
            // Liste, ohne die Wörter hier aber keine mehr (offersModules) – Tageslichtsensor und Nachtlicht
            // (Modul nachtlicht), Zugbrücke, Tor „das sich öffnet“, Garagentor.
            "sensor", "tageslicht", "nachts", "zugbrücke", "öffnet", "garagentor");

    /**
     * Wörter aus MACHINE_WORDS, die auch jedes Haus trägt („Haus mit Tür“, „Turm mit Licht“). Allein öffnen sie weder
     * MODULE LIBRARY noch TECHNICAL RULES – sonst stünde „use the library“ neben der Tatsache „do not use op module“.
     */
    static final List<String> PLAIN_WORDS = List.of("tür", "tor ", "licht", "lampe");

    /**
     * Ob MODULE LIBRARY und TECHNICAL RULES mitgehen: Maschinenwunsch (RequestHints, wie PlanReview), ein Modulname
     * oder ein technisches Wort aus MACHINE_WORDS außer den PLAIN_WORDS. Vorher (T-015, Runde 0) hing die Liste nur am
     * Maschinenwunsch – „Pistontür“, „automatische Tür“, „eine Farm“, „ein Rechner“, „eine Falle“, „Lampe die mit Hebel
     * angeht“ verloren sie (Prüfung des Dirigenten 2026-11-21), obwohl PlanReview einen Plan ab
     * PlanAutoFix.MACHINE_SHARE auch ohne Maschinenwunsch als Maschine annimmt. Wer die Bedingung wieder auf
     * machineWanted verengt, nimmt diesen Aufträgen die Bauteile.
     */
    static boolean offersModules(String prompt, java.util.Collection<String> moduleNames) {
        if (RequestHints.parse(prompt, moduleNames).machineWanted()) {
            return true;
        }
        String t = prompt == null ? "" : prompt.toLowerCase(java.util.Locale.ROOT) + " ";
        for (String w : MACHINE_WORDS) {
            if (!PLAIN_WORDS.contains(w) && t.contains(w)) {
                return true;
            }
        }
        return !RequestHints.namedModules(prompt, moduleNames).isEmpty();
    }

    public static boolean wantsMachineDetails(String prompt, java.util.Collection<String> moduleNames) {
        String t = prompt == null ? "" : prompt.toLowerCase(java.util.Locale.ROOT) + " ";
        for (String w : MACHINE_WORDS) {
            if (t.contains(w)) {
                return true;
            }
        }
        String squeezed = t.replace(" ", "").replace("-", "").replace("_", "");
        for (String n : moduleNames) {
            if (squeezed.contains(n.toLowerCase(java.util.Locale.ROOT).replace("_", ""))) {
                return true;
            }
        }
        return false;
    }
}
