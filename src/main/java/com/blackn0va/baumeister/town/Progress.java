package com.blackn0va.baumeister.town;

/**
 * Kommt ein Bewohner seinem Teilziel näher? Fortschritt heißt hier Annäherung, nicht Bewegung.
 *
 * Vorher galt CompanionWalk.State: Jeder Tick mit mindestens 0,03 Block waagrechter Bewegung setzte den
 * Feststeck-Zähler zurück. Paper-Probe 2026-11-21 (Spur greta): Greta hing gut 5 min bei ca. -3 93 -20, sprang auf der
 * Stelle (y 93,0–94,25) und wackelte waagrecht 0,2–0,4 Blöcke – das zählte als Fortschritt, also nie ein Teleport,
 * dafür Wegsuchen 5 → 2286 (~400 je Minute). Jetzt zählt nur, wenn der Abstand zum Teilziel um mindestens GAIN unter
 * den bisher besten fällt; sonst steckt er nach STUCK_TICKS fest. CompanionWalk (Vera) bleibt unverändert – deren
 * Verhalten soll sich nicht ändern, und dort folgt das Ziel einem Spieler, Annäherung wäre kein gutes Maß.
 *
 * Threads: Hauptthread, je Bewohner einer. Fehler: keine.
 */
public final class Progress {

    /** So lange ohne Annäherung, dann gilt er als festgesteckt (3 s). */
    public static final int STUCK_TICKS = 60;
    /** So viel näher muss er kommen, damit es als Fortschritt zählt (Blöcke). */
    public static final double GAIN = 0.5;

    private double best = Double.MAX_VALUE;
    private long since;

    /** Neues Teilziel oder nach einem Teleport: von vorn zählen. */
    public void reset(long tick) {
        best = Double.MAX_VALUE;
        since = tick;
        rebased = false;
    }

    private boolean rebased;

    /**
     * Fortschritt mit zwei Maßen (T-035): rest = Restweg am geplanten Weg (NaN ohne Weg), straight = Luftlinie. Beim
     * ersten Weg des Teilziels wechselt das Maß einmal auf den Restweg (der ist meist länger), danach nie wieder.
     * Vorher wechselte es bei jedem Wechsel „Weg/kein Weg“ neu – live gemessen (testserver, Holzfäller vor einem Stamm
     * unter tiefem Laub): Die Suche fand abwechselnd einen Weg und keinen, jedes neue Maß ließ das Wackeln als
     * Annäherung zählen, und er stand 4 min ohne eine einzige Feststeck-Stufe.
     */
    public boolean stuck(double rest, double straight, long tick) {
        if (Double.isNaN(rest)) {
            return stuck(straight, tick);
        }
        if (!rebased) {
            rebased = true;
            rebase(rest);
        }
        return stuck(rest, tick);
    }

    /** dist = Abstand zum Teilziel in diesem Tick. true = steckt fest. */
    public boolean stuck(double dist, long tick) {
        improved = best != Double.MAX_VALUE && dist < best - GAIN;
        if (best == Double.MAX_VALUE || improved) {
            best = dist;
            since = tick;
            return false;
        }
        return tick - since >= STUCK_TICKS;
    }

    private boolean improved;

    /**
     * Kam er im letzten stuck-Aufruf wirklich um GAIN näher? Nicht dasselbe wie stalled() == 0: Das gilt auch direkt
     * nach reset, und Unstuck finge nach jeder Stufe wieder bei der ersten an (gemessen T-035: fünfmal „neu planen“,
     * nie „ausweichen“).
     */
    public boolean improved() {
        return improved;
    }

    /**
     * Maß wechselt von der Luftlinie auf den Restweg des ersten Wegs (T-035): bester Wert = dist, die Uhr läuft weiter.
     * Nur dann – nicht bei jeder Neuplanung (TownLife.walk sagt warum). Ein reset hier ließe einen Bewohner, der nie
     * vorankommt, mit jedem Wechsel neu anfangen.
     */
    void rebase(double dist) {
        best = dist;
    }

    /** Ticks seit der letzten Annäherung (für die Neuplanung der Route). */
    public int stalled(long tick) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, tick - since));
    }
}
