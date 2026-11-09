package com.blackn0va.baumeister.companion;

import java.util.ArrayList;
import java.util.List;

/**
 * Teilt Veras Antwort für die Stimme in Sätze, damit der erste Satz erklingt, während der nächste noch erzeugt wird.
 *
 * Vorher ging der ganze Text (bis 300 Zeichen) in einem Stück an Piper: Bis der erste Ton kam, musste die ganze
 * Antwort fertig gerechnet sein. Jetzt kommt die Kette Satz für Satz (CompanionService.sayAloud), Piper rechnet Satz 2,
 * während Satz 1 im Spiel läuft. Kurze Sätze („Klar!“) werden mit dem nächsten zusammengelegt – je Stück kostet ein
 * Aufruf an den Host Zeit, und abgehackte Einzelwörter klingen schlechter.
 *
 * Threads: zustandslos. Fehler: keine; leerer Text → leere Liste.
 */
public final class SpeechChunks {

    /** Kürzere Stücke werden mit dem nächsten verbunden. */
    static final int MIN_CHARS = 40;
    /** Mehr Stücke nicht – der Rest hängt am letzten (die Warteschlange im Spiel hält nur wenige). */
    public static final int MAX_CHUNKS = 4;

    private SpeechChunks() {
    }

    public static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        List<String> sentences = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String t = text.strip();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            cur.append(c);
            boolean end = (c == '.' || c == '!' || c == '?' || c == '…')
                    && (i + 1 == t.length() || Character.isWhitespace(t.charAt(i + 1)))
                    && !abbreviation(cur);
            if (end) {
                sentences.add(cur.toString().strip());
                cur.setLength(0);
            }
        }
        if (!cur.toString().isBlank()) {
            sentences.add(cur.toString().strip());
        }
        StringBuilder chunk = new StringBuilder();
        for (String s : sentences) {
            chunk.append(chunk.isEmpty() ? "" : " ").append(s);
            if (chunk.length() >= MIN_CHARS) {
                out.add(chunk.toString());
                chunk.setLength(0);
            }
        }
        if (!chunk.isEmpty()) {
            if (out.isEmpty()) {
                out.add(chunk.toString());
            } else {
                out.set(out.size() - 1, out.get(out.size() - 1) + " " + chunk);
            }
        }
        while (out.size() > MAX_CHUNKS) {
            String last = out.remove(out.size() - 1);
            out.set(out.size() - 1, out.get(out.size() - 1) + " " + last);
        }
        return out;
    }

    /** „z.B.“, „ca.“, „Nr.“, Ziffern vor dem Punkt („17.“) – dort endet kein Satz. */
    private static boolean abbreviation(StringBuilder cur) {
        String s = cur.toString();
        int start = s.lastIndexOf(' ') + 1;
        String word = s.substring(start, s.length() - 1).toLowerCase(java.util.Locale.ROOT);
        return word.equals("z.b") || word.equals("ca") || word.equals("nr") || word.equals("bzw") || word.equals("usw")
                || word.equals("d.h") || (!word.isEmpty() && word.chars().allMatch(Character::isDigit));
    }
}
