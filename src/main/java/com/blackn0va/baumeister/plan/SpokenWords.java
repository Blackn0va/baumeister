package com.blackn0va.baumeister.plan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hörfehler der Spracherkennung bei Spielwörtern, bevor ein Bauauftrag erkannt wird.
 *
 * Vorfall dev01 2026-11-04 16:09: Whisper hörte „Baue mir ein Rapstone Computer“. Mit „Rapstone“ verfehlte der Auftrag
 * den festen Weg der Prozessor-Werkstatt (QuickOrders, nur bei bekannten Wörtern), das LLM legte 55 Reihen
 * Redstone-Staub auf den Boden (3 580 Blöcke). Mit „Redstone“ läuft derselbe Satz seit 2026-10-31 ohne LLM.
 * Vera reicht das falsche Wort unverändert weiter („einen Rapstone-Computer“), deshalb sitzt die Korrektur am
 * Eingang des Baumeisters und nicht nur in der Spracherkennung.
 *
 * Nur Wortformen, die sonst nichts bedeuten: r + 1–3 Buchstaben + „ston(e)“/„stoun“ („rapstone“, „rädstone“,
 * „retstone“, „redstoun“) und die deutschen „Redstein“/„Rotstein“. Wer das Muster weiter fasst, trifft echte Wörter.
 *
 * Threads: zustandslos.
 */
public final class SpokenWords {

    private static final Pattern REDSTONE = Pattern.compile(
            "(?iuU)\\b(r\\p{L}{1,3}st(?:one|on|oun|oune)|redstein|rotstein)(?=\\b|-)");

    /**
     * „Bauen wir ein Pull“ (dev01 2026-11-04 19:10): Whisper hört „Pool“ als „Pull“. Nur nach Artikel oder
     * Größenwort – „Pull“ allein (Pullover, Pull-Request) bleibt stehen.
     */
    private static final Pattern POOL = Pattern.compile(
            "(?iuU)\\b(ein|einen|den|dem|im|zum|neuen|großen|kleinen|grossen)\\s+(?:pull|pul|puhl|pohl)\\b");

    /**
     * „bau mir fort, Max“ (dev01 2026-11-04 20:24, gemeint war Fort Knox): Der Satz ging am Schnellauftrag vorbei
     * ans LLM, das das Haus daneben umbaute. Nur „Fort“ + ein Wort, das wie „Knox“ klingt (Max/Nox/Nocks/Knocks/
     * Nax/Knax), auch mit Komma dazwischen – „fort“ allein (weg) bleibt stehen.
     */
    private static final Pattern FORT_KNOX = Pattern.compile(
            "(?iuU)\\bfort[\\s,.-]*(?:max|nox|noks|nocks|knocks|knoks|nax|knax|knoxx|knochs)\\b|\\bfortnox\\b");

    private SpokenWords() {
    }

    public static String fix(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher m = REDSTONE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String w = m.group(1);
            m.appendReplacement(sb, Character.isUpperCase(w.charAt(0)) ? "Redstone" : "redstone");
        }
        m.appendTail(sb);
        String out = POOL.matcher(sb.toString()).replaceAll("$1 Pool");
        return FORT_KNOX.matcher(out).replaceAll("Fort Knox");
    }
}
