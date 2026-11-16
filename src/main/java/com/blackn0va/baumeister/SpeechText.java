package com.blackn0va.baumeister;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text fürs Ohr: was Piper (espeak-ng, deutsch) sonst falsch liest. Java-Fassung von host/src/voice/speech_text.cpp –
 * dort am 2026-11-04 mit probes/piper_aussprache_probe.py an Thorsten gemessen. Seit Vera auf dem Server spricht
 * (LocalVoice, 2026-11-08) klang ohne diese Regeln manches „komisch“: „Creeper“ als „Kreeper“, „ca.“ mit Satzpause,
 * „16x“ als „sechzehn iks“, „×“, „→“ und „·“ als Zeichennamen oder Stolperer.
 *
 * Wer eine Umschreibung ändert, misst sie vorher (Probe im Host); gemessene Fehlgriffe, die NICHT zurück dürfen:
 * „S-pohn“ (liest den Buchstaben S), „Redstoun“ (ou → uː), „Kri-eitiv“. Die Tabelle muss zum Host passen.
 *
 * Threads: zustandslos. Fehler: keine.
 */
final class SpeechText {

    /** capital: nur mit großem Anfangsbuchstaben (Substantiv) – „Lag“ ja, das deutsche „lag“ nicht. */
    private record Spelling(String key, String say, boolean prefix, boolean capital) {
        Spelling(String key, String say, boolean prefix) {
            this(key, say, prefix, false);
        }
    }

    /** Wie kSpellings im Host; Reihenfolge zählt (netherite vor nether). */
    private static final List<Spelling> SPELLINGS = List.of(
            new Spelling("creeper", "Krieper", true), new Spelling("redstone", "Rädstohn", true),
            new Spelling("netherite", "Nethereit", true), new Spelling("enderman", "Endermän", true),
            new Spelling("endermen", "Endermän", true), new Spelling("zombie", "Zombi", true),
            new Spelling("skeleton", "Skelett", true), new Spelling("respawn", "Rispohn", true),
            new Spelling("spawn", "Spohn", true), new Spelling("cobblestone", "Kobbelstohn", true),
            new Spelling("minecraft", "Mainkraft", true), new Spelling("villager", "Willätscher", true),
            new Spelling("wither", "Wisser", true), new Spelling("slime", "Slaim", true),
            new Spelling("blaze", "Bleihs", true), new Spelling("beacon", "Bihken", true),
            new Spelling("chunk", "Tschank", true), new Spelling("survival", "Sörweiwel", true),
            new Spelling("creative", "Kriehtiv", true), new Spelling("gamemode", "Spielmodus", false),
            new Spelling("pickaxe", "Spitzhacke", false), new Spelling("diamond", "Diamant", false),
            new Spelling("warden", "Wohrden", false), new Spelling("dungeon", "Dandschen", true),
            new Spelling("biome", "Biom", false), new Spelling("plot", "Plott", false),
            new Spelling("home", "Hohm", false), new Spelling("sword", "Schwert", false),
            new Spelling("shield", "Schild", false), new Spelling("torch", "Fackel", false),
            new Spelling("iron", "Eisen", false), new Spelling("ore", "Erz", false),
            new Spelling("nether", "Näther", true), new Spelling("hardcore", "Haadkohr", true),
            new Spelling("enderauge", "Ender-Auge", true));

    /**
     * Gemessen 2026-11-15 auf dev01 mit aussprache_dev01.py (piper --debug, espeak-ng deutsch): „Mobs“ wurde „moːps“
     * (langes o), „Lag“ wie das deutsche „lag“ (lɑːk). Im Host (speech_text.cpp) seit 2026-11-16 ebenso, dort mit dem
     * Piper der App nachgemessen. „Lag“ nur großgeschrieben: Vorher wurde auch das Verb („der Stein lag dort“) zu
     * „Läg“ (LocalVoiceTest.theGermanVerbLagStaysAndUnitsFollowDigits).
     */
    private static final List<Spelling> SERVER_SPELLINGS = List.of(
            new Spelling("mobs", "Mobbs", false), new Spelling("mob", "Mobb", false),
            new Spelling("lags", "Lägs", false, true), new Spelling("lag", "Läg", false, true));

    /**
     * Einheit hinter Schrägstrich („440 Blöcke/s“ wurde „Blöcke Es“, gemessen 2026-11-15), auch nach einer Ziffer
     * („300/s“) und „/Tag“ – wie im Host.
     */
    private static final Pattern PER_UNIT = Pattern.compile("(?<=[\\p{L}\\d])/(s|sek|min|h|std|tag|Tag)\\b");
    /** Verhältnis „1:1“, „2:3“ (keine Uhrzeit: CLOCK verlangt zweistellige Minuten). Vorher „eins eins“. */
    private static final Pattern RATIO = Pattern.compile("(?<!\\d)(\\d{1,2}):(\\d)(?!\\d)");

    /** Abkürzungen mit Punkt: Piper teilt Sätze am Punkt („ca. 20“ wurde „ca“ und Pause, gemessen im Host). */
    private static final String[][] ABBREVIATIONS = {
        {"z. B.", "zum Beispiel"}, {"z.B.", "zum Beispiel"}, {"d. h.", "das heißt"}, {"d.h.", "das heißt"},
        {"u. a.", "unter anderem"}, {"u.a.", "unter anderem"}, {"bzw.", "beziehungsweise"}, {"usw.", "und so weiter"},
        {"ca.", "circa"}, {"Nr.", "Nummer"}, {"evtl.", "eventuell"}, {"ggf.", "gegebenenfalls"},
        {"o. Ä.", "oder Ähnliches"}};

