package com.blackn0va.baumeister.companion;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Erkennt, ob eine Chatzeile den Begleiter anspricht, und die Steuerwörter, die ohne LLM wirken (wie ChatIntent beim
 * Baumeister: „komm“ soll sofort gehen, nicht nach einer Denkpause des Modells).
 *
 * Anrede: „Hey Vera, …“, „Hallo Vera …“, „Vera, …“, „@Vera …“ oder der Name allein. Im Gespräch (Begleiter hat in den
 * letzten Sekunden geantwortet und steht nah) gilt auch eine Zeile ohne Namen – das entscheidet CompanionService.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class CompanionChat {

    public enum Command {
        COME, STAY, FOLLOW, DISMISS, UNDO
    }

    private CompanionChat() {
    }

    /**
     * Rest der Zeile nach der Anrede, "" bei bloßer Anrede („Hey Vera!“), null ohne Anrede. Name ohne Rücksicht auf
     * Groß-/Kleinschreibung, als ganzes Wort („Veranda“ ist keine Anrede).
     */
    public static String addressed(String name, String message) {
        if (name == null || name.isBlank() || message == null) {
            return null;
        }
        Pattern p = Pattern.compile("^\\s*(?:(?:hey|hallo|hi|he|ey|ok|okay)\\s*,?\\s+)?@?" + Pattern.quote(name.trim())
                + "(?![\\p{L}\\p{N}])\\s*[,:!?.]*\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
                | Pattern.DOTALL);
        Matcher m = p.matcher(message);
        if (m.matches()) {
            return m.group(1).trim();
        }
        // Name am Ende, mit Komma davor: „Abenteuer starten, Vera.“ (dev01 2026-11-04 22:30 – der Satz ging verloren,
        // erst „Vera, Abenteuer starten“ wirkte). Das Komma ist Pflicht, sonst wäre „ich mag Vera“ eine Anrede.
        Matcher end = Pattern.compile("^\\s*(.+?)\\s*,\\s*@?" + Pattern.quote(name.trim())
                + "(?![\\p{L}\\p{N}])\\s*[!?.]*\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
                | Pattern.DOTALL).matcher(message);
        return end.matches() ? end.group(1).trim() : null;
    }

    /**
     * Satz aus dem Voice-Chat im Spiel: Simple Voice Chat hört alles, was der Spieler sagt – auch zu anderen. Er gilt
     * dem Begleiter nur mit Anrede oder während eines laufenden Gesprächs; sonst null (still verwerfen).
     */
    public static String inGame(String name, String spoken, boolean inConversation) {
        if (spoken == null || spoken.isBlank()) {
            return null;
        }
        String rest = addressed(name, spoken);
        if (rest != null) {
            return rest;
        }
        return inConversation ? spoken.trim() : null;
    }

    /**
     * Antwort auf eine Rückfrage des Begleiters („Soll ich wirklich /stop ausführen?“): TRUE bei ja, FALSE bei nein,
     * sonst null – dann verfällt die Rückfrage, und die Zeile geht normal weiter. Nur ganze kurze Antworten: „ja, und
     * dann bau mir ein Haus“ ist kein Ja zu einem Server-Stopp.
     */
    public static Boolean confirm(String text) {
        if (text == null) {
            return null;
        }
        String t = text.toLowerCase(Locale.ROOT).replaceAll("[!.?,]", "").trim();
        return switch (t) {
            case "ja", "ja bitte", "jawohl", "jo", "yes", "ok", "okay", "mach", "mach das", "mach es", "ja mach",
                    "ja mach das", "bestätigt", "genau" -> Boolean.TRUE;
            case "nein", "nee", "ne", "no", "abbrechen", "lass", "lass es", "lass das", "nein danke", "doch nicht" ->
                Boolean.FALSE;
            default -> null;
        };
    }

    /** „bau mir …“, „baue einen …“, „bauen wir …“ – Verb am Anfang, danach ein Wort, das ein Bauobjekt einleitet. */
    private static final Pattern BUILD_START = Pattern.compile("(?iuU)^(?:bau|baue|bauen)(?:\\s+wir)?\\s+"
            + "((?:mir|uns|hier|noch|mal|ein|eine|einen|den|die|das|dem|zu|neben|daneben|eins)\\b.*)$");
    /**
     * Verhörtes „bau mir“ (dev01 2026-11-04 22:26: „Bauch mir ein Haus“, „bom mir ein Haus“ – das Modell antwortete
     * darauf „Ich kann leider kein Haus bauen“). Nur mit „mir“/„uns“ dahinter: „Baum“ oder „Bauch“ allein bleiben Wörter.
     */
    private static final Pattern BUILD_MISHEARD = Pattern.compile(
            "(?iuU)^(?:bom|baum|bauch|bauh|pau|bao|bow)\\s+((?:mir|uns)\\b.*)$");
    /** „kannst du mir einen Pool bauen?“ – Frage mit „bauen“ am Ende. */
    private static final Pattern BUILD_QUESTION = Pattern.compile(
            "(?iuU)^(?:kannst|könntest|würdest|magst)\\s+du\\s+(?:mir|uns)?\\s*(.+?)\\s+bauen$");
    /**
     * „Kannst du hier ein Loch graben?“, „Du sollst ein Loch graben“, „kannst du ein kleines loch machen“ (dev01-Log):
     * an den Baumeister als „bau mir ein Loch“ (QuickOrders.hole gräbt es ohne Modell).
     */
    private static final Pattern DIG = Pattern.compile("(?iuU)^(?:(?:kannst|könntest|sollst|musst)\\s+du\\s+|du\\s+"
            + "(?:sollst|musst)\\s+|grab|grabe|mach|mache)?\\s*(?:mir\\s+|uns\\s+)?(?:hier\\s+|mal\\s+)*(?:ein|eine)\\s+"
            + "(kleines|großes|tiefes|kleine|große|tiefe)?\\s*(?:loch|grube|schacht)(?:\\s+(?:graben|machen|buddeln))?$");
    /** „du sollst mir ein Haus bauen“ (dev01-Log 2026-11-05) – Auftrag mit „sollst“. */
    private static final Pattern BUILD_SHOULD = Pattern.compile(
            "(?iuU)^(?:du\\s+)?(?:sollst|musst|kannst|darfst)\\s+(?:du\\s+)?(?:mir|uns)\\s+(?:mal\\s+)?(.+?)\\s+(?:bauen|hinbauen)$");
    /** Gesprochenes Vorwort ohne Inhalt: „Ja, baue mir …“, „Okay, gib mir …“, „Vera, …“. */
    private static final Pattern LEAD = Pattern.compile(
            "(?iuU)^(?:ja|jo|jep|okay|ok|gut|also|und|bitte|nein|nee|nö|hey|hallo|vera|hey\\s+vera|du)\\s*[,!.]?\\s+");

    /**
     * Vorwörter vorn weg (auch mehrere: „Ja, okay, bau mir …“). Vorher ging „Ja, baue mir eine spannende
     * Redstone-Anlage“ am direkten Bauweg vorbei ans Modell (dev01-Log 2026-11-05). „du“ nur mit Komma/Punkt dahinter
     * nicht – „du sollst …“ braucht es.
     */
    public static String stripLead(String text) {
        String t = text == null ? "" : text.trim();
        for (int i = 0; i < 4; i++) {
            Matcher m = LEAD.matcher(t);
            if (!m.find()) {
                break;
            }
            String head = m.group().trim().toLowerCase(Locale.ROOT).replaceAll("[,!.]", "");
            if (head.equals("du")) {
                break;
            }
            t = t.substring(m.end()).trim();
        }
        return t;
    }

    private static final Pattern QUESTION_WORD = Pattern.compile(
            "(?iuU)^(?:was|wie|wo|warum|wieso|welche|welchen|welches|wann|womit)\\b.*");

    /**
     * Klarer Bauauftrag an Vera → Text für den Baumeister, ohne LLM; sonst leer.
     *
     * Vorfall dev01 2026-11-04 19:10: „Bau mir einen Pool“ an Vera, während der Host noch einen Bauplan rechnete –
     * Veras Frage ans Modell endete nach 13 s mit HTTP 503, der Auftrag ging verloren. Ein Satz, der mit „bau mir …“
     * beginnt, braucht kein Modell: Vera hätte ohnehin nur {"op":"build"} geantwortet. Jetzt geht er sofort zum
     * Baumeister (dort entscheiden Schnellaufträge, Vorlagen oder das LLM). Fragen („was kannst du mir bauen?“,
     * „wie baut man …“) bleiben beim Modell. Wer das Muster weiter fasst, macht aus Gesprächen Bauaufträge.
     */
    public static java.util.Optional<String> buildOrder(String text) {
        if (text == null) {
            return java.util.Optional.empty();
        }
        String t = stripLead(text).replaceAll("[!.?]+$", "").trim();
        // Tunnel, Treppe nach oben, Brücke, „bau die Brücke weiter“ (Wunsch 2026-11-16, „mit dem Baumeister oder
        // Begleiter einen Tunnel graben“): so, wie gesagt, an den Baumeister – TunnelOrder liest Länge, Tempo und
        // Richtung dort. Nur mit Tunnel-, Treppen- oder Brückenwort: „bau weiter“ allein bleibt beim Modell.
        java.util.Optional<com.blackn0va.baumeister.plan.TunnelOrder> dug =
                com.blackn0va.baumeister.plan.TunnelOrder.parse(t);
        if (dug.isPresent() && dug.get().kind() != null) {
            return java.util.Optional.of(t);
        }
        Matcher dig = DIG.matcher(t);
        if (dig.matches()) {
            return java.util.Optional.of("bau mir ein " + (dig.group(1) == null ? "" : dig.group(1).trim() + " ")
                    + "Loch");
        }
        Matcher should = BUILD_SHOULD.matcher(t);
        if (should.matches() && !QUESTION_WORD.matcher(should.group(1)).matches()) {
            return java.util.Optional.of("bau mir " + should.group(1).trim());
        }
        Matcher m = BUILD_START.matcher(t);
        if (m.matches()) {
            return java.util.Optional.of("bau " + m.group(1).trim());
        }
        m = BUILD_MISHEARD.matcher(t);
        if (m.matches()) {
            return java.util.Optional.of("bau " + m.group(1).trim());
        }
        m = BUILD_QUESTION.matcher(t);
        if (m.matches() && !QUESTION_WORD.matcher(m.group(1)).matches()) {
            return java.util.Optional.of("bau mir " + m.group(1).trim());
        }
        return java.util.Optional.empty();
    }

    /** Abenteuer-Sätze (CompanionAdventure). START_* beginnen einen Lauf, die übrigen gelten nur in einem laufenden. */
    public enum AdventureCommand {
        START_NORMAL, START_HARDCORE, STOP, STATUS, WAY, HINT
    }

    private static final Pattern ADV_WORD = Pattern.compile(
            "\\b(abenteuer|abendreuer|abenteur|abentheuer|abendteuer|abenteuerreise|story|"
                    + "minecraft (zusammen |richtig |normal |durch)?spielen|spielen wir|zusammen spielen|"
                    + "lass uns spielen|durchspielen|hardcore|hardkor|hard core)\\b", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ADV_START = Pattern.compile(
            "\\b(start|starte|starten|beginn|beginne|beginnen|lass uns|lass mal|los|wollen wir|spielen wir|neu|neues|"
                    + "machen wir|auf geht|können wir|könnten wir|wollen|machen|möchte|hätte gern|hast du lust)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ADV_STOP = Pattern.compile(
            "\\b(beend|stopp|stop\\b|aufhör|hör auf|abbrech|brich ab|genug|schluss)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ADV_HARDCORE = Pattern.compile("\\b(hardcore|hardkor|hard core|ein leben|einem leben)\\b", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ADV_STATUS = Pattern.compile(
            "\\b(wie weit bin ich|wie weit sind wir|wo stehen wir|welche etappe|fortschritt|wie viele etappen)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ADV_WAY = Pattern.compile(
            "\\b(wo geht.{0,3} (lang|hin)|wohin (jetzt|als nächstes|muss ich|soll ich|gehen)|wo lang\\b|welche richtung|"
                    + "zeig mir den weg|in welche richtung|wo muss ich hin|wo finde ich das)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ADV_HINT = Pattern.compile(
            "\\b(was (jetzt|nun|als nächstes|machen wir jetzt)|was soll ich (jetzt |als nächstes |nun )?(tun|machen)|"
                    + "nächstes ziel|was ist (das nächste|mein|unser) ziel|wie geht es weiter|wie geht.s weiter|"
                    + "gib mir einen tipp|einen tipp|wie mache ich das|kannst du mir helfen|hilf mir|ich brauche hilfe|"
                    + "ich komme nicht weiter|was muss ich (jetzt |als nächstes |nun )?(tun|machen))", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Abenteuer ohne Modell erkennen – die Geschichte rechnet das Plugin (Adventure), das LLM würde Etappen erfinden.
     * Start nur mit Abenteuerwort UND Startwort („lass uns Minecraft spielen“, „starte das Abenteuer im Hardcore“),
     * Ende nur mit Abenteuerwort und Stoppwort; Fragen („was ist das Abenteuer?“) gehen an das Modell. STATUS/WAY/HINT
     * gelten auch ohne Abenteuerwort, der Aufrufer nimmt sie nur in einem laufenden Abenteuer.
     */
    public static AdventureCommand adventure(String text) {
        if (text == null) {
            return null;
        }
        String t = text.toLowerCase(Locale.ROOT).replaceAll("[!?.,;:]", " ").replaceAll("\\s+", " ").trim();
        boolean word = ADV_WORD.matcher(t).find();
        if (word && ADV_STOP.matcher(t).find()) {
            return AdventureCommand.STOP;
        }
        if (word && ADV_START.matcher(t).find() && !t.startsWith("was ") && !t.startsWith("wie ")) {
            return ADV_HARDCORE.matcher(t).find() ? AdventureCommand.START_HARDCORE : AdventureCommand.START_NORMAL;
        }
        if (ADV_STATUS.matcher(t).find()) {
            return AdventureCommand.STATUS;
        }
        if (ADV_WAY.matcher(t).find()) {
            return AdventureCommand.WAY;
        }
        if (ADV_HINT.matcher(t).find()) {
            return AdventureCommand.HINT;
        }
        return null;
    }

    private static final Pattern JOKE = Pattern.compile(
            "(?iuU)\\b((erzähl|erzähle|sag|mach)\\s+(mir\\s+|uns\\s+)?(mal\\s+)?(noch\\s+)?(einen|nen|'nen)\\s+witz|"
                    + "kennst du (einen|nen) witz|noch (einen|nen) witz|witz bitte|bring mich zum lachen)\\b");
    private static final Pattern RANKING = Pattern.compile(
            "(?iuU)\\b(wer ist am weitesten|wer ist (der |die )?beste|rangliste|bestenliste|highscore|wer führt|"
                    + "wer ist vorne)\\b");

    private static final Pattern DIARY = Pattern.compile(
            "(?iuU)\\b((zeig|öffne|zeige)\\s+(mir\\s+)?(mein|das|unser)\\s+(abenteuer)?tagebuch|"
                    + "(abenteuer)?tagebuch( bitte| zeigen| öffnen)?$|was steht (im|in meinem) tagebuch)");

    /** „Zeig mein Tagebuch“ – das Buch des Abenteuers (AdventureDiary). */
    public static boolean askDiary(String text) {
        return text != null && DIARY.matcher(text.trim().replaceAll("[!.?]+$", "")).find();
    }

    /** „Erzähl mir einen Witz“ – ohne Modell (Jokes). */
    public static boolean askJoke(String text) {
        return text != null && JOKE.matcher(text).find();
    }

    /** „Wer ist am weitesten?“ – Rangliste der Abenteuer aller Spieler. */
    public static boolean askRanking(String text) {
        return text != null && RANKING.matcher(text).find();
    }

    private static final Pattern DEATH = Pattern.compile(
            "(?iuU)\\b(wo bin ich (zuletzt )?gestorben|wo (sind|liegen) meine sachen|wo ist mein (zeug|inventar|kram)|"
                    + "bring mich zu meinen sachen|zeig mir meine sachen|wo bin ich (eben |gerade )?(ge)?storben)\\b");

    /** „Wo bin ich gestorben?“, „wo liegen meine Sachen?“ – ohne Modell zum letzten Tod (Weg oder Teleport). */
    public static boolean askDeath(String text) {
        return text != null && DEATH.matcher(text).find();
    }

    /** Gegenstandswunsch: Wortlaut des Gegenstands und Anzahl (0 = keine genannt, der Aufrufer wählt). */
    public record GiveOrder(String item, int count) {
    }

    private static final Pattern GIVE_START = Pattern.compile(
            "(?iuU)^(?:gib|gebe)\\s+(?:mir|uns)\\s+(?:(?:mal|bitte|noch|doch)\\s+)*(.+?)(?:\\s+bitte)?$");
    private static final Pattern GIVE_QUESTION = Pattern.compile(
            "(?iuU)^(?:(?:kannst|könntest|würdest|magst)\\s+du|kann\\s+ich)\\s+(?:mir|uns)\\s+(?:(?:mal|bitte|noch|doch)\\s+)*(.+?)"
                    + "\\s+(?:geben|haben)(?:\\s+bitte)?$");
    /** „Du sollst mir TNT geben“ (dev01-Log). */
    private static final Pattern GIVE_SHOULD = Pattern.compile(
            "(?iuU)^(?:du\\s+)?(?:sollst|musst)\\s+(?:mir|uns)\\s+(?:mal\\s+|bitte\\s+|noch\\s+)*(.+?)\\s+geben$");
    /** „ich will Elytren“, „ich brauche Fackeln“ – nur wenn der Rest sicher ein Gegenstand ist (Aufrufer prüft). */
    private static final Pattern GIVE_WANT = Pattern.compile(
            "(?iuU)^(?:ich\\s+)?(?:will|möchte|hätte\\s+gern|hätte\\s+gerne|brauche|brauch)\\s+(?:mal\\s+|bitte\\s+|noch\\s+)*(.+?)(?:\\s+bitte)?$");
    private static final Pattern STACK = Pattern.compile("(?iuU)^(?:einen|ein|1)\\s+(?:stack|stapel)\\s+(.+)$");
    private static final Pattern COUNTED = Pattern.compile("(?iuU)^(\\d{1,4}|\\p{L}+)\\s+(?:stück\\s+)?(.+)$");
    private static final java.util.Map<String, Integer> NUMBERS = java.util.Map.ofEntries(
            java.util.Map.entry("ein", 1), java.util.Map.entry("eine", 1), java.util.Map.entry("einen", 1),
            java.util.Map.entry("eins", 1), java.util.Map.entry("zwei", 2), java.util.Map.entry("drei", 3),
            java.util.Map.entry("vier", 4), java.util.Map.entry("fünf", 5), java.util.Map.entry("sechs", 6),
            java.util.Map.entry("sieben", 7), java.util.Map.entry("acht", 8), java.util.Map.entry("neun", 9),
            java.util.Map.entry("zehn", 10), java.util.Map.entry("zwölf", 12), java.util.Map.entry("sechzehn", 16),
            java.util.Map.entry("zwanzig", 20), java.util.Map.entry("zweiunddreißig", 32),
            java.util.Map.entry("vierundsechzig", 64));
    /** Artikel ohne Zahlwert: „gib mir die Elytren“. */
    private static final java.util.Set<String> ARTICLES = java.util.Set.of("der", "die", "das", "den", "dem",
            "etwas", "mein", "meine", "meinen");

    /**
     * Klarer Gegenstandswunsch an Vera („gib mir 16 Fackeln“, „kannst du mir Elytren geben?“) → Wortlaut und Anzahl,
     * sonst leer. Ob der Wortlaut ein Gegenstand ist, entscheidet der Aufrufer mit ItemNames.resolveStrict – trifft er
     * nicht sicher, geht der Satz an das Modell.
     *
     * Vorfall dev01 2026-11-04 20:21: „Kannst du mir Elytren geben?“ – das Modell antwortete ohne Aktion, erst
     * „Nein, ich will Elitren“ brachte die Elytren. Den Satz versteht das Plugin selbst; ob gegeben werden darf, prüft
     * wie immer give() (Kreativ oder Recht). Höchstens drei Wörter für den Gegenstand, sonst ist es kein klarer Wunsch.
     */
    public static java.util.Optional<GiveOrder> giveOrder(String text) {
        if (text == null) {
            return java.util.Optional.empty();
        }
        String t = stripLead(text).replaceAll("[!.?]+$", "").trim();
        Matcher m = GIVE_START.matcher(t);
        if (!m.matches()) {
            m = GIVE_QUESTION.matcher(t);
            if (!m.matches()) {
                m = GIVE_SHOULD.matcher(t);
                if (!m.matches()) {
                    m = GIVE_WANT.matcher(t);
                    if (!m.matches()) {
                        return java.util.Optional.empty();
                    }
                }
            }
        }
        return counted(m.group(1).trim());
    }

    private static final Pattern CRAFT_START = Pattern.compile(
            "(?iuU)^(?:craft|crafte|craften)\\s+(?:mir|uns)\\s+(?:(?:mal|bitte|noch|doch|schnell)\\s+)*(.+?)(?:\\s+bitte)?$");
    private static final Pattern CRAFT_MAKE = Pattern.compile(
            "(?iuU)^(?:stell|stelle)\\s+(?:mir|uns)\\s+(?:(?:mal|bitte|noch|doch|schnell)\\s+)*(.+?)\\s+her(?:\\s+bitte)?$");
    private static final Pattern CRAFT_QUESTION = Pattern.compile(
            "(?iuU)^(?:(?:kannst|könntest|würdest|magst)\\s+du)\\s+(?:mir|uns)\\s+(?:(?:mal|bitte|noch|doch)\\s+)*(.+?)"
                    + "\\s+(?:craften|herstellen|basteln)(?:\\s+bitte)?$");

    /**
     * Craft-Wunsch an Vera („crafte mir 4 Fackeln“, „stell mir eine Werkbank her“, „kannst du mir zwei Eimer craften?“)
     * → Wortlaut und Anzahl wie bei giveOrder, sonst leer. Sie stellt aus dem Inventar des Spielers her
     * (CompanionCraft), ob der Wortlaut ein Gegenstand ist, entscheidet der Aufrufer (ItemNames.resolveStrict).
     */
    public static java.util.Optional<GiveOrder> craftOrder(String text) {
        if (text == null) {
            return java.util.Optional.empty();
        }
        String t = stripLead(text).replaceAll("[!.?]+$", "").trim();
        for (Pattern p : java.util.List.of(CRAFT_START, CRAFT_MAKE, CRAFT_QUESTION)) {
            Matcher m = p.matcher(t);
            if (m.matches()) {
                return counted(m.group(1).trim());
            }
        }
        return java.util.Optional.empty();
    }

    /** Anzahl und Gegenstand aus „16 Fackeln“, „einen Stapel Holz“, „die Elytren“ (giveOrder, craftOrder). */
    private static java.util.Optional<GiveOrder> counted(String rest) {
        int count = 0;
        Matcher s = STACK.matcher(rest);
        if (rest.toLowerCase(Locale.ROOT).startsWith("ein paar ")) {
            rest = rest.substring("ein paar ".length()).trim();
        } else if (s.matches()) {
            count = 64;
            rest = s.group(1).trim();
        } else {
            Matcher c = COUNTED.matcher(rest);
            if (c.matches()) {
                String w = c.group(1).toLowerCase(Locale.ROOT);
                if (w.chars().allMatch(Character::isDigit)) {
                    count = Integer.parseInt(w);
                    rest = c.group(2).trim();
                } else if (NUMBERS.containsKey(w)) {
                    count = NUMBERS.get(w);
                    rest = c.group(2).trim();
                } else if (ARTICLES.contains(w)) {
                    rest = c.group(2).trim();
                }
            }
        }
        if (rest.isEmpty() || rest.split("\\s+").length > 3 || QUESTION_WORD.matcher(rest).matches()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new GiveOrder(rest, count));
    }

    // ---------------------------------------------------------------- Rucksack (RucksackService)
    // Ohne Modell: Das LLM kennt den Inhalt des Rucksacks nicht und würde ihn erfinden. Jeder Satz braucht das Wort
    // „Rucksack“, sonst bleibt er beim Modell (so wird „wo ist mein Pferd“ nie zur Rucksack-Suche).

    private static final Pattern RS_FROM = Pattern.compile(
            "(?iuU)\\s*\\b(?:aus|von)\\s+(?:meinem|dem|deinem|unserem)\\s+rucksack\\b\\s*");
    private static final Pattern RS_OPEN = Pattern.compile(
            "(?iuU)^(?:(?:öffne|öffnen|zeig|zeige|mach)\\s+(?:mir\\s+)?(?:mal\\s+)?(?:meinen|den)\\s+rucksack"
                    + "(?:\\s+auf)?|rucksack(?:\\s+(?:auf|öffnen|bitte))?)$");
    private static final java.util.List<Pattern> RS_SEARCH = java.util.List.of(
            Pattern.compile("(?iuU)^wo\\s+(?:im\\s+rucksack\\s+)?(?:ist|sind|liegt|liegen|hab ich|habe ich)\\s+"
                    + "(?:mein|meine|meinen|der|die|das|den)?\\s*(.+?)\\s+(?:im|in meinem)\\s+rucksack$"),
            Pattern.compile("(?iuU)^wo\\s+im\\s+rucksack\\s+(?:ist|sind|liegt|liegen)\\s+"
                    + "(?:mein|meine|meinen|der|die|das|den)?\\s*(.+)$"),
            Pattern.compile("(?iuU)^(?:hab|habe|hast)\\s+(?:ich|du)\\s+(?:noch\\s+)?(?:ein|eine|einen|welche)?\\s*"
                    + "(.+?)\\s+(?:im|in meinem)\\s+rucksack$"),
            Pattern.compile("(?iuU)^(?:such|suche)\\s+(?:mir\\s+)?(?:mal\\s+)?(?:nach\\s+)?(?:meinem|meiner|meinen|"
                    + "meine|dem|der|den|die)?\\s*(.+?)\\s+im\\s+rucksack$"),
            Pattern.compile("(?iuU)^(?:such|suche|durchsuche)\\s+(?:mal\\s+)?(?:im|den|meinen)\\s+rucksack\\s+nach\\s+"
                    + "(?:meinem|meiner|meinen|meine|dem|der|den|die)?\\s*(.+)$"),
            Pattern.compile("(?iuU)^(?:ist|sind)\\s+(?:noch\\s+)?(.+?)\\s+(?:im|in meinem)\\s+rucksack$"));
    private static final Pattern RS_COLLECT = Pattern.compile(
            "(?iuU)^(?:sammel|sammle|sammele|pack|packe|steck|stecke)\\s+(?:ab jetzt\\s+|immer\\s+|automatisch\\s+)*"
                    + "(?:alle[ns]?\\s+|den\\s+|die\\s+|das\\s+)?(.+?)\\s+(?:immer\\s+|automatisch\\s+)*"
                    + "(?:in\\s+(?:den|meinen)\\s+rucksack|ein)$");

    private static String plain(String text) {
        return text == null ? "" : text.trim().replaceAll("[!.?]+$", "").trim();
    }

    /** „Öffne meinen Rucksack“, „Rucksack auf“. */
    public static boolean rucksackOpen(String text) {
        return RS_OPEN.matcher(plain(text)).matches();
    }

    /** „Gib mir 32 Fackeln aus meinem Rucksack“, „hol mir die Elytren aus dem Rucksack“ → wie giveOrder. */
    public static java.util.Optional<GiveOrder> rucksackTake(String text) {
        String t = plain(text);
        Matcher m = RS_FROM.matcher(t);
        if (!m.find()) {
            return java.util.Optional.empty();
        }
        String rest = (t.substring(0, m.start()) + " " + t.substring(m.end())).trim();
        rest = rest.replaceFirst("(?iuU)^(?:hol|hole|nimm|bring)\\s+(mir|uns)\\b", "gib $1");
        rest = rest.replaceFirst("(?iuU)\\s+(?:raus|heraus)$", "");
        return giveOrder(rest);
    }

    /** „Wo ist mein Diamant im Rucksack?“, „Hab ich Eisen im Rucksack?“ → Suchwort. */
    public static java.util.Optional<String> rucksackSearch(String text) {
        String t = plain(text);
        for (Pattern p : RS_SEARCH) {
            Matcher m = p.matcher(t);
            if (m.matches() && !m.group(1).isBlank() && m.group(1).split("\\s+").length <= 3) {
                return java.util.Optional.of(m.group(1).trim());
            }
        }
        return java.util.Optional.empty();
    }

    /** „Sammel Bruchstein automatisch in den Rucksack“ → Gegenstandswort für den Einsammel-Filter. */
    public static java.util.Optional<String> rucksackCollect(String text) {
        Matcher m = RS_COLLECT.matcher(plain(text));
        if (!m.matches() || m.group(1).split("\\s+").length > 3) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(m.group(1).trim());
    }

    // ---------------------------------------------------------------- Protokoll (CoreProtect über BlockHistory)

    private static final Pattern CHEST = Pattern.compile(
            "(?iuU)\\b(wer\\s+(war|ist)\\s+(an|bei|in)\\s+(meiner|der|dieser|meinen|den)\\s+(kiste|truhe|kisten|truhen)|"
                    + "wer\\s+hat\\s+(was\\s+|etwas\\s+|sachen\\s+)?(aus|in)\\s+(meiner|der|dieser)\\s+(kiste|truhe)|"
                    + "wer\\s+hat\\s+mich\\s+beklaut|wer\\s+hat\\s+meine\\s+sachen\\s+(genommen|geklaut))\\b");
    private static final Pattern REPAIR = Pattern.compile(
            "(?iuU)\\b(reparier|repariere|reparieren)\\b.*\\b(haus|hütte|schaden|schäden|loch|löcher|krater|"
                    + "basis|burg|alles|das)\\b|\\b(haus|hütte|schaden|schäden|loch|löcher|krater|basis|burg)\\b.*"
                    + "\\b(reparier|repariere|reparieren)\\b|\\bmach\\s+(den\\s+)?(creeper|explosions)?schaden\\s+weg\\b|"
                    + "\\bbau\\s+(das|mein\\s+haus|meine\\s+basis)\\s+wieder\\s+auf\\b");

    // ---------------------------------------------------------------- Redstone (redstone/RedstoneReader)

    private static final String REDSTONE_THINGS = "schaltung|redstone|anlage|maschine|lampe|lampen|tür|türen|"
            + "falltür|tor|kolben|verstärker|komparator|beobachter|fackel|hebel|knopf|druckplatte|werfer|spender|"
            + "trichter|notenblock|glocke|klingel|farm|aufzug|fahrstuhl|signal|staub|computer|rechner|cpu|"
            + "prozessor|anzeige|display";
    private static final Pattern REDSTONE_REPAIR = Pattern.compile("(?iuU)\\b(reparier|repariere|reparieren|richte|"
            + "fix)\\w*\\s+(mal\\s+)?(die|meine|diese|das|mein|den|meinen)\\s+(" + REDSTONE_THINGS + ")\\b|"
            // Verb am Ende (dev01-Log, 6×): „kannst du die Maschine hier reparieren?“
            + "\\b(die|meine|diese|das|mein|den|meinen|dieses)\\s+(" + REDSTONE_THINGS + ")\\s+(hier\\s+)?(mal\\s+)?"
            + "(reparieren|fixen|richten|heil\\s+machen)\\b");
    private static final Pattern REDSTONE_CHECK = Pattern.compile("(?iuU)\\b(die|meine|diese|das|mein|den|meinen|"
            + "dieses)\\s+(" + REDSTONE_THINGS + ")\\s+(hier\\s+)?(mal\\s+)?(reparieren|fixen|richten|prüfen)\\b|"
            + "\\b(prüf|prüfe|check|teste|kontrollier|"
            + "reparier|repariere|reparieren|richte|fix)\\w*"
            + "\\s+(mal\\s+)?(die|meine|diese|das|mein|den|meinen)\\s+(" + REDSTONE_THINGS + ")\\b|"
            + "\\bwarum\\s+(geht|funktioniert|leuchtet|öffnet|fährt|läuft|klappt|zündet|feuert)\\s+.*\\b("
            + REDSTONE_THINGS + ")\\b.*\\bnicht\\b|"
            + "\\bwarum\\s+.*\\b(" + REDSTONE_THINGS + ")\\b.*\\bnicht\\s+(geht|funktioniert|leuchtet|aufgeht|"
            + "ausfährt|läuft|klappt)\\b|"
            + "\\b(" + REDSTONE_THINGS + ")\\s+(geht|funktioniert|leuchtet|klappt|läuft)\\s+(noch\\s+)?nicht\\b|"
            + "\\bwas\\s+ist\\s+(los\\s+)?mit\\s+(der|meiner|dieser|dem|meinem)\\s+(" + REDSTONE_THINGS + ")\\b");
    private static final Pattern REDSTONE_TOPIC = Pattern.compile("(?iuU)\\b(" + REDSTONE_THINGS
            + "|strom|takt|gatter|logik|speicher|flipflop|zähler)\\b");

    /**
     * „Prüf meine Schaltung“, „warum geht die Lampe nicht?“: Vera liest die Schaltung (RedstoneReader) und sagt den
     * Fehler direkt – ohne Modell, das 14B riet bei Redstone ohne Blickkontakt.
     */
    public static boolean askRedstoneCheck(String text) {
        return text != null && REDSTONE_CHECK.matcher(text).find();
    }

    /**
     * „Repariere meine Schaltung“ (Nutzer 2026-11-06 im Spiel – ging ans Modell, das gerade nicht lief). Vera prüft und
     * sagt, was klemmt; umbauen tut sie nicht: Ein „falsch herum“ ist nur vermutet, und in fremden Anlagen ist ein
     * gesperrter Verstärker oft gewollt.
     */
    public static boolean askRedstoneRepair(String text) {
        return text != null && REDSTONE_REPAIR.matcher(text).find();
    }

    private static final Pattern REDSTONE_LOGIC = Pattern.compile("(?iuU)\\bwas\\s+(macht|tut|kann)\\s+(die|diese|"
            + "meine|das|der|mein|dieser)\\s+(schaltung|anlage|maschine|gatter|logik|redstone)\\b|"
            + "\\b(probier|teste|schalt)\\w*\\s+(mal\\s+)?(die|diese|meine)\\s+(schaltung|hebel|anlage)\\s+(aus|durch)\\b|"
            + "\\bwahrheitstabelle\\b|\\bwelche\\s+logik\\b|\\bfinde?\\s+(mal\\s+)?heraus,?\\s+was\\s+(die|diese|meine)\\s+"
            + "(schaltung|anlage)");

    /**
     * „Was macht diese Schaltung?“, „Probier die Schaltung aus“: Vera legt die Hebel durch (RedstoneLab) und sagt die
     * Formel je Ausgang – echte Physik des Servers statt Raten.
     */
    public static boolean askRedstoneLogic(String text) {
        return text != null && REDSTONE_LOGIC.matcher(text).find();
    }

    private static final Pattern HOW_TO = Pattern.compile("(?iuU)\\b(wie\\s+(funktioniert|benutze|bediene|nutze|"
            + "geht|startet|benutzt|bedient|verwende)|versteh\\w*\\s+nicht,?\\s+wie|was\\s+macht\\s+(der|die|das)\\s+"
            + "(computer|rechner|maschine|anlage|knopf|hebel|schalter))\\b");

    /**
     * „Wie benutze ich den Computer?“, „Ich versteh nicht, wie der Computer funktioniert“ (dev01-Log): Steht der Spieler
     * in einem Baumeister-Bau, kennt das Baubuch die Bedienung – Vera sagt sie ohne Modell.
     */
    public static boolean askHowTo(String text) {
        return text != null && HOW_TO.matcher(text).find();
    }

    private static final Pattern FIX = Pattern.compile("(?iuU)\\b(reparier|repariere|reparieren|richte|fixen|heil\\s+machen)\\b");

    /**
     * Irgendein Reparaturwunsch („kannst du das hier reparieren?“, „Herr Vera, kannst du das reparieren, die Anlage?“,
     * dev01-Log) – CompanionService entscheidet am Ort: Baumeister-Bau → Baubuch, Redstone → RedstoneRepair, sonst
     * Schäden durch Creeper & Co. Vorher gingen diese Sätze ans Modell.
     */
    public static boolean askFix(String text) {
        return text != null && FIX.matcher(text).find();
    }

    /** Frage dreht sich um Redstone – dann bekommt das Modell die gelesene Schaltung als Kontext. */
    public static boolean aboutRedstone(String text) {
        return text != null && REDSTONE_TOPIC.matcher(text).find();
    }

    /** „Wer war an meiner Kiste?“ – Kisten-Verlauf der Kiste, auf die der Spieler schaut. */
    public static boolean askChest(String text) {
        return text != null && CHEST.matcher(text).find();
    }

    /** „Reparier mein Haus“, „mach den Creeperschaden weg“. */
    public static boolean askRepair(String text) {
        return text != null && REPAIR.matcher(text).find();
    }

    // ---------------------------------------------------------------- Bauwerk merken und nachbauen

    private static final Pattern CORNER = Pattern.compile(
            "(?iuU)^(?:(?:ecke|punkt)\\s+(eins|1|zwei|2)|(erste|zweite)\\s+ecke)(?:\\s+(?:hier|da|ist hier))?$");
    private static final Pattern REMEMBER_BUILD = Pattern.compile(
            "(?iuU)^(?:merk\\s+dir|merke\\s+dir|speicher|speichere)\\s+(?:das|den|dieses|diesen|die|diese)\\s+"
                    + "(?:bauwerk|gebäude|bau|haus|bauteil|vorlage|hütte|turm|ding)\\s+als\\s+([\\p{L}0-9_]{2,32})$");
    private static final Pattern REBUILD = Pattern.compile(
            "(?iuU)^(?:bau|baue)\\s+(?:mir\\s+)?(?:das\\s+|den\\s+|die\\s+|mein\\s+|meinen\\s+|meine\\s+)?"
                    + "([\\p{L}0-9_]{2,32})\\s+(?:hier|nochmal|noch\\s+mal|nochmals|da|hierhin)"
                    + "((?:\\s+(?:gespiegelt|spiegelverkehrt|doppelt\\s+so\\s+groß|größer|riesig|in\\s+groß))*)$");

    /** „Ecke eins“, „zweite Ecke hier“ → 1 oder 2, sonst 0. */
    public static int corner(String text) {
        Matcher m = CORNER.matcher(plain(text));
        if (!m.matches()) {
            return 0;
        }
        String w = (m.group(1) != null ? m.group(1) : m.group(2)).toLowerCase(Locale.ROOT);
        return w.equals("eins") || w.equals("1") || w.equals("erste") ? 1 : 2;
    }

    /** „Merk dir das Bauwerk als turm1“ → Name (klein, Umlaute ausgeschrieben – er wird Dateiname). */
    public static java.util.Optional<String> rememberBuild(String text) {
        Matcher m = REMEMBER_BUILD.matcher(plain(text));
        return m.matches() ? java.util.Optional.of(moduleName(m.group(1))) : java.util.Optional.empty();
    }

    /** Nachbauen: Name, gespiegelt, doppelt so groß. */
    public record Rebuild(String name, boolean mirrored, boolean doubled) {
    }

    /** „Bau turm1 hier gespiegelt“, „bau mein haus nochmal doppelt so groß“. */
    public static java.util.Optional<Rebuild> rebuild(String text) {
        Matcher m = REBUILD.matcher(plain(text));
        if (!m.matches()) {
            return java.util.Optional.empty();
        }
        String extra = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
        return java.util.Optional.of(new Rebuild(moduleName(m.group(1)), extra.contains("spiegel"),
                extra.contains("groß") || extra.contains("riesig")));
    }

    /** Bauteilname: klein, Umlaute ausgeschrieben (SchematicLibrary.validName: a–z, 0–9, _). */
    static String moduleName(String raw) {
        return raw.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue") // umlaut-ok
                .replace("ß", "ss"); // umlaut-ok
    }

    // ---------------------------------------------------------------- Team-Abenteuer

    private static final java.util.List<Pattern> INVITE = java.util.List.of(
            Pattern.compile("(?iuU)^(?:lade|hol)\\s+([A-Za-z0-9_]{3,16})\\s+(?:ins|zum|in unser|in mein)\\s+"
                    + "abenteuer(?:\\s+ein)?$"),
            Pattern.compile("(?iuU)^(?:wir\\s+)?(?:spiel|spielen|spiele)\\s+(?:jetzt\\s+)?(?:zusammen\\s+)?mit\\s+"
                    + "([A-Za-z0-9_]{3,16})(?:\\s+zusammen)?(?:\\s+weiter)?$"),
            Pattern.compile("(?iuU)^([A-Za-z0-9_]{3,16})\\s+(?:soll|will|darf)\\s+(?:auch\\s+)?mitspielen$"));

    /** „Lade Alex ins Abenteuer ein“, „spiel mit Alex zusammen“ → Spielername (Minecraft-Namen: 3–16 Zeichen). */
    public static java.util.Optional<String> adventureInvite(String text) {
        String t = plain(text);
        for (Pattern p : INVITE) {
            Matcher m = p.matcher(t);
            // „Spiel mit mir“ ist ein Start, keine Einladung an einen Spieler namens „mir“.
            if (m.matches() && !PRONOUNS.contains(m.group(1).toLowerCase(Locale.ROOT))) {
                return java.util.Optional.of(m.group(1));
            }
        }
        return java.util.Optional.empty();
    }

    private static final java.util.Set<String> PRONOUNS = java.util.Set.of("mir", "dir", "uns", "euch", "ihm", "ihr",
            "ihnen", "jemandem", "allen", "freunden");

    // ---------------------------------------------------------------- Hilfe (HelpService)

    private static final Pattern HELP = Pattern.compile(
            "(?iuU)^(?:hilfe|anleitung|help)$|\\bwas\\s+kann\\s+(?:ich|man)\\s+(?:hier\\s+)?(?:alles\\s+)?"
                    + "(?:machen|tun)\\b|\\bwie\\s+(?:funktioniert|geht)\\s+(?:das\\s+hier|der\\s+server|alles)\\b|"
                    + "\\b(?:ich\\s+bin\\s+neu|zeig\\s+mir\\s+die\\s+hilfe|ich\\s+brauche\\s+hilfe)\\b");

    /** „Was kann ich hier machen?“, „Hilfe“, „ich bin neu“ – öffnet das Hilfebuch. */
    public static boolean askHelp(String text) {
        return text != null && HELP.matcher(plain(text)).find() && !askQuests(text);
    }

    // ---------------------------------------------------------------- Tagesaufgaben (QuestService)

    private static final Pattern QUESTS = Pattern.compile(
            "(?iuU)\\b((meine|die|heutigen|heutige|unsere)\\s+)?(tages)?aufgaben\\b|\\bwas\\s+(soll|kann)\\s+ich\\s+"
                    + "heute\\s+(machen|tun)\\b|\\bwas\\s+gibt\\s+es\\s+heute\\s+zu\\s+tun\\b");
    private static final Pattern QUEST_RANKING = Pattern.compile(
            "(?iuU)\\b(wer\\s+hat\\s+die\\s+meisten\\s+punkte|punkte\\s*rangliste|aufgaben\\s*rangliste|"
                    + "rangliste\\s+der\\s+aufgaben|wie\\s+viele\\s+punkte\\s+habe?\\s+ich)\\b");

    /** „Was sind meine Aufgaben?“, „Was soll ich heute machen?“ */
    public static boolean askQuests(String text) {
        return text != null && QUESTS.matcher(text).find() && !askQuestRanking(text);
    }

    /** „Wer hat die meisten Punkte?“ – Rangliste der Tagesaufgaben (nicht die des Abenteuers). */
    public static boolean askQuestRanking(String text) {
        return text != null && QUEST_RANKING.matcher(text).find();
    }

    /** Steuerwort ohne LLM, sonst null. Nur kurze Zeilen – „komm, wir bauen eine Brücke“ ist ein Auftrag. */
    public static Command command(String text) {
        if (text == null) {
            return null;
        }
        String t = text.toLowerCase(Locale.ROOT).replaceAll("[!.?,]", "").trim();
        return switch (t) {
            case "komm", "komm her", "komm zu mir", "hierher", "hier her" -> Command.COME;
            case "bleib", "bleib hier", "bleib da", "warte", "warte hier", "halt", "stopp", "stop" -> Command.STAY;
            case "folge mir", "folg mir", "mitkommen", "komm mit" -> Command.FOLLOW;
            case "tschüss", "tschüs", "geh", "geh weg", "verschwinde", "bis später", "bye", "auf wiedersehen" ->
                Command.DISMISS;
            case "rückgängig", "mach das rückgängig", "mach es rückgängig", "mach rückgängig", "rückgängig machen",
                    "das rückgängig machen", "mach das wieder rückgängig", "undo" -> Command.UNDO;
            default -> null;
        };
    }
}
