package com.blackn0va.baumeister.client;

/**
 * Laufende Planung als Ticket: Der Rückruf der LLM-Antwort gilt nur, solange sein Ticket aktuell ist. Vorher kannte
 * /kbau stop die Planung nicht („Gerade läuft kein Bau.“), und der Plan kam bis zu 10 min später trotzdem und wurde
 * gebaut – die Figur übernahm Blick und Flug, nachdem der Spieler abbrechen wollte. Die HTTP-Anfrage selbst läuft
 * weiter; ihr Ergebnis wird nur verworfen. Wer cancel() leert, lässt abgebrochene Pläne wieder bauen – und einen
 * späten Rückruf einen neuen Auftrag überschreiben.
 *
 * Threads: nur Client-Thread (Rückrufe springen per Minecraft.execute zurück). Fehler: keine.
 */
final class PlanTicket {

    private int generation;

    /** Neues Ticket für eine Planung; alle älteren werden damit ungültig. */
    int issue() {
        return ++generation;
    }

    /** Die laufende Planung abbrechen: Ihr Ticket ist ab jetzt ungültig. */
    void cancel() {
        generation++;
    }

    boolean isCurrent(int ticket) {
        return ticket == generation;
    }
}
