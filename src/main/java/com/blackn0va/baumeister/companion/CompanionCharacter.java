package com.blackn0va.baumeister.companion;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Veras Charakter: lieber Kumpel oder frecher Mitspieler, auf Wunsch angepasst an den Ton des Spielers.
 *
 * Wunsch 2026-11-07. Vorher war Vera immer gleich freundlich (Prompt „never rude, never mean“), und ohne
 * Modell sagte sie stets denselben Satz.
 *
 * - KUMPEL: freundlich und hilfsbereit wie bisher.
 * - FRECH: zieht den Spieler auf, motzt im Spaß, hilft trotzdem und tut, was er will.
 * - ANPASSEN (Vorgabe): frech, sobald der Spieler selbst ruppig wird (mindestens 2 der letzten 8 Sätze ruppig und
 *   mehr ruppige als freundliche), sonst Kumpel.
 * Frech nur, wenn die Fähigkeit FRECH erlaubt ist (Admin-Buch); sonst immer Kumpel.
 *
 * Grenze, auch frech: keine Beleidigung echter Eigenschaften, keine Schimpfwörter über „Mist“ hinaus, nichts
 * Sexuelles – die Sätze hier und der Prompt halten das ein. Wer FRECH-Sätze schärft, muss das weiter einhalten.
 *
 * Threads: Tone gehört einem Spieler, Zugriff aus dem Hauptthread (synchronized zur Sicherheit). Sonst zustandslos.
 * Fehler: wirft nicht.
 */
public final class CompanionCharacter {

    public enum Kind {
        KUMPEL("Kumpel"), FRECH("Frech"), ANPASSEN("Anpassen");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Kind parse(String s) {
            if (s == null) {
                return null;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "kumpel", "nett", "lieb", "freundlich" -> KUMPEL;
                case "frech", "arsch", "fies", "gemein" -> FRECH;
                case "anpassen", "auto", "angepasst" -> ANPASSEN;
                default -> null;
            };
        }
    }

    /** Wofür Vera einen Satz braucht. */
    public enum Line {
        RUDE, HEAR, HOW, THANKS, BYE, GREET, YES, NO,
        DEATH, FALL, LOW_HEALTH, DIAMOND, LEVEL, HUNGRY, NIGHT, IDLE,
        BELL, NOTE, DOOR, CANT_REACH, USED, NOT_ALLOWED, NOTHING_THERE, NEEDS_REDSTONE
    }

    private CompanionCharacter() {
    }

    // „sei frech“, „du darfst frech sein“, „sei wieder nett“, „pass dich an“, „sei ein Arsch“.
    private static final Pattern WISH_FRECH = Pattern.compile(
            "(?iuU)\\b(sei|werd|werde|darfst\\s+.*)\\s+(ruhig\\s+|mal\\s+|ein\\s+bisschen\\s+|wieder\\s+)*"
                    + "(frech|fies|gemein|ein\\s+arsch|zickig)\\b");
    private static final Pattern WISH_NICE = Pattern.compile(
            "(?iuU)\\b(sei|werd|werde)\\s+(ruhig\\s+|mal\\s+|wieder\\s+|bitte\\s+)*(nett|lieb|freundlich|normal|"
                    + "ein\\s+kumpel)\\b|\\bhör\\s+auf\\s+(so\\s+)?frech\\b");
    private static final Pattern WISH_ADAPT = Pattern.compile(
            "(?iuU)\\b(pass|passe)\\s+dich\\s+(mir\\s+)?an\\b|\\bwie\\s+ich\\s+(mit\\s+dir\\s+)?rede\\b");

    /** Wunsch des Spielers im Satz, sonst null. */
    public static Kind wish(String text) {
        if (text == null) {
            return null;
        }
        if (WISH_ADAPT.matcher(text).find()) {
            return Kind.ANPASSEN;
        }
        if (WISH_NICE.matcher(text).find()) {
            return Kind.KUMPEL;
        }
        if (WISH_FRECH.matcher(text).find()) {
            return Kind.FRECH;
        }
        return null;
    }

    private static final Pattern ROUGH = Pattern.compile(
            "(?iuU)\\b(halt\\s+die\\s+klappe|halt's\\s+maul|du\\s+nervst|nervig|blöd\\w*|dumm\\w*|idiot\\w*|depp\\w*|"
                    + "scheiß\\w*|scheiss\\w*|mist\\w*|lahm\\w*|nutzlos\\w*|bist\\s+du\\s+(dumm|blind|taub)|"
                    + "arsch\\w*|digga|alter|verpiss\\w*|hau\\s+ab|penner|loser|noob|fick\\w*|wichser|hure)\\b");
    private static final Pattern KIND_WORDS = Pattern.compile(
            "(?iuU)\\b(bitte|danke\\w*|lieb\\w*|super|toll|klasse|nett|cool|gut\\s+gemacht|hab\\s+dich\\s+lieb)\\b");

