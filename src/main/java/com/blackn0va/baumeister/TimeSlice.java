package com.blackn0va.baumeister;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Arbeit in einem Tick nach Zeit statt nach fester Stückzahl: Schritte laufen, bis budgetNanos verbraucht sind –
 * mindestens min, höchstens max. Die Uhr wird nur alle CLOCK_EVERY Schritte gelesen.
 *
 * Warum (BuildJob, Prüfphase): Vorher prüfte der Bau fest 300 Aktionen je Tick. Ohne Spieler (Konsole, bauenbei)
 * ist eine Prüfung nur ein Namensvergleich (Behälter) – ein Hochhaus mit 31 111 Aktionen wartete so 104 Ticks (5,2 s)
 * vor dem ersten Block, obwohl jeder dieser Ticks fast leer war. Mit Spieler löst jede Prüfung zwei Ereignisse für
 * WorldGuard/PlotSquared aus; dort schützt die Zeitgrenze den Tick, die feste Zahl tat es nicht. Wer wieder eine feste
 * Zahl einsetzt, verschenkt die leeren Ticks oder überzieht die vollen.
 *
 * Uhr injizierbar (Tests). Threads: beliebig, ein Aufruf läuft im aufrufenden Thread. Fehler: keine eigenen.
 */
final class TimeSlice {

    static final int CLOCK_EVERY = 16;

    private TimeSlice() {
    }

    /**
     * step() so oft aufrufen, bis er false liefert (fertig oder abgebrochen), max erreicht ist oder – ab min Schritten –
     * die Zeit abgelaufen ist. Rückgabe: Zahl der ausgeführten Schritte (auch der letzte, der false lieferte).
     */
    static int run(LongSupplier clock, long budgetNanos, int min, int max, BooleanSupplier step) {
        long deadline = clock.getAsLong() + budgetNanos;
        int n = 0;
        while (n < max) {
            n++;
            if (!step.getAsBoolean()) {
                break;
            }
            if (n >= min && n % CLOCK_EVERY == 0 && clock.getAsLong() >= deadline) {
                break;
            }
        }
        return n;
    }
}
