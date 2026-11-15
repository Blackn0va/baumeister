package com.blackn0va.baumeister.help;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Hilfebuch und Bedienpult für neue Spieler (/hilfe). Wunsch 2026-11-05.
 *
 * Darum ist fast jede Zeile ein Knopf. Grenze von Minecraft: Ein Klick im Buch kann nur einen BEFEHL ausführen
 * (run_command) oder blättern, keinen Chat-Satz senden. Sätze an den Begleiter laufen deshalb über /vera &lt;satz&gt;
 * (wirkt wie „Vera, &lt;satz&gt;“). Was eine Eingabe braucht (Suchwort, Warpname), legt /hilfe tippen &lt;befehl&gt; als
 * anklickbaren Vorschlag in den Chat – dort ergänzt man nur noch den Namen.
 *
 * Das Buch wird bei jedem Öffnen neu gebaut (Context): Mitspieler zum Einladen, alle Warps, eigene Teamkisten,
 * gemerkte Bauwerke und der Stand der Tagesaufgaben stehen als echte Listen darin. Kapitel, für die das Recht fehlt,
 * fehlen ganz. Lange Listen gehen auf Folgeseiten weiter.
 *
 * Namen in Befehlen werden auf [\p{L}0-9_-] beschränkt (sie stammen aus geprüften Quellen, trotzdem doppelt
 * gesichert): Ein Name mit Leerzeichen oder „/“ dürfte nie einen anderen Befehl ergeben (OWASP A03).
 *
 * Seiten höchstens ~14 Zeilen zu ~19 Zeichen (Minecraft-Buch); lines/fitsPage schätzen das, der Test prüft jede Seite.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class HelpBook {

    /** Ein Stück Text. command: bei Klick ausführen; page: bei Klick zu dieser Seite (1-basiert); sonst null/0. */
    public record Part(String text, String command, int page, boolean bold) {
        static Part text(String t) {
            return new Part(t, null, 0, false);
        }

        static Part title(String t) {
            return new Part(t, null, 0, true);
        }

        static Part run(String label, String command) {
            return new Part("» " + label + "\n", command, 0, false);
        }

        /** Legt command als Vorschlag in die Chatzeile (zum Ergänzen). */
        static Part type(String label, String command) {
            return new Part("✎ " + label + "\n", "/hilfe tippen " + command, 0, false);
        }
    }

    public record Page(String chapter, List<Part> parts) {
    }

    /**
     * Was das Buch über den Spieler und den Server weiß, wenn es aufgeschlagen wird.
     *
     * @param can     Rechteprüfung (p::hasPermission)
     * @param players andere Spieler online (zum Einladen)
     * @param warps   alle Warps
     * @param teams   Teamkisten des Spielers
     * @param quests  heutige Aufgaben mit Stand, je Zeile
     * @param modules gemerkte/gespeicherte Bauwerke (Bauteile)
     * @param plots   PlotSquared läuft (sonst keine /plot-Knöpfe)
     * @param extras  Sammlung, Stimme (Bedienung 2026-11-08)
     */
    public record Context(String companion, Predicate<String> can, List<String> players, List<String> warps,
            List<String> teams, List<String> quests, List<String> modules, String character, List<Right> rights,
            List<String> exceptions, List<String> rightPlayers, boolean plots, Extras extras) {

        /** Ohne Sammlung und Stimme. */
        public Context(String companion, Predicate<String> can, List<String> players, List<String> warps,
                List<String> teams, List<String> quests, List<String> modules, String character, List<Right> rights,
                List<String> exceptions, List<String> rightPlayers, boolean plots) {
            this(companion, can, players, warps, teams, quests, modules, character, rights, exceptions, rightPlayers,
                    plots, Extras.NONE);
        }

        /** Ohne PlotSquared. */
        public Context(String companion, Predicate<String> can, List<String> players, List<String> warps,
                List<String> teams, List<String> quests, List<String> modules, String character, List<Right> rights,
                List<String> exceptions, List<String> rightPlayers) {
            this(companion, can, players, warps, teams, quests, modules, character, rights, exceptions, rightPlayers,
                    false);
        }

        /** Ohne Charakter und Rechte (Messprobe, ältere Tests). */
        public Context(String companion, Predicate<String> can, List<String> players, List<String> warps,
                List<String> teams, List<String> quests, List<String> modules) {
            this(companion, can, players, warps, teams, quests, modules, null, List.of(), List.of(), List.of());
        }
    }

    /**
     * Was das Buch außer Rechten und Listen noch wissen muss (2026-11-08): wie viele Bauwerk-Dateien es gibt (Kapitel
     * „Sammlung“ nur, wenn welche da sind) und – nur für Admins, sonst null – den Stand der Server-Stimme.
     */
    public record Extras(int schematics, Voice voice) {
        public static final Extras NONE = new Extras(0, null);
    }

    /**
     * Server-Stimme fürs Admin-Kapitel „Stimme“: an, gewählte Stimme (Name ohne .onnx), alle auf dem Server liegenden
     * Stimmen und ein kurzer Grund, wenn sie trotz „an“ nicht spricht (≤ 17 Zeichen), sonst "".
     */
    public record Voice(boolean on, String current, List<String> voices, String problem, String vera,
            boolean cartoon) {

        /** Ohne eigene Vera-Stimme (wie der Baumeister, normaler Ton). */
        public Voice(boolean on, String current, List<String> voices, String problem) {
            this(on, current, voices, problem, current, false);
        }
    }

    /** Befehl hinter den Knöpfen der Stimme-Seite. */
    public static final String VOICE_COMMAND = "/bm sprache ";
    public static final String VOICE_CHAPTER = "Stimme";

    /** Stimmen als Knöpfe „■/□ Kurzname“ hinter VOICE_COMMAND + verb + Name; ■ = current. */
    private static void voiceList(List<Part> list, List<String> voices, String current, String verb) {
        for (String name : voices) {
            if (safe(name)) {
                boolean now = name.equals(current);
                list.add(new Part((now ? "■ " : "□ ") + voiceLabel(name) + "\n", VOICE_COMMAND + verb + name, 0, now));
            }
        }
    }

    /**
     * Kurzname einer Piper-Stimme fürs Buch: „de_DE-thorsten-medium“ → „thorsten (de)“. Der volle Name passt nicht in
     * eine Buchzeile (19 Zeichen) und bräche um – dann stünde die Fußzeile schief.
     */
    static String voiceLabel(String name) {
        String n = name.replaceFirst("-(?:x_low|low|medium|high)$", "");
        java.util.regex.Matcher m = Pattern.compile("^([a-z]{2})_[A-Z]{2}-(.+)$").matcher(n);
        String label = m.matches() ? m.group(2) + " (" + m.group(1) + ")" : n;
        return label.length() > 17 ? label.substring(0, 17) : label;
    }

    /**
     * Eine Fähigkeit Veras im Admin-Buch (VeraRights): key für den Befehl, label (≤ 19 Zeichen), level = aktuelle
     * Stufe („Alle“, „Ops“, „Aus“).
     */
    public record Right(String key, String label, String level) {
    }

    /** Befehl hinter den Knöpfen der Rechte-Seite: /bm begleiter recht &lt;key&gt; &lt;alle|ops|aus&gt;. */
    public static final String RIGHT_COMMAND = "/bm begleiter recht ";
    /** Öffnet die Rechte-Seite eines Spielers. */
    public static final String PLAYER_RIGHTS_COMMAND = "/bm begleiter rechte ";

    /**
     * Ein Recht auf der Seite eines Spielers: state = eigener Eintrag (TRUE/FALSE) oder null (Standard), effective =
     * was gerade gilt (mit Stufe, LuckPerms und Vorgabe).
     */
    public record PlayerRight(String key, String label, Boolean state, boolean effective) {
    }

    /**
     * Seiten mit allen Rechten eines Spielers, je Recht „Name: ✔/✘“ und drei Knöpfe (Erlaubt, Verboten, Standard). Der
     * Haken zeigt, was gerade gilt; ■ markiert den eigenen Eintrag.
     */
    public static List<Page> playerRights(String player, List<PlayerRight> rights) {
        if (!safe(player)) {
            return List.of(new Page("Rechte", List.of(Part.title("Rechte\n"), Part.text("Ungültiger Name.\n"))));
        }
        List<Part> list = new ArrayList<>();
        for (PlayerRight r : rights) {
            if (!safe(r.key())) {
                continue;
            }
            list.add(Part.text(r.label() + ": " + (r.effective() ? "✔" : "✘")));
            String[][] buttons = {{"Ja", "erlauben"}, {"Nein", "verbieten"}, {"Std", "standard"}};
            Boolean[] means = {Boolean.TRUE, Boolean.FALSE, null};
            for (int i = 0; i < buttons.length; i++) {
                boolean now = java.util.Objects.equals(means[i], r.state());
                String text = (i == 0 ? "\n " : "") + (now ? "■" : "□") + buttons[i][0]
                        + (i == buttons.length - 1 ? "\n" : "  ");
                list.add(new Part(text, RIGHT_COMMAND + r.key() + " " + buttons[i][1] + " " + player, 0, now));
            }
        }
        List<Page> pages = paginate(new Chapter("Rechte " + player, List.of(
                Part.title(player + "\n"),
                Part.text("✔ gilt, ■ Eintrag\n")), list));
        List<Page> out = new ArrayList<>();
        for (Page pg : pages) {
            List<Part> parts = new ArrayList<>(pg.parts());
            out.add(new Page(pg.chapter(), List.copyOf(parts)));
        }
        out.add(new Page("Zurück", List.of(Part.title("Fertig?\n"), Part.text("\n"),
                Part.run("Alle Rechte", PLAYER_RIGHTS_COMMAND.trim()))));
        // Ohne Fußzeile: deren Knöpfe schlagen das HILFEBUCH auf (PAGE_COMMAND). Hier blättern die Buchpfeile.
        return out;
    }

    /** Befehl hinter den Charakter-Knöpfen. */
    public static final String CHARACTER_COMMAND = "/bm begleiter charakter ";
    /** Kapitelnamen, auf die ein Befehl das Buch nach einer Änderung wieder aufschlägt (HelpService.openChapter). */
    public static final String RIGHTS_CHAPTER = "Vera-Rechte";
    public static final String CHARACTER_CHAPTER = "Charakter";

    static final int LINE = 19;
    static final int LINES = 14;
    /** Inhalt je Seite: eine Zeile weniger, die letzte gehört der Fußzeile ◀ · Inhalt · ▶. */
    static final int CONTENT_LINES = LINES - 1;
    /** Befehl, der das Buch auf Seite n (1-basiert) aufschlägt (HelpService). */
    public static final String PAGE_COMMAND = "/hilfe seite ";
    /** Höchstens so viele Einträge je Liste (danach „… und weitere“) – das Buch soll handlich bleiben. */
    static final int MAX_LIST = 24;
    private static final Pattern SAFE = Pattern.compile("[\\p{L}0-9_-]{1,32}");

    private HelpBook() {
    }

    /** Ein Kapitel: Titel, feste Knöpfe, dann eine Liste, die bei Bedarf auf Folgeseiten weitergeht. */
    private record Chapter(String name, List<Part> head, List<Part> list) {
        Chapter(String name, List<Part> head) {
            this(name, head, List.of());
        }
    }

    static boolean safe(String name) {
        return name != null && SAFE.matcher(name).matches();
    }

    public static List<Page> pages(Context c) {
        String v = c.companion();
        List<Chapter> chapters = new ArrayList<>();
        // Texte als kurze Einzelzeilen (≤ LINE Zeichen): Dann bricht Minecraft nichts selbst um, die Zeilenzahl ist
        // genau bekannt und die Fußzeile steht auf jeder Seite auf derselben Höhe (Wunsch 2026-11-05). HelpBookTest
        // prüft beides.

        // Welt- und Grundstücks-Knöpfe nur, wo es sie gibt: Vorher standen /warp farmwelt, /warp plotwelt und /plot
        // fest auf der ersten Seite – die Warps und PlotSquared von dev01. Auf einem fremden Server (frisches Paper,
        // 2026-11-08) bekam jeder neue Spieler so als Erstes vier Knöpfe, die nur Fehlermeldungen bringen.
        List<Part> start = new ArrayList<>(List.of(Part.title("Los geht's\n"), Part.text("\n")));
        if (c.warps().contains("farmwelt")) {
            start.add(Part.run("Farmwelt", "/warp farmwelt"));
            start.add(Part.text("  Überleben\n"));
        }
        if (c.warps().contains("plotwelt")) {
            start.add(Part.run("Plotwelt", "/warp plotwelt"));
            start.add(Part.text("  Kreativ bauen\n"));
        }
        if (c.plots()) {
            start.add(Part.run("Grundstück holen", "/plot auto"));
            start.add(Part.run("Zum Grundstück", "/plot home"));
        }
        if (start.size() > 2) {
            start.add(Part.text("\n"));
        }
        start.add(Part.text("Alles hier geht\n"));
        start.add(Part.text("per Klick.\n"));
        chapters.add(new Chapter("Los geht's", List.copyOf(start)));

        if (c.can().test("baumeister.begleiter")) {
            chapters.add(new Chapter(v, List.of(
                    Part.title(v + "\n"),
                    Part.text("\n"),
                    Part.run("Rufen", "/vera"),
                    Part.run("Komm her", "/vera komm"),
                    Part.run("Bleib hier", "/vera bleib"),
                    Part.run("Folge mir", "/vera folge mir"),
                    Part.run("Tschüss", "/vera tschüss"),
                    Part.type("Frag sie …", "/vera "),
                    Part.text("\n"),
                    Part.text("Im Voice-Chat\n"),
                    Part.text("hört sie dich.\n"))));
            if (c.character() != null) {
                List<Part> ch = new ArrayList<>(List.of(
                        Part.title(CHARACTER_CHAPTER + "\n"),
                        Part.text("Jetzt: " + c.character() + "\n"),
                        Part.text("\n"),
                        Part.run("Kumpel", CHARACTER_COMMAND + "kumpel"),
                        Part.text("  lieb, hilfsbereit\n")));
                if (c.can().test("baumeister.begleiter.frech")) {
                    ch.add(Part.run("Frech", CHARACTER_COMMAND + "frech"));
                    ch.add(Part.text("  zieht dich auf\n"));
                    ch.add(Part.run("Anpassen", CHARACTER_COMMAND + "anpassen"));
                    ch.add(Part.text("  wie du mit ihr\n"));
                    ch.add(Part.text("  redest\n"));
                }
                ch.add(Part.text("\n"));
                ch.add(Part.text("Oder sag es ihr:\n"));
                ch.add(Part.text("„sei frech“\n"));
                chapters.add(new Chapter(CHARACTER_CHAPTER, List.copyOf(ch)));
            }
            List<Part> ask = new ArrayList<>(List.of(
                    Part.title("Fragen\n"),
                    Part.text("\n"),
                    Part.run("Witz", "/vera erzähl mir einen Witz"),
                    Part.run("Wo starb ich?", "/vera wo bin ich gestorben"),
                    Part.run("Hier ist Zuhause", "/vera merk dir das hier als Zuhause"),
                    Part.run("Nach Hause", "/vera bring mich nach Hause"),
                    Part.run("Kisten-Verlauf", "/vera wer war an meiner Kiste"),
                    Part.text("  Kiste anschauen\n"),
                    Part.run("Schaltung prüfen", "/vera prüf meine Schaltung")));
            if (c.can().test("baumeister.reparieren")) {
                ask.add(Part.run("Haus reparieren", "/vera reparier mein Haus"));
            }
            ask.add(Part.text("Nachts warnt sie\n"));
            ask.add(Part.text("vor Monstern.\n"));
            chapters.add(new Chapter("Fragen", List.copyOf(ask)));

            // Erz- und Ortssuche (2026-11-08) ohne Modell: Die Sätze treffen CompanionIntent.FIND, die Wörter
            // CompanionRules.oreFor/placeFor – HelpBookTest prüft beides. Recht wie in CompanionService.find.
            if (c.can().test("baumeister.begleiter.suchen")) {
                chapters.add(new Chapter("Suchen", List.of(
                        Part.title("Suchen\n"),
                        Part.text("Sie zeigt dir\n"),
                        Part.text("den Weg:\n"),
                        Part.run("Diamanten", "/vera wo finde ich Diamanten"),
                        Part.run("Eisen", "/vera wo finde ich Eisen"),
                        Part.run("Gold", "/vera wo finde ich Gold"),
                        Part.run("Redstone", "/vera wo finde ich Redstone"),
                        Part.run("Dorf", "/vera wo ist das nächste Dorf"),
                        Part.run("Netherfestung", "/vera wo ist die nächste Netherfestung"),
                        Part.run("Endportal", "/vera wo ist das Endportal"),
                        Part.type("Anderes …", "/vera wo finde ich "))));
            }

            chapters.add(new Chapter("Abenteuer", List.of(
                    Part.title("Abenteuer\n"),
                    Part.text("14 Etappen bis\n"),
                    Part.text("zum Enderdrachen.\n"),
                    Part.text("\n"),
                    Part.run("Starten", "/bm begleiter abenteuer normal"),
                    Part.run("Hardcore", "/bm begleiter abenteuer hardcore"),
                    Part.run("Wohin?", "/vera wo muss ich hin"),
                    Part.run("Stand", "/bm begleiter abenteuer stand"),
                    Part.run("Tagebuch", "/vera zeig mein Tagebuch"),
                    Part.run("Bestenliste", "/vera wer ist am weitesten"),
                    Part.run("Beenden", "/bm begleiter abenteuer aus"))));

            List<Part> invite = new ArrayList<>();
            for (String name : c.players()) {
                if (safe(name)) {
                    invite.add(Part.run(name, "/bm begleiter abenteuer team " + name));
                }
            }
            chapters.add(new Chapter("Zusammen", List.of(
                    Part.title("Zusammen\n"),
                    Part.text("Fortschritt zählt\n"),
                    Part.text("für alle.\n"),
                    Part.text("\n"),
                    Part.run("Annehmen", "/bm begleiter abenteuer annehmen"),
                    Part.text("\n"),
                    Part.text(invite.isEmpty() ? "Niemand online.\n" : "Einladen:\n")),
                    invite));
        }

        if (c.can().test("baumeister.rucksack")) {
            chapters.add(new Chapter("Rucksack", List.of(
                    Part.title("Rucksack\n"),
                    Part.text("4500 Plätze.\n"),
                    Part.text("\n"),
                    Part.run("Öffnen", "/rucksack"),
                    Part.run("Kiste holen", "/rucksack holen"),
                    Part.type("Suchen …", "/rucksack suche "),
                    Part.run("Sammelliste", "/rucksack einsammeln"),
                    Part.run("Einsammeln aus", "/rucksack einsammeln aus"),
                    Part.text("\n"),
                    Part.text("Einsammeln: Ding\n"),
                    Part.text("am Mauszeiger auf\n"),
                    Part.text("den Beutel klicken.\n"))));
            List<Part> mine = new ArrayList<>();
            for (String t : c.teams()) {
                if (safe(t)) {
                    mine.add(Part.run(t, "/rucksack team " + t));
                }
            }
            chapters.add(new Chapter("Teamkisten", List.of(
                    Part.title("Teamkisten\n"),
                    Part.text("Eine Kiste für\n"),
                    Part.text("mehrere Spieler.\n"),
                    Part.text("\n"),
                    Part.type("Gründen …", "/rucksack team "),
                    Part.type("Einladen …", "/rucksack team <kiste> einladen "),
                    Part.text("\n"),
                    Part.text(mine.isEmpty() ? "Noch keine.\n" : "Deine:\n")), mine));
        }

        if (c.can().test("baumeister.warp")) {
            List<Part> head = new ArrayList<>(List.of(Part.title("Warps\n"), Part.text("\n")));
            if (c.can().test("baumeister.warp.setzen")) {
                head.add(Part.type("Setzen …", "/setwarp "));
                head.add(Part.type("Löschen …", "/delwarp "));
                head.add(Part.text("\n"));
            }
            head.add(Part.text(c.warps().isEmpty() ? "Noch keine.\n" : "Springen:\n"));
            List<Part> list = new ArrayList<>();
            for (String w : c.warps()) {
                if (safe(w)) {
                    list.add(Part.run(w, "/warp " + w));
                }
            }
            chapters.add(new Chapter("Warps", List.copyOf(head), list));
        }

        if (c.can().test("baumeister.aufgaben")) {
            List<Part> head = new ArrayList<>(List.of(Part.title("Aufgaben\n"), Part.text("Heute:\n")));
            for (String q : c.quests()) {
                head.add(Part.text(q + "\n"));
            }
            head.add(Part.text("\n"));
            head.add(Part.run("Details", "/aufgaben"));
            head.add(Part.run("Rangliste", "/aufgaben rangliste"));
            chapters.add(new Chapter("Aufgaben", List.copyOf(head)));
        }

        if (c.can().test("baumeister.bauen")) {
            List<Part> list = new ArrayList<>();
            for (String m : c.modules()) {
                if (safe(m)) {
                    list.add(Part.run(m, "/vera bau " + m + " hier"));
                    list.add(new Part("  ↔ gespiegelt\n", "/vera bau " + m + " hier gespiegelt", 0, false));
                    list.add(new Part("  ×2 doppelt\n", "/vera bau " + m + " hier doppelt so groß", 0, false));
                }
            }
            chapters.add(new Chapter("Bauen", List.of(
                    Part.title("Bauen\n"),
                    Part.type("Baumeister, bau …", "/bm bau "),
                    // Bildschirm mit bewegten Bildern (Karten in Rahmen) und die Redstone-Zahlenanzeige getrennt – vorher
                    // baute „Display“ die Zahlenanzeige, gemeint waren Bilder (Nutzer 2026-11-07).
                    Part.run("Bildschirm", "/bm bildschirm"),
                    Part.run("Zahlenanzeige", "/bm bau eine Zahlenanzeige"),
                    Part.text("Bauwerk merken:\n"),
                    Part.run("Ecke eins", "/vera ecke eins"),
                    Part.run("Ecke zwei", "/vera ecke zwei"),
                    Part.type("Merken als …", "/vera merk dir das Bauwerk als "),
                    Part.text("\n"),
                    Part.text(list.isEmpty() ? "Noch nichts\n" : "Nachbauen:\n"),
                    Part.text(list.isEmpty() ? "gemerkt.\n" : "")), list));
            // Bauformen ohne LLM (QuickForms): sofort, auch wenn kein Modell läuft. HelpBookTest prüft, dass jeder
            // Knopf wirklich den Schnellweg trifft – ein Wort, das ans LLM ginge, fiele dort auf.
            chapters.add(new Chapter("Schnellbau", List.of(
                    Part.title("Schnellbau\n"),
                    Part.text("Sofort, ohne KI:\n"),
                    Part.run("Pyramide", "/bm bau eine Pyramide"),
                    Part.run("Burg", "/bm bau eine Burg"),
                    // Lobby und Portalhalle (Lobby, 2026-11-15): Eingang für einen frischen Server.
                    Part.run("Lobby", "/bm bau eine Lobby"),
                    Part.run("Portalhalle", "/bm bau eine Portalhalle"),
                    Part.run("Mauer", "/bm bau eine Mauer"),
                    Part.run("Brücke", "/bm bau eine Brücke"),
                    Part.run("Garten", "/bm bau einen Garten"),
                    Part.run("Baum", "/bm bau einen Baum"),
                    Part.run("Tanne", "/bm bau eine Tanne"),
                    Part.text("\n"),
                    Part.type("Hochhaus …", "/bm bau ein Hochhaus mit 8 Stockwerken"))));
            // Bauwerk-Dateien: im Buch nur der Einstieg, das Blättern macht das Kistenfenster (SchematicMenu) – bei
            // Hunderten Familien wäre das Buch ein Telefonbuch.
            if (c.extras().schematics() > 0) {
                chapters.add(new Chapter("Sammlung", List.of(
                        Part.title("Sammlung\n"),
                        Part.text(c.extras().schematics() + " Bauwerke\n"),
                        Part.text("aus Dateien.\n"),
                        Part.text("\n"),
                        Part.run("Katalog öffnen", "/bm katalog"),
                        Part.type("Suchen …", "/bm katalog "),
                        Part.run("Liste im Chat", "/bm schematics"),
                        // Bauwerke und Naturgebilde des Spiels (/bm spielbauwerk, 2026-11-15) über die Herkunft.
                        Part.run("Aus dem Spiel", "/bm katalog spiel"),
                        Part.text("\n"),
                        Part.text("Klick im Katalog\n"),
                        Part.text("baut vor dir.\n"),
                        // Lag-Wächter (2026-11-14): Wer erlebt, dass ein Bau gleich wieder verschwindet, findet hier
                        // den Grund – die Chatzeile allein liest man leicht über.
                        Part.text("Lag-Maschinen\n"),
                        Part.text("baue ich nicht.\n"))));
            }
        }

        // Mods für Sprache und Karte: /mods darf jeder, also für jeden.
        chapters.add(new Chapter("Mods", List.of(
                Part.title("Mods\n"),
                Part.text("Für Sprache und\n"),
                Part.text("Karte im Spiel:\n"),
                Part.text("\n"),
                Part.run("Mods & Download", "/mods"),
                Part.text("\n"),
                Part.text("Fabric starten,\n"),
                Part.text("Mods aus der\n"),
                Part.text("Liste dazu.\n"))));

        if (c.can().test("baumeister.admin")) {
            // Frischer Server (2026-11-15, SetupGuide): eigenes Kapitel, weil die Admin-Seite mit 13 Zeilen voll ist –
            // mit drei Knöpfen mehr rutschte die Fußzeile auf Zeile 15 (HelpBookTest.footerIsAlwaysOnTheSameLine).
            chapters.add(new Chapter("Einrichten", List.of(
                    Part.title("Einrichten\n"),
                    Part.text("Frischer Server:\n"),
                    Part.run("Alle Schritte", "/bm einrichten"),
                    Part.run("Lobby", "/bm bau eine Lobby"),
                    Part.run("Riesige Lobby", "/bm bau eine riesige Lobby"),
                    Part.run("Portalhalle", "/bm bau eine Portalhalle"),
                    Part.run("Weltspawn hier", "/bm einrichten spawn"),
                    Part.text("\n"),
                    Part.text("Im leeren Rahmen:\n"),
                    Part.type("Portal …", "/bm portal "),
                    Part.run("Portale", "/bm portal liste"))));
            chapters.add(new Chapter("Admin", List.of(
                    Part.title("Admin\n"),
                    Part.text("\n"),
                    Part.run("Zustand", "/bm status"),
                    Part.run("Leistung", "/bm leistung"),
                    Part.run("Warteschlange", "/bm warteschlange"),
                    Part.run("Pause", "/bm pause"),
                    Part.run("Weiter", "/bm weiter"),
                    Part.run("Stopp", "/bm stop"),
                    Part.run("Rückgängig", "/bm rückgängig"),
                    Part.run("Weltbau", "/bm weltbau status"),
                    Part.run("Neu laden", "/bm neuladen"))));
            if (!c.rights().isEmpty()) {
                // Je Fähigkeit: „Name: Stufe“ und darunter drei Knöpfe. Die Teile einer Fähigkeit enden erst mit dem
                // letzten Knopf auf „\n“ – paginate hält sie dadurch auf einer Seite zusammen.
                List<Part> list = new ArrayList<>();
                for (Right r : c.rights()) {
                    if (!safe(r.key())) {
                        continue;
                    }
                    list.add(Part.text(r.label() + ": " + r.level()));
                    String[][] buttons = {{"Alle", "alle"}, {"Ops", "ops"}, {"Aus", "aus"}};
                    for (int i = 0; i < buttons.length; i++) {
                        boolean now = buttons[i][0].equals(r.level());
                        String text = (i == 0 ? "\n " : "") + (now ? "■" : "□") + buttons[i][0]
                                + (i == buttons.length - 1 ? "\n" : "  ");
                        list.add(new Part(text, RIGHT_COMMAND + r.key() + " " + buttons[i][1], 0, now));
                    }
                }
                if (!c.exceptions().isEmpty()) {
                    list.add(Part.text("Ausnahmen:\n"));
                    c.exceptions().forEach(e -> list.add(Part.text(e + "\n")));
                }
                // Rechte je Spieler (Wunsch 2026-11-07):
                // Klick öffnet die Seite des Spielers mit allen Rechten des Plugins.
                list.add(Part.text("Spieler:\n"));
                for (String name : c.rightPlayers()) {
                    if (safe(name)) {
                        list.add(Part.run(name, PLAYER_RIGHTS_COMMAND + name));
                    }
                }
                list.add(Part.type("Anderer Spieler", PLAYER_RIGHTS_COMMAND));
                chapters.add(new Chapter(RIGHTS_CHAPTER, List.of(
                        Part.title(RIGHTS_CHAPTER + "\n"),
                        Part.text("Ops: Admins immer.\n")), list));
            }
            Voice voice = c.extras().voice();
            if (voice != null) {
                // Stimmen vom Server (VoiceControl): Vera und der Baumeister je für sich, Wahl per Klick, ■ = gewählt.
                // Jede Wahl spricht sofort einen Probesatz – mit der Stimme dessen, der sie bekommen hat.
                List<Part> head = new ArrayList<>(List.of(
                        Part.title(VOICE_CHAPTER + "\n"),
                        Part.text("Server: " + (voice.on() ? "an" : "aus") + "\n")));
                if (!voice.problem().isEmpty()) {
                    head.add(Part.text("⚠ " + voice.problem() + "\n"));
                }
                head.add(voice.on() ? Part.run("Ausschalten", VOICE_COMMAND + "aus")
                        : Part.run("Einschalten", VOICE_COMMAND + "an"));
                head.add(Part.run("Zustand im Chat", VOICE_COMMAND + "status"));
                if (voice.voices().isEmpty()) {
                    head.add(Part.text("Keine Stimmen in\n"));
                    head.add(Part.text("stimmen/.\n"));
                }
                List<Part> list = new ArrayList<>();
                if (!voice.voices().isEmpty()) {
                    list.add(Part.title(c.companion().length() > 17 ? "Begleiter:\n" : c.companion() + ":\n"));
                    list.add(new Part((voice.cartoon() ? "■ " : "□ ") + "Cartoon-Ton\n",
                            VOICE_COMMAND + "ton " + (voice.cartoon() ? "normal" : "cartoon"), 0, voice.cartoon()));
                    list.add(Part.run("Probe hören", VOICE_COMMAND + "probe"));
                    voiceList(list, voice.voices(), voice.vera(), "vera ");
                    list.add(Part.title("Baumeister:\n"));
                    list.add(Part.run("Probe hören", VOICE_COMMAND + "probe baumeister"));
                    voiceList(list, voice.voices(), voice.current(), "stimme ");
                }
                chapters.add(new Chapter(VOICE_CHAPTER, List.copyOf(head), list));
            }
        }

        // Ordnung nach Themen (Wunsch 2026-11-15: „ordne das Menü für den Baumeister komplett“). Vorher standen die
        // Kapitel in der Reihenfolge, in der sie im Code entstehen: sechs Kapitel über den Begleiter vorn, „Bauen“ – die
        // Hauptsache des Plugins – erst an zwölfter Stelle, Server-Dinge dazwischen. Jetzt: Los geht's, Bauen,
        // Begleiter, Gemeinsam, Server; im Inhaltsverzeichnis mit Überschriften. Die Kapitel selbst bleiben, wie sie
        // sind; sortiert wird stabil, innerhalb einer Gruppe gilt die Reihenfolge von oben.
        List<String> order = List.of("", "Bauen", v, "Gemeinsam", "Server", "Mehr");
        chapters.sort(java.util.Comparator.comparingInt(ch -> order.indexOf(section(ch.name(), v))));

        // Seiten bauen: erst alle Kapitel, dann das Inhaltsverzeichnis mit den echten Seitenzahlen davor. Es geht über
        // so viele Seiten wie nötig (bis zu 18 Kapitel für Admins plus Überschriften). Eine Überschrift steht nie allein
        // am Seitenende: sie wandert mit ihrem ersten Kapitel auf die nächste Seite.
        int perToc = CONTENT_LINES - 1;
        List<List<Integer>> tocLayout = new ArrayList<>();
        List<Integer> page = new ArrayList<>();
        String lastSection = null;
        for (int i = 0; i < chapters.size(); i++) {
            String sec = section(chapters.get(i).name(), v);
            boolean header = !sec.isEmpty() && !sec.equals(lastSection);
            int need = header ? 2 : 1;
            if (page.size() + need > perToc) {
                tocLayout.add(page);
                page = new ArrayList<>();
                header = !sec.isEmpty();
            }
            if (header) {
                page.add(-1 - order.indexOf(sec));
            }
            page.add(i);
            lastSection = sec;
        }
        tocLayout.add(page);
        int tocPages = tocLayout.size();
        List<Page> body = new ArrayList<>();
        List<Integer> firstPage = new ArrayList<>();
        for (Chapter ch : chapters) {
            firstPage.add(body.size() + tocPages + 1);
            body.addAll(paginate(ch));
        }
        List<Page> out = new ArrayList<>();
        for (int tp = 0; tp < tocPages; tp++) {
            List<Part> toc = new ArrayList<>();
            toc.add(Part.title(tp == 0 ? "Hilfe\n" : "Hilfe (weiter)\n"));
            for (int entry : tocLayout.get(tp)) {
                if (entry < 0) {
                    String sec = order.get(-1 - entry);
                    toc.add(Part.title((sec.length() > LINE - 1 ? sec.substring(0, LINE - 1) : sec) + "\n"));
                    continue;
                }
                // Sprung per Befehl, nicht per change_page: Wunsch 2026-11-05, obwohl change_page laut Messprobe
                // korrekt im Buch stand (Log dev01 20:18). Der Befehl
                // schlägt das Buch auf der Zielseite neu auf (rotate) – das hängt an keinem Verhalten des Clients.
                toc.add(new Part("» " + chapters.get(entry).name() + "\n", PAGE_COMMAND + firstPage.get(entry),
                        firstPage.get(entry), false));
            }
            out.add(new Page("Inhalt", List.copyOf(toc)));
        }
        out.addAll(body);
        out.add(new Page("Mehr", List.of(
                Part.title("Noch Fragen?\n"),
                Part.text("\n"),
                Part.text("Frag einfach\n"),
                Part.text(v + ".\n"),
                Part.text("\n"),
                Part.run("Buch holen", "/hilfe holen"),
                Part.text("\n"),
                Part.text("Viel Spaß!\n"))));
        return withFooters(out);
    }

    /**
     * Unten auf jeder Seite, immer auf Zeile LINES: ◀ (Seite davor) · Inhalt · ▶ (Seite danach), als Befehle (siehe
     * PAGE_COMMAND). Davor wird bis Zeile CONTENT_LINES aufgefüllt. Ohne Vorgänger/Nachfolger steht der Pfeil grau
     * und ohne Klick da – sonst verrutschte „Inhalt“ auf der ersten und letzten Seite.
     */
    private static List<Page> withFooters(List<Page> pages) {
        List<Page> out = new ArrayList<>();
        int n = pages.size();
        for (int i = 1; i <= n; i++) {
            Page p = pages.get(i - 1);
            List<Part> parts = new ArrayList<>(p.parts());
            String last = parts.isEmpty() ? "\n" : parts.getLast().text();
            if (!last.endsWith("\n")) {
                parts.add(Part.text("\n"));
            }
            int fill = CONTENT_LINES - lines(new Page(p.chapter(), parts));
            if (fill > 0) {
                parts.add(Part.text("\n".repeat(fill)));
            }
            parts.add(i > 1 ? new Part(FOOTER_BACK, PAGE_COMMAND + (i - 1), i - 1, false)
                    : new Part(FOOTER_BACK, null, 0, false));
            parts.add(new Part(FOOTER_HOME, PAGE_COMMAND + 1, 1, false));
            parts.add(i < n ? new Part(FOOTER_NEXT, PAGE_COMMAND + (i + 1), i + 1, false)
                    : new Part(FOOTER_NEXT, null, 0, false));
            out.add(new Page(p.chapter(), List.copyOf(parts)));
        }
        return out;
    }

    /** Fußzeile: Teile in fester Breite, damit „Inhalt“ immer an derselben Stelle steht. */
    public static final String FOOTER_BACK = " ◀   ";
    public static final String FOOTER_HOME = "Inhalt";
    public static final String FOOTER_NEXT = "   ▶";

    /**
     * Buch ab Seite start (1-basiert): start … Ende, dann 1 … start−1. Minecraft schlägt ein Buch immer auf Seite 1
     * auf – so steht die gewünschte vorn, und die normalen Pfeile blättern in der gewohnten Reihenfolge weiter.
     */
    public static List<Page> rotate(List<Page> pages, int start) {
        if (pages.isEmpty()) {
            return pages;
        }
        int s = Math.clamp(start, 1, pages.size()) - 1;
        List<Page> out = new ArrayList<>(pages.subList(s, pages.size()));
        out.addAll(pages.subList(0, s));
        return out;
    }

    /** Kopf auf die erste Seite, die Liste so weit wie es passt, Rest auf Folgeseiten („… weiter“). */
    private static List<Page> paginate(Chapter ch) {
        List<Page> pages = new ArrayList<>();
        List<Part> current = new ArrayList<>(ch.head());
        // Teile, deren Text nicht auf „\n“ endet, gehören mit dem nächsten auf dieselbe Seite (eine Fähigkeit samt
        // Knöpfen auf der Rechte-Seite) – sonst stünde die Beschriftung unten und die Knöpfe auf der nächsten Seite.
        // MAX_LIST zählt Gruppen, nicht Teile: Vorher kappte die Rechte-Seite nach 6 von 9 Fähigkeiten (4 Teile je).
        List<List<Part>> groups = new ArrayList<>();
        List<Part> group = new ArrayList<>();
        for (Part item : ch.list()) {
            group.add(item);
            if (item.text().endsWith("\n")) {
                groups.add(group);
                group = new ArrayList<>();
            }
        }
        if (!group.isEmpty()) {
            groups.add(group);
        }
        if (groups.size() > MAX_LIST) {
            int more = groups.size() - MAX_LIST;
            groups = new ArrayList<>(groups.subList(0, MAX_LIST));
            groups.add(List.of(Part.text("… und " + more + " weitere")));
        }
        for (List<Part> g : groups) {
            List<Part> trial = new ArrayList<>(current);
            trial.addAll(g);
            if (lines(new Page(ch.name(), trial)) > CONTENT_LINES) {
                pages.add(new Page(ch.name(), List.copyOf(current)));
                current = new ArrayList<>(List.of(Part.title(ch.name() + " (weiter)\n")));
            }
            current.addAll(g);
        }
        pages.add(new Page(ch.name(), List.copyOf(current)));
        return pages;
    }

    /**
     * Gruppe eines Kapitels im Inhaltsverzeichnis; "" = ohne Überschrift (nur „Los geht's“). Unbekannte Kapitel
     * landen unter „Mehr“, damit ein neues Kapitel nie unsichtbar wird.
     */
    static String section(String chapter, String companion) {
        if (chapter.equals("Los geht's")) {
            return "";
        }
        if (chapter.equals(companion) || chapter.equals(CHARACTER_CHAPTER) || chapter.equals(VOICE_CHAPTER)) {
            return companion;
        }
        return switch (chapter) {
            case "Bauen", "Schnellbau", "Sammlung" -> "Bauen";
            case "Fragen", "Suchen", "Abenteuer" -> companion;
            case "Zusammen", "Rucksack", "Teamkisten", "Warps", "Aufgaben" -> "Gemeinsam";
            case "Mods", "Einrichten", "Admin", RIGHTS_CHAPTER -> "Server";
            default -> "Mehr";
        };
    }

    /** Passt die Seite ins Buch? */
    public static boolean fitsPage(Page page) {
        return lines(page) <= LINES;
    }

    /** Geschätzte Zeilen der Seite (Umbruch nach Wörtern auf LINE Zeichen). */
    public static int lines(Page page) {
        StringBuilder all = new StringBuilder();
        page.parts().forEach(p -> all.append(p.text()));
        String text = all.toString();
        if (text.endsWith("\n")) {
            // Ein abschließender Umbruch macht keine sichtbare Zeile mehr.
            text = text.substring(0, text.length() - 1);
        }
        int lines = 0;
        for (String para : text.split("\n", -1)) {
            int len = 0;
            int rows = 1;
            for (String word : para.split(" ")) {
                int w = word.length();
                if (len == 0) {
                    len = w;
                } else if (len + 1 + w <= LINE) {
                    len += 1 + w;
                } else {
                    rows++;
                    len = w;
                }
                while (len > LINE) {
                    rows++;
                    len -= LINE;
                }
            }
            lines += rows;
        }
        return lines;
    }
}