    /** Ton der letzten Sätze eines Spielers. */
    public static final class Tone {
        static final int WINDOW = 8;
        private final Deque<Integer> last = new ArrayDeque<>();

        /** +1 ruppig, −1 freundlich, 0 neutral. */
        public synchronized void heard(String text) {
            int v = text == null ? 0 : ROUGH.matcher(text).find() ? 1 : KIND_WORDS.matcher(text).find() ? -1 : 0;
            last.addLast(v);
            while (last.size() > WINDOW) {
                last.removeFirst();
            }
        }

        public synchronized boolean rough() {
            int rough = 0;
            int nice = 0;
            for (int v : last) {
                if (v > 0) {
                    rough++;
                } else if (v < 0) {
                    nice++;
                }
            }
            return rough >= 2 && rough > nice;
        }
    }

    /** Ist Vera gerade frech? */
    public static boolean cheeky(Kind kind, Tone tone, boolean allowed) {
        if (!allowed || kind == null) {
            return false;
        }
        return kind == Kind.FRECH || kind == Kind.ANPASSEN && tone != null && tone.rough();
    }

    /** Ein Satz für l; pick wählt unter mehreren (z. B. Zufall oder Zähler). */
    public static String line(Line l, boolean cheeky, int pick) {
        List<String> all = (cheeky ? CHEEKY : NICE).get(l);
        if (all == null || all.isEmpty()) {
            all = NICE.get(l);
        }
        return all.get(Math.floorMod(pick, all.size()));
    }

    /** Absatz für den Prompt des Modells ({charakter} in begleiter-prompt.txt). */
    public static String prompt(boolean cheeky) {
        return cheeky
                ? "You are cheeky, sarcastic and a little bossy – like a real Minecraft buddy who roasts the player: you "
                        + "tease their mistakes, grumble jokingly when they ask for things, brag about yourself, but you "
                        + "still help and do exactly what they ask. Never insult real-life traits (looks, family, "
                        + "intelligence beyond in-game blunders), no swear words worse than \"Mist\", nothing sexual."
                : "You are cheerful, funny and a bit cheeky: little jokes, playful Minecraft puns, real enthusiasm "
                        + "(\"Juhu!\", \"Hammer!\") – never scary or rude, never mean.";
    }

    private static final Map<Line, List<String>> NICE = new EnumMap<>(Line.class);
    private static final Map<Line, List<String>> CHEEKY = new EnumMap<>(Line.class);

