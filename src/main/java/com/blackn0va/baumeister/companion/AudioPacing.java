package com.blackn0va.baumeister.companion;

/**
 * Takt für Veras Stimme im Voice-Chat: wann der nächste 20-ms-Rahmen an Simple Voice Chat geht.
 *
 * Wunsch 2026-11-07. Belegt am Quellcode von Simple Voice Chat
 * 1.21.11 (AudioPlayerImpl, ALSpeakerBase, ClientConfig):
 * - Der Server-Player schickt streng im Echtzeittakt ohne Vorlauf und am Satzende eine Endmarke (flush).
 * - Der Client spielt jeden Rahmen sofort in eine OpenAL-Warteschlange (32 Puffer). Läuft sie leer, schiebt er beim
 *   nächsten Rahmen erst output_buffer_size Stille-Rahmen vor (Vorgabe 5 = 100 ms).
 * - Auf dev01 hält Java für die Speicherbereinigung an (gemessen 2026-11-07: 40 Pausen in 9 min, Median 39 ms, längste
 *   132 ms; früher bis 261 ms). Jede Pause über 100 ms leerte das Polster – Lücke plus 100 ms Stille. Zwischen zwei
 *   Sätzen ebenso: Endmarke, dann neuer Player über den Haupttakt (≥ 50 ms).
 *
 * Jetzt: ein Strom über alle Sätze; die ersten PREROLL Rahmen gehen sofort raus (Polster beim Client 5 + 10 = 15 von 32
 * Puffern, 300 ms); danach fester Zeitplan, nach einer Pause werden überfällige Rahmen sofort nachgeschickt. Liegt der
 * Plan mehr als MAX_BEHIND zurück (Polster beim Client sicher leer, z. B. längere Satzpause), beginnt ein neuer Strom
 * mit neuem Vorlauf. Wer PREROLL auf 0 setzt, hat das alte Verhalten zurück; wer ihn über ~25 hebt, stößt an die
 * 32 Puffer des Clients, und der springt vor („Full playback buffer“) – der Satzanfang fehlt.
 *
 * Threads: ein Objekt je Sprecher-Strom, nur aus dessen Sende-Faden benutzt (nicht threadsicher). Fehler: keine.
 */
public final class AudioPacing {

    public static final long FRAME_NS = 20_000_000L;
    /** Rahmen, die beim Start sofort rausgehen (200 ms). */
    public static final int PREROLL = 10;
    /** Polster des Clients in Rahmen (Simple Voice Chat output_buffer_size, Vorgabe 5). */
    static final int CLIENT_CUSHION = 5;
    /** So weit hinter dem Plan ist das Polster beim Client sicher leer: neuer Strom. */
    public static final long MAX_BEHIND = (PREROLL + CLIENT_CUSHION) * FRAME_NS;

    private long start;
    private long sent;
    private boolean running;
    private boolean restarted;

    /** Zeitpunkt (nanoTime), zu dem der nächste Rahmen raus soll; now ≥ Rückgabe heißt: sofort senden. */
    public long due(long now) {
        restarted = false;
        if (running && now - planned() > MAX_BEHIND) {
            running = false;
        }
        if (!running) {
            start = now;
            sent = 0;
            running = true;
            restarted = true;
        }
        return planned();
    }

    /** Beginn des laufenden Stroms (nanoTime); Vorlauf-Rahmen sind ab da „pünktlich“. */
    public long started() {
        return start;
    }

    /** Ein Rahmen ist raus. */
    public void sent() {
        sent++;
    }

    /** Hat der letzte due() einen neuen Strom begonnen (Vorlauf)? */
    public boolean restarted() {
        return restarted;
    }

    /** Strom ist zu Ende (Satzende ohne Nachschub, Ins-Wort-Fallen): der nächste Rahmen beginnt neu mit Vorlauf. */
    public void stop() {
        running = false;
    }

    /** Wie lange der Client noch zu spielen hat (Vorsprung des Plans vor now), höchstens das ganze Polster. */
    public long ahead(long now) {
        return running ? Math.max(0, planned() + CLIENT_CUSHION * FRAME_NS - now) : 0;
    }

    private long planned() {
        return start + (sent - PREROLL) * FRAME_NS;
    }
}
