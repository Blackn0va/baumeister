package com.blackn0va.baumeister;

/**
 * Lastregelung des Stadtbaus: wie viele noch nicht geladene Chunks je Tick neu geladen werden dürfen.
 *
 * AIMD wie bei TCP: Liegt zwischen zwei Ticks mehr als lagNanos, war der Server überlastet – die Rate sinkt auf DOWN
 * und dieser Tick baut nichts; sonst steigt sie um UP je Tick bis max.
 *
 * Erste Fassung (2026-10-28): Schwelle 75 ms, Halbierung, Untergrenze 0,05. Auf dev01 (Celeron) dauern Ticks im
 * normalen Betrieb oft knapp darüber – 1 032 Lücken über 100 ms in 10 min, längste 289 ms, keine über 1 s. Die Rate
 * fiel auf 0,31; gebaut wurde fast nur in schon geladenen Chunks rund um den Spieler, der Rest der Stadt wartete
 * („die Stadt wird nur im geladenen Bereich gebaut“). Jetzt: Schwelle 150 ms (stadt.lag-ms), Absenken auf 70 %,
 * nie unter einem Chunk alle vier Ticks. Wer die Schwelle wieder auf 75 ms senkt, legt den Stadtbau auf langsamen
 * Servern still.
 *
 * Threads: nur Hauptthread. Fehler: keine.
 */
final class LoadControl {

    static final double DOWN = 0.7;
    static final double UP = 0.05;
    static final double FLOOR = 0.25;

    private final long lagNanos;
    private final int max;
    private double rate = 1.0;
    private double credit;
    private int recoveries;

    LoadControl(long lagNanos, int max) {
        this.lagNanos = lagNanos;
        this.max = max;
    }

    /** -1 = Server hinkt, diesen Tick nichts tun; sonst so viele neue Chunks dürfen geladen werden. */
    int onTick(long gapNanos) {
        if (gapNanos > lagNanos) {
            rate = Math.max(FLOOR, rate * DOWN);
            recoveries++;
            return -1;
        }
        rate = Math.min(max, rate + UP);
        credit = Math.min(max, credit + rate);
        int allowed = (int) credit;
        credit -= allowed;
        return allowed;
    }

    double rate() {
        return rate;
    }

    int recoveries() {
        return recoveries;
    }
}