    private static final Pattern WORD = Pattern.compile("[\\p{L}]+");
    private static final Pattern CLOCK = Pattern.compile("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)( Uhr\\b)?");
    private static final Pattern TIMES = Pattern.compile("(\\d)\\s*[×xX*]\\s*(\\d)");
    private static final Pattern COUNT_X = Pattern.compile("(\\d)[xX](?=\\s|$)");
    /** Tausenderpunkt und die schmalen Leerzeichen aus String.format(Locale.GERMANY) – nie ein normales Leerzeichen. */
    private static final Pattern THOUSANDS = Pattern.compile("(\\d)[.\\u00A0\\u202F](\\d{3})(?!\\d)");
    private static final Pattern DECIMAL = Pattern.compile("(\\d)\\.(\\d)");
    private static final Pattern LEET = Pattern.compile("(?<=[A-Za-z])([013457])(?=[A-Za-z])");

    private SpeechText() {
    }

    /** Aufbereiteter Text; leer bleibt leer. */
    static String forSpeech(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String s = dropPictographs(text).replaceAll("[\\r\\n\\t]", " ");
        for (String[] a : ABBREVIATIONS) {
            s = s.replaceAll("(?<![\\p{L}])" + Pattern.quote(a[0]), Matcher.quoteReplacement(a[1]));
        }
        s = s.replaceAll("(?i)\\bcrafting[ _]table\\b", "Werkbank");
        s = clock(s);
        s = RATIO.matcher(s).replaceAll("$1 zu $2");
        s = PER_UNIT.matcher(s).replaceAll(m -> switch (m.group(1)) {
            case "s", "sek" -> " pro Sekunde";
            case "min" -> " pro Minute";
            case "h", "std" -> " pro Stunde";
            default -> " pro Tag";
        });
        s = TIMES.matcher(s).replaceAll("$1 mal $2");
        s = COUNT_X.matcher(s).replaceAll("$1");
        s = THOUSANDS.matcher(s).replaceAll("$1$2");
        s = DECIMAL.matcher(s).replaceAll("$1,$2");
        s = s.replaceAll("(\\d)\\s*%", "$1 Prozent").replaceAll("(\\d)\\s*°", "$1 Grad")
                .replaceAll("(\\d) ?km\\b", "$1 Kilometer").replaceAll("(\\d) ?m\\b", "$1 Meter")
                // „10-15“ und „10–15“ sind Bereiche, „5 - 3“ mit Leerzeichen eine Rechnung (wie im Host).
                .replaceAll("(\\d) - (\\d)", "$1 minus $2").replaceAll("(\\d)\\s*[–-]\\s*(\\d)", "$1 bis $2");
        // Satzzeichen, die espeak als Namen liest oder über die es stolpert: als Pause bzw. weg.
        // Klammern liest espeak gar nicht und macht keine Pause: „Rechnung (Quelle: Wiki) fertig“ lief in einem Zug
        // durch (gemessen 2026-11-15). Als Komma klingt es wie gesprochen.
        s = s.replace("(", ", ").replace(")", ", ").replace("&", " und ").replace("+", " plus ");
        s = s.replace("→", ", ").replace("·", ", ").replace("–", ", ").replace("—", ", ").replace("…", "...")
                .replace("×", " mal ").replace("=", " ist ").replace("_", " ").replace("„", "").replace("“", "")
                .replace("\"", "").replace("*", "").replace("#", "").replace("`", "").replace("~", "");
        s = LEET.matcher(s).replaceAll(m -> switch (m.group(1)) {
            case "0" -> "o";
            case "1" -> "i";
            case "3" -> "e";
            case "4" -> "a";
            case "5" -> "s";
            default -> "t";
        });
        s = spellings(s);
        return s.replaceAll("\\s+([,.!?])", "$1").replaceAll(",\\s*,", ",").replaceAll(",\\s*([.!?])", "$1")
                .replaceAll("^[,\\s]+", "").replaceAll("\\s+", " ").strip();
    }

    /** „6:00“ → „6 Uhr“, „14:30 Uhr“ → „14 Uhr 30“. */
    private static String clock(String s) {
        Matcher m = CLOCK.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            String r = h <= 24 && min <= 59 ? h + " Uhr" + (min == 0 ? "" : " " + min) : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String spellings(String s) {
        Matcher m = WORD.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String word = m.group();
            String low = word.toLowerCase(Locale.ROOT);
            String repl = word;
            repl = spell(word, low, SPELLINGS);
            if (repl.equals(word)) {
                repl = spell(word, low, SERVER_SPELLINGS);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(repl));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String spell(String word, String low, List<Spelling> table) {
        for (Spelling sp : table) {
            if (low.startsWith(sp.key()) && (!sp.capital() || Character.isUpperCase(word.charAt(0)))) {
                String rest = word.substring(sp.key().length());
                if (rest.isEmpty() || sp.prefix() || rest.equals("s") || rest.equals("S")) {
                    return sp.say() + rest;
                }
            }
        }
        return word;
    }

    /** Emojis, Bildsymbole, Kästchen und Pfeile fallen weg: espeak liest sonst ihren Unicode-Namen vor. */
    private static String dropPictographs(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            boolean pict = (cp >= 0x1F000 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF)
                    || (cp >= 0x2B00 && cp <= 0x2BFF) || (cp >= 0x25A0 && cp <= 0x25FF) || cp == 0xFE0F
                    || cp == 0x200D || (cp >= 0x1F1E6 && cp <= 0x1F1FF);
            if (!pict) {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }
}
