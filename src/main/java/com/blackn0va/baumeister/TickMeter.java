package com.blackn0va.baumeister;

/**
 * Messwerte eines Baus über seine Ticks: längster eigener Tick und längste Lücke zwischen zwei eigenen Ticks (enthält
 * alles, was der Server dazwischen tut – Chunks, Licht, Speichern).
 *
 * Warum: CityJob loggt diese Werte seit 2026-10-28 am Ende; der Bau aus BuildJob (Schnellgang, Hochhaus) hatte keine –
 * „Glätte“ ließ sich dort nur von außen schätzen. Gleiche Schwellen wie CityJob (50/200 ms Tick, 100 ms/1 s Lücke),
 * damit die Ende-Zeilen vergleichbar bleiben.
 *
 * Threads: nur Hauptthread. Fehler: keine.
 */
final class TickMeter {

    private long lastStart;
    private long maxTick;
    private long maxGap;
    private int ticksOver50;
    private int ticksOver200;
    private int gapsOver100;
    private int gapsOver1000;
    private int ticks;
    /** Dauer der Prüfphase in ns, -1 = noch nicht zu Ende. */
    private long checkNanos = -1;

    TickMeter() {
    }

    private TickMeter(TickMeter o) {
        lastStart = o.lastStart;
        maxTick = o.maxTick;
        maxGap = o.maxGap;
        ticksOver50 = o.ticksOver50;
        ticksOver200 = o.ticksOver200;
        gapsOver100 = o.gapsOver100;
        gapsOver1000 = o.gapsOver1000;
        ticks = o.ticks;
        checkNanos = o.checkNanos;
    }

    /** Ein eigener Tick von start bis end (System.nanoTime). */
    void tick(long start, long end) {
        if (lastStart != 0) {
            long gap = start - lastStart;
            maxGap = Math.max(maxGap, gap);
            gapsOver100 += gap > 100_000_000L ? 1 : 0;
            gapsOver1000 += gap > 1_000_000_000L ? 1 : 0;
        }
        lastStart = start;
        long took = end - start;
        maxTick = Math.max(maxTick, took);
        ticksOver50 += took > 50_000_000L ? 1 : 0;
        ticksOver200 += took > 200_000_000L ? 1 : 0;
        ticks++;
    }

    /**
     * Prüfphase zu Ende – mit dem ersten Block oder durch Abbruch (Kiste, verweigert, offline). Nur der erste Aufruf
     * zählt: finish ruft es nach beginBuild noch einmal, das darf die Prüfdauer nicht zur Gesamtdauer machen.
     */
    void checkEnded(long startNanos, long endNanos) {
        if (checkNanos < 0) {
            checkNanos = Math.max(0, endNanos - startNanos);
        }
    }

    /** Prüfdauer in ms, -1 = Prüfung noch nicht zu Ende. */
    long checkMillis() {
        return checkNanos < 0 ? -1 : checkNanos / 1_000_000;
    }

    long maxTickMillis() {
        return maxTick / 1_000_000;
    }

    long maxGapMillis() {
        return maxGap / 1_000_000;
    }

    int gapsOver100() {
        return gapsOver100;
    }

    int ticks() {
        return ticks;
    }

    /** Für die Ende-Zeile im Log, Schlüssel wie bei CityJob. */
    String summary() {
        return String.format("ticks=%d längster_tick_ms=%d ticks_über_50ms=%d ticks_über_200ms=%d längste_lücke_ms=%d "
                + "lücken_über_100ms=%d lücken_über_1s=%d", ticks, maxTickMillis(), ticksOver50, ticksOver200,
                maxGapMillis(), gapsOver100, gapsOver1000);
    }

    /** Wie summary(), aber samt des noch laufenden Ticks ab runningStart – ohne ihn hier einzutragen. */
    String summaryWith(long runningStart, long now) {
        TickMeter m = new TickMeter(this);
        m.tick(runningStart, now);
        return m.summary();
    }
}
