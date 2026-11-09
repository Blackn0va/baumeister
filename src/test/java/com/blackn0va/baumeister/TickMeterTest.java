package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tick-Messwerte für das Ende-Log von BuildJob. */
class TickMeterTest {

    @Test
    void countsLongestTickAndGaps() {
        TickMeter m = new TickMeter();
        long t = 1_000_000_000L;
        m.tick(t, t + 5_000_000L);                         // 5 ms
        m.tick(t + 50_000_000L, t + 110_000_000L);         // Lücke 50 ms, Tick 60 ms
        m.tick(t + 1_300_000_000L, t + 1_301_000_000L);    // Lücke 1 250 ms
        assertEquals(3, m.ticks());
        assertEquals(60, m.maxTickMillis());
        assertEquals(1250, m.maxGapMillis());
        assertEquals(1, m.gapsOver100());
        String s = m.summary();
        assertTrue(s.contains("ticks_über_50ms=1") && s.contains("lücken_über_1s=1"), s);
    }

    @Test
    void firstTickHasNoGap() {
        TickMeter m = new TickMeter();
        m.tick(5_000_000_000L, 5_001_000_000L);
        assertEquals(0, m.maxGapMillis());
    }

    @Test
    void endLineIncludesTheTickThatIsStillRunning() {
        // Prüfbefund 2026-10-29: BuildJob.finish loggt mitten im Tick, meter.tick kommt erst danach. Ohne den laufenden
        // Tick fehlte in der Ende-Zeile gerade der letzte – oft der längste (Abbruch, letzter Abschnitt).
        TickMeter m = new TickMeter();
        long t = 1_000_000_000L;
        m.tick(t, t + 5_000_000L);
        String s = m.summaryWith(t + 50_000_000L, t + 130_000_000L);   // läuft seit 80 ms
        assertTrue(s.contains("ticks=2 ") && s.contains("längster_tick_ms=80"), s);
        assertEquals(1, m.ticks(), "der laufende Tick wird nicht doppelt gezählt, wenn tickGuarded ihn danach einträgt");
    }

    @Test
    void checkTimeIsSetOnceAlsoWhenAborted() {
        // Abbruch in der Prüfphase: Vorher blieb prüfung_ms=0 stehen, weil nur beginBuild den Wert setzte.
        TickMeter aborted = new TickMeter();
        assertEquals(-1, aborted.checkMillis());
        aborted.checkEnded(1_000_000_000L, 1_037_000_000L);
        assertEquals(37, aborted.checkMillis());
        // Nach dem Bau ruft finish es noch einmal – die Prüfdauer bleibt die der Prüfung.
        aborted.checkEnded(1_000_000_000L, 9_000_000_000L);
        assertEquals(37, aborted.checkMillis());
    }
}
