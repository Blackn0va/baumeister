package com.blackn0va.baumeister.town;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Feste Sätze der Bewohner: Gruß im Vorbeigehen (ohne KI, AK-23) und Rückfall, wenn das KI-Budget nein sagt, der Host
 * schweigt, die Antwort durch den Filter fällt (AK-24) oder das Stadtleben ohne KI läuft. Grüßen läuft bewusst nie über
 * die KI – es kommt oft und soll das Budget nicht aufbrauchen, das für echte Gespräche da ist.
 *
 * Vorher (v008) waren es 16 Floskeln plus ein Arbeitsspruch je Beruf (28 Sätze), zufällig gezogen – ohne KI sagte
 * Lotte binnen Minuten zweimal „Moin!“ und klang wie Emil (Nutzer 2026-11-22: „eher dumm“). Jetzt (v013, AK-41):
 * - Sätze je Anlass (Occasion), Tageszeit, Wetter, Beruf und Charakter; candidates() stellt daraus den Vorrat für
 *   genau diese Lage zusammen. Ein Regen-Satz kommt nur bei Regen, ein Morgengruß nur morgens.
 * - pick() sperrt die zuletzt gesagten BLOCK Sätze stadtweit; ist alles gesperrt, kommt der am längsten nicht gesagte.
 * - Jeder Satz läuft im Test durch TownTalk.blocked (TownLinesTest) – kein Horror, kein Schimpfen, auch nicht hier.
 *
 * Threads: Vorräte unveränderlich; die Sperre (Instanz) nur im Hauptthread. Fehler: keine.
 */
public final class TownLines {

    /** Wozu ein Satz gesagt wird. */
    public enum Occasion {
        /** Spieler kommt vorbei oder sagt nur „Hallo“. */
        GREET,
        /** Antwort auf eine Frage oder Bemerkung eines Spielers. */
        REPLY,
        /** Spieler verabschiedet sich. */
        FAREWELL,
        /** Bemerkung über die eigene Arbeit. */
        WORK,
        /** Ein Bewohner spricht einen anderen an. */
        MEET,
        /** Der andere antwortet. */
        MEET_REPLY
    }

    /** So viele zuletzt gesagte Sätze sind gesperrt (stadtweit). */
    static final int BLOCK = 48;

    // ---------------------------------------------------------------- Vorräte

    static final Map<Occasion, List<String>> BASE = new EnumMap<>(Map.of(
            Occasion.GREET, List.of("Hallo!", "Guten Tag!", "Na, alles gut?", "Servus!", "Moin!", "Grüß dich!",
                    "Hallo, schön dich zu sehen!", "Ah, Besuch! Hallo!", "Na, wie läuft's bei dir?",
                    "Hallo! Du bist ja auch wieder unterwegs.", "Hallo, Nachbar!", "Tag auch!"),
            Occasion.REPLY, List.of("Da hab ich gerade keine Antwort – frag mich nachher noch mal.",
                    "Hm, gute Frage. Ich muss weiter, bis später!", "Ach, ich bin gerade in Gedanken. Später gern!",
                    "Das erzähl ich dir ein andermal.", "Ich hab gerade alle Hände voll zu tun!",
                    "Gute Frage – darüber denk ich heute Abend nach.", "Da fragst du am besten im Rathaus nach.",
                    "Puh, das weiß ich gerade nicht genau.", "Klingt spannend! Erzähl mir nachher mehr davon.",
                    "Da hast du recht, glaube ich.", "Ach, so ist das Leben in der Stadt.",
                    "Das hör ich heute schon zum zweiten Mal!", "Frag mich das morgen früh noch mal, ja?",
                    "Hm, da bin ich überfragt.", "Das klingt nach einem guten Plan!",
                    "Ich behalte das im Hinterkopf."),
            Occasion.FAREWELL, List.of("Tschüss, bis bald!", "Mach's gut!", "Bis später!", "Komm gut heim!",
                    "Pass auf dich auf!", "Bis zum nächsten Mal!", "War schön, dich zu sehen!", "Ade!",
                    "Bis dann, und viel Glück!", "Grüß mir die anderen!", "Wir sehen uns!", "Gute Reise!"),
            Occasion.WORK, List.of("Die Arbeit macht sich nicht von allein.", "Noch ein bisschen, dann ist Pause.",
                    "Schritt für Schritt, so wird das was.", "Heute läuft es richtig gut.",
                    "Ich bin fast fertig für heute.", "Einer muss es ja machen!"),
            Occasion.MEET, List.of("Na, auch unterwegs?", "Wir sehen uns später!", "Viel zu tun heute?",
                    "Grüß mir die Nachbarn!", "Hast du schon das Neueste gehört?", "Wie geht's dir heute?",
                    "Gut, dass ich dich treffe!", "Lange nicht gesehen!", "Was gibt's Neues bei dir?",
                    "Hast du kurz Zeit?", "Wohin des Weges?", "Schön, ein bekanntes Gesicht!",
                    "Kommst du nachher auf den Platz?", "Du siehst gut gelaunt aus!"),
            Occasion.MEET_REPLY, List.of("Ja, ganz gut, danke!", "Viel zu tun, wie immer.", "Später gern!",
                    "Ach, du kennst mich doch.", "Erzähl!", "Bei mir läuft alles bestens.", "Dir auch einen guten Tag!",
                    "Mal sehen, was der Tag noch bringt.", "Ich muss gleich weiter, aber schön dich zu sehen.",
                    "Na klar, bis nachher!", "Das klingt gut!", "Ach was, wirklich?", "Geht so, und bei dir?",
                    "Danke, gleichfalls!")));

