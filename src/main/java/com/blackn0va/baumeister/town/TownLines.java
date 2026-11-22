package com.blackn0va.baumeister.town;

import java.util.List;

/**
 * Feste Sätze der Bewohner: Gruß im Vorbeigehen (ohne KI, AK-23) und Rückfall, wenn das KI-Budget nein sagt, der Host
 * schweigt oder die Antwort durch den Filter fällt (AK-24). Grüßen läuft bewusst nie über die KI – es kommt oft und
 * soll das Budget nicht aufbrauchen, das für echte Gespräche da ist.
 *
 * Threads: unveränderlich. Fehler: keine.
 */
public final class TownLines {

    static final List<String> GREET = List.of("Hallo!", "Guten Tag!", "Na, alles gut?", "Servus!", "Moin!",
            "Schönen Tag noch!");
    static final List<String> REPLY = List.of("Da hab ich gerade keine Antwort – frag mich nachher noch mal.",
            "Hm, gute Frage. Ich muss weiter, bis später!", "Ach, ich bin gerade in Gedanken. Später gern!",
            "Das erzähl ich dir ein andermal.", "Ich hab gerade alle Hände voll zu tun!");
    static final List<String> MEET = List.of("Na, auch unterwegs?", "Schönes Wetter heute, oder?",
            "Wir sehen uns später!", "Viel zu tun heute?", "Grüß mir die Nachbarn!");

    private TownLines() {
    }

    public static String greet(int pick) {
        return GREET.get(Math.floorMod(pick, GREET.size()));
    }

    /** Rückfall auf eine Spielerfrage; jeder fünfte Satz ist der Arbeitsspruch des Berufs. */
    public static String reply(Roster.Person p, int pick) {
        return Math.floorMod(pick, 5) == 0 ? p.workLine() : REPLY.get(Math.floorMod(pick, REPLY.size()));
    }

    public static String meet(int pick) {
        return MEET.get(Math.floorMod(pick, MEET.size()));
    }
}