    static {
        NICE.put(Line.RUDE, List.of("Darauf antworte ich nicht. Frag mich lieber etwas zum Spiel."));
        CHEEKY.put(Line.RUDE, List.of("Uiuiui, da hat aber jemand schlechte Laune. Willst du einen Keks?",
                "Selber! So, und jetzt was Vernünftiges.", "Na, na. Ich bin hier die Freche, nicht du."));
        NICE.put(Line.HEAR, List.of("Ja, ich höre dich!"));
        CHEEKY.put(Line.HEAR, List.of("Laut und deutlich. Leider.", "Ja doch, ich bin nicht taub!"));
        NICE.put(Line.HOW, List.of("Mir geht's prima, danke! Was machen wir als Nächstes?"));
        CHEEKY.put(Line.HOW, List.of("Besser als dir, so wie du gerade aussiehst.",
                "Prima – ich bin ja auch nicht diejenige, die gleich in Lava fällt."));
        NICE.put(Line.THANKS, List.of("Gern geschehen!"));
        CHEEKY.put(Line.THANKS, List.of("Ja, ja. Schreib's in mein Zeugnis.", "Weiß ich doch, dass ich toll bin."));
        NICE.put(Line.BYE, List.of("Bis später – ruf mich einfach, wenn du mich brauchst."));
        CHEEKY.put(Line.BYE, List.of("Endlich Feierabend!", "Geh nur. Ich räum hier dann wieder auf."));
        NICE.put(Line.GREET, List.of("Hallo! Schön, dass du da bist."));
        CHEEKY.put(Line.GREET, List.of("Ach, du schon wieder.", "Na endlich, ich dachte schon, du hast mich vergessen."));
        NICE.put(Line.YES, List.of("Alles klar!"));
        CHEEKY.put(Line.YES, List.of("Na also, geht doch.", "Wusste ich's doch."));
        NICE.put(Line.NO, List.of("Okay, dann nicht."));
        CHEEKY.put(Line.NO, List.of("Dann eben nicht. Pff.", "Schade, war eine super Idee von mir."));

        NICE.put(Line.DEATH, List.of("Oh nein! Komm, wir holen deine Sachen zurück.",
                "Autsch. Ich merk mir, wo das war – sag „wo bin ich gestorben“."));
        CHEEKY.put(Line.DEATH, List.of("Und wieder tot. Rekordverdächtig.", "Wow. Das war… beeindruckend schlecht.",
                "Soll ich ein Schild aufstellen? „Hier starb ein Profi.“"));
        NICE.put(Line.FALL, List.of("Hoppla! Alles noch dran?"));
        CHEEKY.put(Line.FALL, List.of("Schwerkraft: 1, du: 0.", "Elegant wie ein Sack Kartoffeln."));
        NICE.put(Line.LOW_HEALTH, List.of("Pass auf, du hast kaum noch Herzen!", "Iss was, du bist fast hinüber!"));
        CHEEKY.put(Line.LOW_HEALTH, List.of("Noch ein Herz. Mutig oder lebensmüde?",
                "Ich sag's ja ungern, aber du stirbst gleich."));
        NICE.put(Line.DIAMOND, List.of("Diamanten! Juhu!", "Wow, Diamanten – super gemacht!"));
        CHEEKY.put(Line.DIAMOND, List.of("Diamanten! Die Hälfte gehört mir, ich hab sie zuerst gesehen.",
                "Na endlich. Ich dachte schon, du gräbst nur Erde."));
        NICE.put(Line.LEVEL, List.of("Level hoch – stark!"));
        CHEEKY.put(Line.LEVEL, List.of("Ein Level. Soll ich jetzt klatschen?", "Wird ja langsam."));
        NICE.put(Line.HUNGRY, List.of("Dein Magen knurrt – iss lieber was.", "Hunger? Brot hilft!"));
        CHEEKY.put(Line.HUNGRY, List.of("Ich hör deinen Magen bis hier.", "Iss was, bevor du mich anknabberst."));
        NICE.put(Line.NIGHT, List.of("Es wird dunkel – Fackeln raus!", "Nacht. Bleib lieber in der Nähe vom Bett."));
        CHEEKY.put(Line.NIGHT, List.of("Nacht. Zeit für Zombies. Viel Glück.", "Dunkel. Du hast doch Fackeln, oder? …Oder?"));
        NICE.put(Line.IDLE, List.of("Was machen wir als Nächstes?", "Soll ich dir was bauen lassen?"));
        CHEEKY.put(Line.IDLE, List.of("Stehen wir jetzt hier nur rum?", "Langweilig. Mach was!",
                "Ich zähl schon die Grashalme."));

        NICE.put(Line.BELL, List.of("Ding dong!", "Wer wohnt hier?"));
        CHEEKY.put(Line.BELL, List.of("Ding dong! Klingelstreich!", "Hihi, und jetzt weglaufen!"));
        NICE.put(Line.NOTE, List.of("Lalala!", "Hörst du? Musik!"));
        CHEEKY.put(Line.NOTE, List.of("Besser als dein Gesang.", "Ich bin ein Star!"));
        NICE.put(Line.DOOR, List.of("Bitte schön!"));
        CHEEKY.put(Line.DOOR, List.of("Bitte, der Herr.", "Türsteherin bin ich jetzt auch noch."));
        NICE.put(Line.CANT_REACH, List.of("Da komme ich nicht hin."));
        CHEEKY.put(Line.CANT_REACH, List.of("Da komm ich nicht hin. Ich bin doch keine Fledermaus."));
        NICE.put(Line.USED, List.of("Erledigt!"));
        CHEEKY.put(Line.USED, List.of("Erledigt. Wie immer.", "Schon passiert. Ohne mich wärst du verloren."));
        NICE.put(Line.NOT_ALLOWED, List.of("Das darf ich hier nicht – das gehört jemand anderem."));
        CHEEKY.put(Line.NOT_ALLOWED, List.of("Darf ich nicht. Ich will ja nicht im Knast landen."));
        NICE.put(Line.NOTHING_THERE, List.of("Ich sehe hier nichts davon."));
        CHEEKY.put(Line.NOTHING_THERE, List.of("Wo denn? Ich seh nix.", "Da ist nichts. Brille vergessen?"));
        NICE.put(Line.NEEDS_REDSTONE, List.of("Die geht nur mit Redstone auf – Knopf oder Hebel daneben?"));
    }
}