    static final Map<Occasion, Map<DayPlan.Phase, List<String>>> BY_PHASE = new EnumMap<>(Map.of(
            Occasion.GREET, phases(
                    List.of("Guten Morgen!", "Morgen! Schon wach?", "Guten Morgen, gut geschlafen?",
                            "Früh unterwegs heute!"),
                    List.of("Schönen Vormittag!", "Tag! Fleißig unterwegs?", "Hallo, schon viel geschafft heute?",
                            "Na, mitten im Tag unterwegs?"),
                    List.of("Mahlzeit!", "Mittagspause – herrlich, oder?", "Hallo! Schon gegessen?",
                            "Guten Appetit, falls du gerade isst!"),
                    List.of("Schönen Nachmittag!", "Hallo! Der Tag ist ja schon halb rum.",
                            "Na, noch Kraft für den Nachmittag?", "Hallo, Zeit für ein Päuschen?"),
                    List.of("Guten Abend!", "Na, auch Feierabend?", "Abend! War ein langer Tag.",
                            "Schönen Feierabend!"),
                    List.of("Guten Abend, noch so spät unterwegs?", "Gute Nacht bald!", "Es wird dunkel, pass auf!",
                            "Spät ist es geworden.")),
            Occasion.FAREWELL, phases(
                    List.of("Einen guten Start in den Tag!", "Hab einen schönen Morgen!", "Bis heute Mittag!"),
                    List.of("Schönen Tag noch!", "Viel Erfolg heute!", "Bis zur Mittagspause!"),
                    List.of("Lass es dir schmecken!", "Genieß die Pause!", "Bis nach dem Essen!"),
                    List.of("Schönen Nachmittag noch!", "Halt durch bis zum Feierabend!", "Bis heute Abend!"),
                    List.of("Schönen Feierabend!", "Erhol dich gut!", "Bis morgen!"),
                    List.of("Gute Nacht!", "Schlaf gut!", "Träum was Schönes!")),
            Occasion.MEET, phases(
                    List.of("Morgen! Auch auf dem Weg zur Arbeit?", "Guten Morgen, ausgeschlafen?",
                            "Früh dran heute, was?"),
                    List.of("Na, viel los bei der Arbeit?", "Schon die Hälfte geschafft?",
                            "Bald ist Mittag, oder?"),
                    List.of("Kommst du mit auf den Platz?", "Mittagspause! Was isst du heute?",
                            "Endlich Pause, was?"),
                    List.of("Noch ein paar Stunden, dann ist Feierabend.", "Wie lief dein Vormittag?",
                            "Der Nachmittag zieht sich heute."),
                    List.of("Feierabend! Gehst du auch heim?", "War das ein Tag, oder?",
                            "Bis morgen dann!"),
                    List.of("Noch wach?", "Höchste Zeit fürs Bett, oder?", "Schlaf gut nachher!"))));

