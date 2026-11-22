package com.blackn0va.baumeister.town;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Zuruf an einen Bewohner (Vertrag v013, AK-42): „Lotte, komm mit“, „Lotte, bleib hier“, „Lotte, geh nach Hause“.
 * Gelesen wird nur der Rest der Zeile nach dem Namen (TownTalk.withoutName); erkannt wird nur, was ganz eindeutig ist.
 *
 * Warum feste Wörter statt KI: Folgen ist eine Handlung, kein Gespräch. Die KI darf bei Bewohnern keine Aktion
 * auslösen (TownTalk verwirft jede), und „komm mit“ muss auch ohne KI (stadtleben.ohne-ki) und bei vollem Budget
 * wirken. Eine Frage („kommst du mit zum Markt?“) ist kein Befehl – sie bleibt beim Gespräch.
 *
 * Threads: zustandslos. Fehler: keine (null und Leeres ergeben NONE).
 */
public final class Request {

    public enum Kind {
        /** Kein Zuruf – die Zeile geht ans Gespräch. */
        NONE,
        /** Dem Spieler folgen. */
        FOLLOW,
        /** Stehen bleiben, wo er gerade ist (beendet auch das Folgen). */
        STAY,
        /** Nach Hause gehen (beendet auch das Folgen). */
        HOME
    }

    /**
     * Anfang der Zeile, auf den es ankommt. Ganze Wörter, damit „komm mittag“ oder „folgende Frage“ nichts auslösen.
     * Reihenfolge zählt: „geh nach hause“ vor „geh mit“.
     */
    private static final List<Object[]> WORDS = List.of(
            new Object[] {Kind.HOME, "geh(e)? (nach hause|heim)|ab nach hause|feierabend|husch nach hause"},
            new Object[] {Kind.STAY, "bleib(e)?( (hier|da|stehen|dort))?|warte( (hier|kurz|mal))?|stopp|stop|halt"
                    + "|hör auf|hoer auf|lass mich( in ruhe)?|nicht mehr folgen|du kannst gehen"}, // umlaut-ok
            new Object[] {Kind.FOLLOW, "komm( (mal )?mit( mir)?)?|folg(e)?( mir)?|begleite mich|lauf mit( mir)?"
                    + "|geh mit( mir)?|mir nach|hinterher"});

    private static final Pattern[] PATTERNS = new Pattern[WORDS.size()];

    static {
        for (int i = 0; i < WORDS.size(); i++) {
            PATTERNS[i] = Pattern.compile("^(bitte )?(" + WORDS.get(i)[1] + ")( bitte)?$", Pattern.UNICODE_CASE);
        }
    }

    private Request() {
    }

    /** Was der Rest der Zeile verlangt. Fragezeichen am Ende = Frage, kein Befehl (NONE). */
    public static Kind parse(String rest) {
        if (rest == null) {
            return Kind.NONE;
        }
        String s = rest.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.endsWith("?")) {
            return Kind.NONE;
        }
        // Satzzeichen und doppelte Leerzeichen weg: „Komm mit!“, „bleib hier.“ und „komm, mit“ gelten gleich.
        s = s.replaceAll("[!.,;:…]+", " ").replaceAll("\\s+", " ").trim();
        for (int i = 0; i < PATTERNS.length; i++) {
            if (PATTERNS[i].matcher(s).matches()) {
                return (Kind) WORDS.get(i)[0];
            }
        }
        return Kind.NONE;
    }

    /** Fester Satz, mit dem ein Bewohner den Zuruf bestätigt. */
    public static String reply(Kind k, int pick) {
        String[] lines = switch (k) {
            case FOLLOW -> new String[] {"Gern, ich komme mit!", "Geh vor, ich folge dir.", "Na klar, wohin geht's?"};
            case STAY -> new String[] {"Gut, ich bleibe hier.", "Ich warte hier.", "In Ordnung, ich bleibe."};
            case HOME -> new String[] {"Dann gehe ich nach Hause.", "Feierabend – bis später!", "Ich mach mich heim."};
            case NONE -> new String[] {""};
        };
        return lines[Math.floorMod(pick, lines.length)];
    }
}
