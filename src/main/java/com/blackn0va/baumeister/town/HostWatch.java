package com.blackn0va.baumeister.town;

/**
 * Ist die lokale KI da? Das Stadtleben läuft nur, solange der Host antwortet (Vertrag v008, AK-22: „wenn die lokale
 * KI an ist“). Es gibt im Plugin keinen Schalter „KI an“ – nur LlmClient.health() (GET /health, sonst /v1/models).
 *
 * Takt: höchstens eine Prüfung je INTERVAL_MS, nie zwei gleichzeitig, asynchron (der Aufrufer schickt health() ab und
 * meldet das Ergebnis mit result()). Der Vertrag verlangt „Health-Prüfung höchstens alle 60 s“ und „verschwinden
 * binnen 60 s“. Gelesen als Abstand ≤ 60 s; gewählt 30 s, damit auch eine hängende Prüfung (2 × 5 s Frist in
 * LlmClient.status) mit ihrem Ergebnis noch in die 60 s fällt. Ein abgelehnter Verbindungsaufbau (Host beendet) kommt
 * nach Millisekunden. Zusätzlich meldet jede gescheiterte Bewohner-Anfrage mit Verbindungsfehler sofort down().
 *
 * Vor der ersten Antwort gilt der Host als NICHT da (fail-closed): Kein Bewohner erscheint auf Verdacht.
 *
 * Threads: nur Hauptthread (result() kommt per runTask zurück). Fehler: keine.
 */
public final class HostWatch {

    public static final long INTERVAL_MS = 30_000L;

    private boolean up;
    private boolean pending;
    private long lastAsk = Long.MIN_VALUE / 2;
    private long lastAnswer = Long.MIN_VALUE / 2;
    private boolean answered;

    /** Jetzt eine Prüfung abschicken? Stellt die Uhr und merkt „läuft“. */
    public boolean due(long nowMs) {
        if (pending || nowMs - lastAsk < INTERVAL_MS) {
            return false;
        }
        pending = true;
        lastAsk = nowMs;
        return true;
    }

    /** Ergebnis einer Prüfung. */
    public void result(boolean ok, long nowMs) {
        pending = false;
        up = ok;
        answered = true;
        lastAnswer = nowMs;
    }

    /** Eine Bewohner-Anfrage scheiterte an der Verbindung: sofort als weg werten, nicht bis zur nächsten Prüfung. */
    public void down(long nowMs) {
        up = false;
        answered = true;
        lastAnswer = nowMs;
    }

    /** Modus eingeschaltet: sofort prüfen statt bis zu 30 s zu warten. */
    public void askSoon() {
        if (!pending) {
            lastAsk = Long.MIN_VALUE / 2;
        }
    }

    public boolean up() {
        return up;
    }

    public boolean answered() {
        return answered;
    }

    /** Sekunden seit der letzten Antwort, -1 ohne Antwort. */
    public long secondsSinceAnswer(long nowMs) {
        return answered ? (nowMs - lastAnswer) / 1000 : -1;
    }
}
