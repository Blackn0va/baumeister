package com.blackn0va.baumeister.companion;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Kurze Lieder für den Begleiter (Aktion song), als Notenblock-Töne. Feste Liste: Das LLM nennt nur den Namen, die
 * Töne stehen hier – ein Modell, das Noten erfindet, klingt schief.
 *
 * Ton = Notenblock-Stufe 0 … 24 (0 = Fis3, 12 = Fis4, wie Note.Tone in Bukkit), Dauer in Ticks bis zum nächsten Ton.
 * Gemeinfreie Melodien (Volkslied; Happy Birthday ist seit 2016 in den USA gemeinfrei).
 *
 * Threads: unveränderlich. Fehler: byName liefert null für unbekannte Namen.
 */
public final class Songs {

    /** Ein Ton: step 0 … 24, ticks bis zum nächsten. */
    public record Note(int step, int ticks) {
    }

    public record Song(String name, List<Note> notes) {
    }

    // Stufen: C4 = 6, D4 = 8, E4 = 10, F4 = 11, G4 = 13, A4 = 15, H4 = 17, C5 = 18, D5 = 20
    private static final Map<String, Song> SONGS = Map.of(
            "alle_meine_entchen", new Song("Alle meine Entchen", List.of(
                    n(6, 4), n(8, 4), n(10, 4), n(11, 4), n(13, 8), n(13, 8),
                    n(15, 4), n(15, 4), n(15, 4), n(15, 4), n(13, 12),
                    n(15, 4), n(15, 4), n(15, 4), n(15, 4), n(13, 12),
                    n(11, 4), n(11, 4), n(11, 4), n(11, 4), n(10, 8), n(10, 8),
                    n(8, 4), n(8, 4), n(8, 4), n(8, 4), n(6, 12))),
            "happy_birthday", new Song("Happy Birthday", List.of(
                    n(13, 3), n(13, 3), n(15, 6), n(13, 6), n(18, 6), n(17, 12),
                    n(13, 3), n(13, 3), n(15, 6), n(13, 6), n(20, 6), n(18, 12))),
            "tusch", new Song("Tusch", List.of(n(6, 2), n(10, 2), n(13, 2), n(18, 10))));

    private Songs() {
    }

    private static Note n(int step, int ticks) {
        return new Note(step, ticks);
    }

    /** Name wie im Prompt („alle_meine_entchen“), Leerzeichen und Groß-/Kleinschreibung egal. */
    public static Song byName(String name) {
        if (name == null) {
            return null;
        }
        return SONGS.get(name.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
    }

    public static java.util.Set<String> names() {
        return new java.util.TreeSet<>(SONGS.keySet());
    }
}