    static final Map<TownTalk.Weather, List<String>> WEATHER = new EnumMap<>(Map.of(
            TownTalk.Weather.CLEAR, List.of("Schönes Wetter heute, oder?", "Herrlich, die Sonne scheint!",
                    "Was für ein klarer Himmel!", "Bei dem Wetter arbeitet es sich gut.",
                    "Ein schöner Tag zum Draußensein.", "Kein Wölkchen am Himmel!", "So ein Wetter mag ich.",
                    "Die Luft ist heute richtig frisch."),
            TownTalk.Weather.RAIN, List.of("Ganz schön nass heute!", "Der Regen tut den Feldern gut.",
                    "Hast du keinen Schirm dabei?", "Bei dem Regen bleib ich lieber unterm Dach.",
                    "Hoffentlich hört es bald auf zu regnen.", "Pfützen überall!", "Regenwetter – Zeit für Tee.",
                    "Na, auch nass geworden?"),
            TownTalk.Weather.THUNDER, List.of("Hörst du das Donnern?", "Bei Gewitter bleib ich lieber drinnen.",
                    "Das war ein lauter Donner!", "Schnell rein, bevor es richtig losgeht!",
                    "Was für ein Wetter heute!", "Bleib nicht unter hohen Bäumen stehen!",
                    "Das Gewitter zieht hoffentlich bald weiter.", "Da blitzt es wieder!")));

