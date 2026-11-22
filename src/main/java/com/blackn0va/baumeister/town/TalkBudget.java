package com.blackn0va.baumeister.town;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Globales KI-Budget aller Bewohner (Vertrag v008, AK-24): höchstens eine Anfrage je gap, nie zwei gleichzeitig, und
 * keine, solange ein Bauplan denkt oder Vera eine Frage beantwortet.
 *
 * Warum so knapp: Bau, Vera und Bewohner teilen sich einen Host. Jede Bewohner-Anfrage verdrängt im Host das
 * Vorwärmen des Bauplan-Prompts (Gedächtnis „Offen“: der erste Bauauftrag danach rechnet den System-Prompt neu), und
 * llama-server rechnet Anfragen nacheinander – ein Bewohner-Satz vor einem Bauauftrag ist Wartezeit für den Spieler.
 * Wer die Sperre bei thinking entfernt, lässt Bewohner-Geplauder vor einen Bauauftrag rutschen.
 *
 * Abgelehnt heißt nicht stumm: Der Aufrufer nimmt dann einen festen Satz (TownLines).
 *
 * Threads: nur Hauptthread. Fehler: keine.
 */
public final class TalkBudget {

    /** Wie viele Antwortzeiten für Status und Messung behalten werden. */
    static final int KEEP = 64;

    private long gapMillis;
    private long lastAsk = Long.MIN_VALUE / 2;
    private boolean inFlight;
    private int asked;
    private int answered;
    private int fallbacks;
    private long lastMillis = -1;
    private boolean lastOk;
    private final Deque<Long> times = new ArrayDeque<>();

    public TalkBudget(long gapMillis) {
        this.gapMillis = gapMillis;
    }

    public void gap(long gapMillis) {
        this.gapMillis = gapMillis;
    }

    /** Darf jetzt gefragt werden? Ändert nichts. */
    public boolean allowed(long nowMs, boolean buildThinking, boolean companionThinking) {
        return !inFlight && !buildThinking && !companionThinking && nowMs - lastAsk >= gapMillis;
    }

    /** Wie allowed, stellt bei Ja die Uhr und merkt die laufende Anfrage. */
    public boolean begin(long nowMs, boolean buildThinking, boolean companionThinking) {
        if (!allowed(nowMs, buildThinking, companionThinking)) {
            return false;
        }
        inFlight = true;
        lastAsk = nowMs;
        asked++;
        return true;
    }

    /** Anfrage fertig: ok = brauchbare Antwort, sonst fiel der Bewohner auf einen festen Satz zurück. */
    public void end(long millis, boolean ok) {
        inFlight = false;
        lastMillis = millis;
        lastOk = ok;
        if (ok) {
            answered++;
        } else {
            fallbacks++;
        }
        times.addLast(millis);
        while (times.size() > KEEP) {
            times.removeFirst();
        }
    }

    public boolean inFlight() {
        return inFlight;
    }

    public int asked() {
        return asked;
    }

    public int answered() {
        return answered;
    }

    public int fallbacks() {
        return fallbacks;
    }

    public long lastMillis() {
        return lastMillis;
    }

    public boolean lastOk() {
        return lastOk;
    }

    /** Sekunden seit der letzten Anfrage, -1 ohne Anfrage. */
    public long secondsSinceAsk(long nowMs) {
        return asked == 0 ? -1 : (nowMs - lastAsk) / 1000;
    }

    /** Mittel und Höchstwert der behaltenen Antwortzeiten in ms, {0, 0} ohne Werte. */
    public long[] timesMeanMax() {
        if (times.isEmpty()) {
            return new long[] {0, 0};
        }
        long sum = 0;
        long max = 0;
        for (long t : times) {
            sum += t;
            max = Math.max(max, t);
        }
        return new long[] {sum / times.size(), max};
    }
}
