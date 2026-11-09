package com.blackn0va.baumeister;

import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wie viele Helfer ein normaler Bau bekommt. Wunsch 2026-10-30. Vorher entschied allein die Größe (ab 300 Blöcken
 * einer, ab 700 zwei), und /bm helfer wirkte
 * nur im Stadtbau – bei einem normalen Bau kam „Gerade läuft kein Stadtbau.“.
 *
 * Jetzt gilt in dieser Reihenfolge: „… mit 6 Helfern“ im Auftrag (nur dieser Bau) → der stehende Wunsch des Spielers
 * (/bm helfer &lt;n&gt;, Chat „mehr helfer“; bis /bm helfer auto oder Neustart) → die Größenregel aus helfer: in der
 * config.yml. Höchstens {@link #MAX} wie im Stadtbau (CityService.control).
 *
 * Threads: zustandslos, von überall. Fehler: keine – Unlesbares zählt als „kein Wunsch“.
 */
final class HelperWish {

    /** Wie /bm helfer im Stadtbau: 0–16. Jeder Helfer ist ein eigener Mannequin. */
    static final int MAX = 16;

    private static final Map<String, Integer> WORDS = Map.ofEntries(Map.entry("einem", 1), Map.entry("einen", 1),
            Map.entry("einer", 1), Map.entry("ein", 1), Map.entry("zwei", 2), Map.entry("drei", 3),
            Map.entry("vier", 4), Map.entry("fünf", 5), Map.entry("sechs", 6), Map.entry("sieben", 7),
            Map.entry("acht", 8), Map.entry("neun", 9), Map.entry("zehn", 10), Map.entry("elf", 11),
            Map.entry("zwölf", 12));

    /**
     * „mit 6 Helfern“, „mit drei helfern“, „mit einem Helfer“, „ohne Helfer“ – samt Komma davor, damit „Turm, mit 4
     * Helfern“ sauber zu „Turm“ wird. Zahl als Ziffern oder Wort bis zwölf.
     */
    private static final Pattern PHRASE = Pattern.compile(
            "\\s*,?\\s*\\b(?:mit\\s+(\\d{1,3}|einem|einen|einer|ein|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf"
                    + "|zwölf)|(ohne))\\s+helfer(?:n|in|innen)?\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private HelperWish() {
    }

    /** Helferzahl aus dem Auftrag, leer = keine Angabe. */
    static OptionalInt fromPrompt(String prompt) {
        if (prompt == null) {
            return OptionalInt.empty();
        }
        Matcher m = PHRASE.matcher(prompt);
        if (!m.find()) {
            return OptionalInt.empty();
        }
        if (m.group(2) != null) {
            return OptionalInt.of(0);
        }
        String n = m.group(1).toLowerCase(Locale.GERMAN);
        Integer word = WORDS.get(n);
        return OptionalInt.of(clamp(word != null ? word : Integer.parseInt(n)));
    }

    /**
     * Auftrag ohne die Helfer-Angabe – fürs LLM. Sonst plante es „6 Helfer“ womöglich als Figuren oder Schilder ein;
     * die Helfer stellt der Bau, nicht der Plan.
     */
    static String strip(String prompt) {
        if (prompt == null) {
            return null;
        }
        String s = PHRASE.matcher(prompt).replaceAll("").trim();
        return s.isEmpty() ? prompt.trim() : s;
    }

    static int clamp(int n) {
        return Math.max(0, Math.min(MAX, n));
    }

    /**
     * Helfer für einen neuen Bau. standing null = kein stehender Wunsch.
     *
     * @param secondFrom ab so vielen Blöcken ein Helfer (helfer.zweiter-ab-blöcken)
     * @param thirdFrom  ab so vielen Blöcken zwei Helfer (helfer.dritter-ab-blöcken)
     */
    static int choose(OptionalInt fromPrompt, Integer standing, int blocks, int secondFrom, int thirdFrom) {
        if (fromPrompt.isPresent()) {
            return clamp(fromPrompt.getAsInt());
        }
        if (standing != null) {
            return clamp(standing);
        }
        return blocks >= thirdFrom ? 2 : blocks >= secondFrom ? 1 : 0;
    }

    /** /bm helfer &lt;arg&gt;: Zahl, „+2“/„-2“ relativ zu current, „auto“ = -1. null = unlesbar. */
    static Integer parseCommand(String arg, int current) {
        if (arg == null) {
            return null;
        }
        String a = arg.trim().toLowerCase(Locale.GERMAN);
        if (a.equals("auto")) {
            return -1;
        }
        try {
            if (a.startsWith("+") || a.startsWith("-")) {
                return clamp(current + Integer.parseInt(a));
            }
            return clamp(Integer.parseInt(a));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