    /** Arbeitssätze je Beruf (Roster.Person.job); dazu kommt immer Person.workLine. */
    static final Map<String, List<String>> WORK_BY_JOB = Map.ofEntries(
            Map.entry("Bäckerin", List.of("Der Teig muss noch gehen.", "Gleich kommen die Brezeln aus dem Ofen.",
                    "Riechst du das? Frisches Brot!", "Heute gibt es Apfelkuchen.", "Mehl hab ich überall, haha.",
                    "Die Brötchen sind schon fast weg!")),
            Map.entry("Schmied", List.of("Das Eisen muss heiß sein.", "Noch ein paar Schläge auf den Amboss.",
                    "Die Esse brennt gut heute.", "Ein gutes Werkzeug hält ein Leben lang.",
                    "Diese Axt schleif ich noch.", "Hufeisen, Nägel, Beschläge – alles meins.")),
            Map.entry("Gärtnerin", List.of("Die Tulpen kommen bald raus.", "Unkraut wächst immer am schnellsten.",
                    "Ich muss noch die Beete gießen.", "Die Bienen sind heute fleißig.",
                    "Ein Garten braucht Geduld.", "Die Kürbisse werden groß dieses Jahr.")),
            Map.entry("Fischer", List.of("Das Wasser ist heute ganz ruhig.", "Gestern hab ich einen Riesenlachs gesehen.",
                    "Ein guter Köder ist das halbe Glück.", "Das Netz muss ich noch flicken.",
                    "Am Steg ist es am schönsten.", "Manchmal beißt stundenlang nichts – auch schön.")),
            Map.entry("Händlerin", List.of("Smaragde gegen Ware, wie immer fair!", "Neue Stoffe sind gekommen.",
                    "Ich hab heute ein Schnäppchen für dich.", "Der Markt ist heute gut besucht.",
                    "Handeln gehört dazu!", "Meine Waage lügt nie.")),
            Map.entry("Holzfäller", List.of("Eiche oder Birke, das ist hier die Frage.", "Ich pflanze für jeden Baum "
                    + "einen neuen.", "Das Holz muss trocknen.", "Noch ein Stamm, dann Pause.",
                    "Im Wald ist es schön still.", "Ein Stapel Holz für den Winter.")),
            Map.entry("Bibliothekarin", List.of("Ein neues Buch ist eingetroffen.", "Die Regale ordne ich nach "
                    + "Farben.", "Wer liest, reist ohne zu laufen.", "Ich empfehle dir eine Abenteuergeschichte.",
                    "Bitte die Bücher pünktlich zurückbringen.", "Heute sortiere ich die Karten.")),
            Map.entry("Bauer", List.of("Die Kühe sind heute gut drauf.", "Die Kartoffeln müssen raus.",
                    "Regen wäre gut für das Feld.", "Die Hühner haben fleißig gelegt.",
                    "Heute wird gesät.", "Ein Feld ist nie fertig.")),
            Map.entry("Malerin", List.of("Der Himmel hat heute so ein schönes Licht.", "Ich male den Brunnen.",
                    "Mir fehlt noch ein bisschen Gelb.", "Farbe ist Freude!", "Halt still, ich male dich gleich!",
                    "Jedes Haus hier hat seine eigene Farbe.")),
            Map.entry("Postbote", List.of("Drei Briefe noch, dann bin ich durch.", "Ein Paket für den Schmied!",
                    "Die Post muss pünktlich sein.", "Ich kenne jede Haustür hier.",
                    "Heute ist die Tasche schwer.", "Hast du was zu verschicken?")),
            Map.entry("Ärztin", List.of("Viel trinken, das hilft fast immer.", "Ich schau später noch bei Karl "
                    + "vorbei.", "Kräutertee wirkt Wunder.", "Pass auf deine Knie auf beim Klettern!",
                    "Genug Schlaf ist die beste Medizin.", "Heute war die Praxis ruhig.")),
            Map.entry("Musiker", List.of("Ich übe ein neues Lied.", "Hörst du die Melodie?",
                    "Heute Abend spiele ich auf dem Platz.", "Ein Lied geht immer.", "Mein Notenblock klingt heute "
                    + "besonders schön.", "Taktgefühl ist alles!")));

    /** Antworten, die nach dem Beruf klingen (Rückfall auf eine Frage). */
    static final Map<String, List<String>> REPLY_BY_JOB = Map.ofEntries(
            Map.entry("Bäckerin", List.of("Komm später in die Backstube, dann reden wir.",
                    "Darüber reden wir bei einem Stück Kuchen.", "Erst mal ein Brötchen, dann sehen wir weiter.",
                    "Weißt du, beim Backen fällt mir sowas oft ein.")),
            Map.entry("Schmied", List.of("Hm. Frag mich, wenn das Eisen kalt ist.", "Ich bin Schmied, kein Weiser.",
                    "Darüber denk ich beim Hämmern nach.", "Komm später in die Schmiede.")),
            Map.entry("Gärtnerin", List.of("Lass dir Zeit, wie die Pflanzen.", "Das wächst schon noch.",
                    "Darüber denk ich beim Gießen nach.", "Komm doch mal in den Garten.")),
            Map.entry("Fischer", List.of("Das erinnert mich an eine Geschichte vom See …",
                    "Darüber grüble ich am Steg.", "Wie beim Angeln: abwarten.", "Frag mich, wenn was gebissen hat.")),
            Map.entry("Händlerin", List.of("Oh, erzähl mir mehr, ich will alles wissen!",
                    "Das frag ich gleich die anderen am Markt.", "Neuigkeiten sind mir mehr wert als Smaragde.",
                    "Davon hab ich auch schon gehört!")),
            Map.entry("Holzfäller", List.of("Hm.", "Kann sein.", "Frag wen anders.", "Mhm, später.")),
            Map.entry("Bibliothekarin", List.of("Dazu gibt es sicher ein Buch.", "Eine berechtigte Frage, wahrlich.",
                    "Ich schlage das gern für Sie nach.", "Darauf antworte ich mit Bedacht morgen.")),
            Map.entry("Bauer", List.of("Wie mein Opa sagte: Alles hat seine Zeit.", "Erst die Arbeit, dann das "
                    + "Plaudern.", "Gemach, gemach.", "Komm mal aufs Feld, da reden wir.")),
            Map.entry("Malerin", List.of("Das stell ich mir in Lila vor!", "Darüber male ich ein Bild!",
                    "Ooh, das ist eine bunte Frage.", "Stell dir das mal als Gemälde vor.")),
            Map.entry("Postbote", List.of("Sorry, ich muss weiter, die Post wartet!",
                    "Ich bring dir die Antwort mit der nächsten Post.", "Schnell gefragt, schnell vergessen – sorry!",
                    "Erzähl's mir auf meiner nächsten Runde.")),
            Map.entry("Ärztin", List.of("Wichtig ist, dass es dir gut geht.", "Darüber sprechen wir in Ruhe.",
                    "Sachlich betrachtet: Ich weiß es nicht.", "Bleib gesund, dann klären wir das.")),
            Map.entry("Musiker", List.of("Darauf schreib ich ein Lied!", "Das reimt sich auf … hmm, gleich hab ich's!",
                    "Gute Frage, schlechter Reim.", "Ich sag's dir in Moll.")));

