package com.blackn0va.baumeister.dataset;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Deutsche Auftragstexte mit Abwandlungen, wie Spieler sie tippen: mit und ohne Anrede, klein geschrieben, ohne
 * Satzzeichen, gelegentlich „ae“ statt „ä“.
 *
 * Warum so viele Abwandlungen: Ein feingetuntes kleines Modell lernt sonst die Satzform statt des Auftrags – „Bau mir
 * ein Haus“ ginge, „kannst du mir ein haus bauen“ nicht. Die Abwandlungen ändern nie den Inhalt (Maße, Material,
 * Anzahl), damit der Plan zum Text passt.
 *
 * Threads: zustandslos, der Random kommt vom Aufrufer (fester Seed = gleicher Datensatz). Fehler: keine.
 */
final class Phrases {

    /** Geschlecht des Nomens für Artikel und Adjektivendung. */
    enum Gender {
        M, F, N
    }

    /** Ein Nomen samt Geschlecht, z. B. Turm (m), Burg (f), Haus (n). */
    record Noun(String word, Gender gender) {
    }

    /**
     * Einleitungen für Aufträge, die ans LLM gehen. {} = Nominalphrase im Akkusativ; ein Eintrag ohne {} hängt die
     * Phrase im Nominativ allein an („ein Turm aus Stein“, wie das Beispiel im System-Prompt).
     */
    private static final List<String> OPENERS = List.of("Bau mir {}", "bau mir {}", "Baue {}", "baue bitte {}",
            "Kannst du mir {} bauen?", "kannst du {} bauen", "Ich hätte gern {}", "ich will {}", "Errichte {}",
            "mach mir {}", "Bitte bau {}", "Wir brauchen {}", "hey, bau mir {}", "Bau uns {}", "bau mal {}",
            "Ich möchte {}", "bau hier {}", "Baumeister, bau {}", "");

    /** Nur Füllwörter aus QuickOrders.FILLER – sonst geht der Schnellauftrag ans LLM. */
    private static final List<String> QUICK_OPENERS = List.of("Bau mir {}", "bau mir {}", "Baue {}", "bau {}",
            "Bau uns {}", "bau mal {}", "bitte bau mir {}", "Bau hier {}", "bau mir mal {}", "");

    private Phrases() {
    }

    /** Auftrag ans LLM: beliebige Einleitung. adjectives als Stämme („klein“, „zweistöckig“), Endung kommt hier. */
    static String order(Random r, Noun noun, List<String> adjectives, String rest) {
        return order(r, pick(r, OPENERS), noun, adjectives, rest);
    }

    /** Schnellauftrag: nur Einleitungen aus Füllwörtern. */
    static String quickOrder(Random r, Noun noun, List<String> adjectives, String rest) {
        return order(r, pick(r, QUICK_OPENERS), noun, adjectives, rest);
    }

    /** Freie Einleitung um eine fertige Akkusativphrase („eine Zuckerrohrfarm und eine Schmelzanlage …“). */
    static String orderPhrase(Random r, String accusativePhrase, String nominativePhrase) {
        String opener = pick(r, OPENERS);
        return casual(r, opener.isEmpty() ? capitalizeMaybe(r, nominativePhrase)
                : opener.replace("{}", accusativePhrase));
    }

    /** Wie orderPhrase, nur mit Schnellauftrag-Einleitungen. */
    static String quickPhrase(Random r, String accusativePhrase, String nominativePhrase) {
        String opener = pick(r, QUICK_OPENERS);
        return casual(r, opener.isEmpty() ? capitalizeMaybe(r, nominativePhrase)
                : opener.replace("{}", accusativePhrase));
    }

    private static String order(Random r, String opener, Noun noun, List<String> adjectives, String rest) {
        boolean bare = opener.isEmpty();
        String np = nounPhrase(noun, adjectives, !bare) + (rest.isEmpty() ? "" : " " + rest);
        String text = bare ? capitalizeMaybe(r, np) : opener.replace("{}", np);
        return casual(r, text);
    }

    /** „einen kleinen runden Turm“ (Akkusativ) bzw. „ein kleiner runder Turm“ (Nominativ). */
    static String nounPhrase(Noun noun, List<String> adjectives, boolean accusative) {
        String article = switch (noun.gender()) {
            case M -> accusative ? "einen" : "ein";
            case F -> "eine";
            case N -> "ein";
        };
        String ending = switch (noun.gender()) {
            case M -> accusative ? "en" : "er";
            case F -> "e";
            case N -> "es";
        };
        StringBuilder sb = new StringBuilder(article);
        for (String a : adjectives) {
            sb.append(' ').append(a).append(ending);
        }
        return sb.append(' ').append(noun.word()).toString();
    }

    /**
     * Spielertypisch: oft klein, mal ohne Satzzeichen am Ende, selten Umlaute als ae/oe/ue. Nie mitten im Wort
     * verändert, damit Maß- und Materialwörter lesbar bleiben (RequestHints normalisiert ä und ae gleich).
     */
    static String casual(Random r, String text) {
        String t = text;
        if (r.nextInt(100) < 35) {
            t = t.toLowerCase(Locale.ROOT);
        }
        if (r.nextInt(100) < 50 && (t.endsWith("?") || t.endsWith("!") || t.endsWith("."))) {
            t = t.substring(0, t.length() - 1);
        } else if (r.nextInt(100) < 15 && !t.endsWith("?") && !t.endsWith(".") && !t.endsWith("!")) {
            t = t + "!";
        }
        if (r.nextInt(100) < 8) {
            t = t.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue"); // umlaut-ok: so tippen manche Spieler
        }
        return t;
    }

    private static String capitalizeMaybe(Random r, String s) {
        return r.nextBoolean() ? Character.toUpperCase(s.charAt(0)) + s.substring(1) : s;
    }

    static <T> T pick(Random r, List<T> list) {
        return list.get(r.nextInt(list.size()));
    }

    /** Zufallszahl in [lo, hi]. */
    static int between(Random r, int lo, int hi) {
        return lo + r.nextInt(hi - lo + 1);
    }
}
