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
        if (!all.isEmpty()) {
            sb.append("\nMODULE LIBRARY (tested builds; use op module with name, pos = front-left-bottom corner, "
                    + "facing = side the module front points to):\n");
            if (wantsMachineDetails(prompt, names)) {
                for (ModuleLibrary.Module mod : all) {
                    sb.append("- ").append(mod.name()).append(": ").append(mod.size().x()).append('x')
                            .append(mod.size().y()).append('x').append(mod.size().z()).append(" (w x h x d). ")
                            .append(mod.description()).append('\n');
                }
            } else {
                // Nur Name und Größe: Ein Haus braucht die Bedienhinweise der Maschinen nicht, kann aber trotzdem eine
                // automatiktuer oder lampenreihe einbauen.
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
            "register", "akku", "automat", "mechani");

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