    /** Gruß je Charakterzug (Schlüssel im Charakter-Text, siehe trait). */
    static final Map<String, List<String>> TONE_GREET = Map.ofEntries(
            Map.entry("fröhlich", List.of("Hallöchen, wie schön!", "Juhu, Besuch!", "Hallo, du Sonnenschein!")),
            Map.entry("brummig", List.of("Hm. Tag.", "Na.", "Tag auch.")),
            Map.entry("ruhig", List.of("Schön, dass du da bist.", "Hallo, ganz in Ruhe.", "Willkommen.")),
            Map.entry("verträumt", List.of("Oh, hallo … ich war ganz woanders.", "Ach, du bist es!",
                    "Hallo, schön wie ein Sonnenaufgang.")),
            Map.entry("gesprächig", List.of("Hallo! Gibt's was Neues?", "Ah, genau dich wollte ich sprechen!",
                    "Hallo, erzähl, wie geht's?")),
            Map.entry("wortkarg", List.of("Tag.", "Mhm, hallo.", "Moin.")),
            Map.entry("förmlich", List.of("Guten Tag, werte Person.", "Seien Sie gegrüßt.",
                    "Einen angenehmen Tag wünsche ich.")),
            Map.entry("gemütlich", List.of("Na, immer mit der Ruhe, hallo!", "Hallo, setz dich doch mal hin.",
                    "Gemütlich unterwegs, was?")),
            Map.entry("verspielt", List.of("Hallo! Rate mal, was ich heute male!", "Huhu!",
                    "Kuckuck, da bist du ja!")),
            Map.entry("eilig", List.of("Hallo, kann nicht bleiben!", "Tag! Schnell, schnell!",
                    "Grüß dich – und weiter!")),
            Map.entry("fürsorglich", List.of("Hallo, geht's dir gut?", "Schön, dich gesund zu sehen.",
                    "Hallo, hast du genug getrunken heute?")),
            Map.entry("witzig", List.of("Hallo! Kennst du den mit dem Creeper?", "Na, du Held des Tages!",
                    "Tag! Lächeln nicht vergessen!")));

    /** Antwort je Charakterzug. */
    static final Map<String, List<String>> TONE_REPLY = Map.ofEntries(
            Map.entry("fröhlich", List.of("Ach, wie schön, dass du fragst!", "Das macht mich ganz froh!",
                    "Hihi, das ist eine nette Frage.")),
            Map.entry("brummig", List.of("Pff, frag nicht so viel.", "Wenn's sein muss … später.",
                    "Hm, meinetwegen.")),
            Map.entry("ruhig", List.of("Darüber lass ich mir Zeit.", "Alles zu seiner Zeit.",
                    "Eins nach dem anderen.")),
            Map.entry("verträumt", List.of("Hm? Oh, ich hab geträumt …", "Das klingt wie aus einem Märchen.",
                    "Ich denk dabei an Wolken.")),
            Map.entry("gesprächig", List.of("Oh, da könnte ich stundenlang erzählen!", "Und was meinst du dazu?",
                    "Wer hat dir das erzählt?")),
            Map.entry("wortkarg", List.of("Joa.", "Vielleicht.", "Mhm.")),
            Map.entry("förmlich", List.of("Eine ausgezeichnete Frage.", "Ich werde darüber nachdenken.",
                    "Gestatten Sie mir Bedenkzeit.")),
            Map.entry("gemütlich", List.of("Nur keine Hektik.", "Das hat Zeit bis nach dem Essen.",
                    "Ach, das wird schon.")),
            Map.entry("verspielt", List.of("Rate mal!", "Das verrat ich dir, wenn du mich fängst!",
                    "Ich sag's dir in Farben!")),
            Map.entry("eilig", List.of("Keine Zeit, sorry!", "Kurz gesagt: später!", "Muss los, frag mich nachher!")),
            Map.entry("fürsorglich", List.of("Mach dir keine Sorgen.", "Ich kümmere mich drum.",
                    "Hauptsache, dir geht's gut.")),
            Map.entry("witzig", List.of("Das frag ich mich auch jeden Morgen!", "Gute Frage – nächste Frage!",
                    "Ha, du bist ja lustig!")));

    /** Verabschiedung erkennen: dann FAREWELL statt REPLY. */
    private static final Pattern BYE = Pattern.compile("(?iuU)\\b(tschüss|tschau|ciao|bis bald|bis später|"
            + "bis dann|bis morgen|gute nacht|mach's gut|machs gut|auf wiedersehen|ade|bye)\\b");
    /** Nur ein Gruß ohne Frage („Hallo Lotte!“): dann GREET. */
    private static final Pattern HELLO = Pattern.compile("(?iu)^\\s*(hallo|hi|hey|moin|servus|guten (morgen|tag|"
            + "abend)|grüß dich|huhu|na)\\s*[!.,]*\\s*$");

    // ---------------------------------------------------------------- Sperre

    private final Deque<String> recent = new ArrayDeque<>();

    /**
     * Ein Satz für diese Lage, nicht unter den letzten BLOCK gesagten. pick wählt unter den freien (Zufall vom
     * Aufrufer, damit Tests feste Werte nehmen können); ist keiner frei, den am längsten nicht gesagten.
     */
    public String pick(Occasion o, Roster.Person p, DayPlan.Phase phase, TownTalk.Weather weather, int roll) {
        List<String> all = candidates(o, p, phase, weather);
        List<String> free = new ArrayList<>();
        for (String s : all) {
            if (!recent.contains(s)) {
                free.add(s);
            }
        }
        String s = free.isEmpty() ? oldest(all) : free.get(Math.floorMod(roll, free.size()));
        said(s);
        return s;
    }

    /** Auch KI-Sätze sperren, damit der feste Satz danach nicht wiederholt, was eben gesagt wurde. */
    public void said(String s) {
        recent.remove(s);
        recent.addLast(s);
        while (recent.size() > BLOCK) {
            recent.removeFirst();
        }
    }

    private String oldest(List<String> all) {
        for (Iterator<String> it = recent.iterator(); it.hasNext();) {
            String s = it.next();
            if (all.contains(s)) {
                return s;
            }
        }
        return all.getFirst();
    }

    // ---------------------------------------------------------------- Zusammenstellung

    /** Der Vorrat für Anlass, Beruf, Charakter, Tageszeit und Wetter (ohne Sperre), ohne Doppelte. */
    static List<String> candidates(Occasion o, Roster.Person p, DayPlan.Phase phase, TownTalk.Weather weather) {
        Set<String> out = new LinkedHashSet<>(BASE.get(o));
        String trait = trait(p.character());
        boolean working = phase.goal() == DayPlan.Goal.WORK;
        // Sonne nur tagsüber: „Die Sonne scheint!“ am Abend wäre so falsch wie ein Regen-Satz bei Sonne.
        boolean showWeather = weather != TownTalk.Weather.CLEAR || phase != DayPlan.Phase.NIGHT
                && phase != DayPlan.Phase.EVENING;
        switch (o) {
            case GREET -> {
                out.addAll(BY_PHASE.get(o).get(phase));
                if (showWeather) {
                    out.addAll(WEATHER.get(weather));
                }
                out.addAll(TONE_GREET.getOrDefault(trait, List.of()));
            }
            case REPLY -> {
                out.addAll(REPLY_BY_JOB.getOrDefault(p.job(), List.of()));
                out.addAll(TONE_REPLY.getOrDefault(trait, List.of()));
                if (working) {
                    out.addAll(WORK_BY_JOB.getOrDefault(p.job(), List.of()));
                    out.add(p.workLine());
                }
            }
            case FAREWELL -> out.addAll(BY_PHASE.get(o).get(phase));
            case WORK -> {
                out.addAll(WORK_BY_JOB.getOrDefault(p.job(), List.of()));
                out.add(p.workLine());
            }
            case MEET -> {
                out.addAll(BY_PHASE.get(o).get(phase));
                if (showWeather) {
                    out.addAll(WEATHER.get(weather));
                }
                if (working) {
                    out.addAll(WORK_BY_JOB.getOrDefault(p.job(), List.of()));
                }
            }
            case MEET_REPLY -> {
                out.addAll(TONE_REPLY.getOrDefault(trait, List.of()));
                if (showWeather) {
                    out.addAll(WEATHER.get(weather));
                }
            }
        }
        return List.copyOf(out);
    }

    /** Anlass einer Spielerzeile: Abschied, bloßer Gruß oder sonst Antwort. */
    public static Occasion occasionFor(String heard) {
        if (heard == null || heard.isBlank()) {
            return Occasion.GREET;
        }
        if (BYE.matcher(heard).find()) {
            return Occasion.FAREWELL;
        }
        return HELLO.matcher(heard).matches() ? Occasion.GREET : Occasion.REPLY;
    }

    /** Erster Charakterzug aus TONE_GREET, der im Charakter-Text steht; leer = keiner. */
    static String trait(String character) {
        String c = character == null ? "" : character.toLowerCase(Locale.ROOT);
        for (String k : TONE_GREET.keySet().stream().sorted().toList()) {
            if (c.contains(k)) {
                return k;
            }
        }
        return "";
    }

    /** Alle festen Sätze ohne Doppelte (Zählung im Test und für den CHANGELOG). */
    static Set<String> all() {
        Set<String> out = new LinkedHashSet<>();
        BASE.values().forEach(out::addAll);
        BY_PHASE.values().forEach(m -> m.values().forEach(out::addAll));
        WEATHER.values().forEach(out::addAll);
        WORK_BY_JOB.values().forEach(out::addAll);
        REPLY_BY_JOB.values().forEach(out::addAll);
        TONE_GREET.values().forEach(out::addAll);
        TONE_REPLY.values().forEach(out::addAll);
        Roster.all().forEach(p -> out.add(p.workLine()));
        return out;
    }

    private static Map<DayPlan.Phase, List<String>> phases(List<String> morning, List<String> work,
            List<String> noon, List<String> afternoon, List<String> evening, List<String> night) {
        Map<DayPlan.Phase, List<String>> m = new EnumMap<>(DayPlan.Phase.class);
        m.put(DayPlan.Phase.MORNING, morning);
        m.put(DayPlan.Phase.WORK, work);
        m.put(DayPlan.Phase.NOON, noon);
        m.put(DayPlan.Phase.AFTERNOON, afternoon);
        m.put(DayPlan.Phase.EVENING, evening);
        m.put(DayPlan.Phase.NIGHT, night);
        return m;
    }
}
