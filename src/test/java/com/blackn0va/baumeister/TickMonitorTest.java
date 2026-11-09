package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** /bm leistung: TPS, Lücken und Rechenzeit aus gestellten Ticks. */
class TickMonitorTest {

    private static final long MS = 1_000_000L;

    /** n Ticks im Abstand gapMs, je cpuMs Rechenzeit, ab Zeitpunkt t (ns); liefert den neuen Zeitpunkt. */
    private static long run(TickMonitor m, long t, long[] cpu, int n, long gapMs, long cpuMs, boolean players) {
        for (int i = 0; i < n; i++) {
            t += gapMs * MS;
            cpu[0] += cpuMs * MS;
            m.sample(t, cpu[0], players);
        }
        return t;
    }

    @Test
    void healthyServerShowsTwentyTpsAndItsRealWork() {
        TickMonitor m = new TickMonitor();
        long[] cpu = {0};
        long t = run(m, 0, cpu, 1201, 50, 12, true);
        TickMonitor.Stats s = m.stats(t);
        assertEquals(1200, s.ticks());
        assertEquals(20.0, s.tps(), 1e-9);
        assertEquals(50.0, s.gapMaxMs(), 1e-9);
        assertEquals(12.0, s.cpuMeanMs(), 1e-9);
        assertEquals(0, s.over100());
    }

    @Test
    void onlyTheLastMinuteCounts() {
        TickMonitor m = new TickMonitor();
        long[] cpu = {0};
        long t = run(m, 0, cpu, 11, 2_000, 1, true);   // ein schlimmer Anfang mit 2-s-Ticks
        t = run(m, t, cpu, 1201, 50, 5, true);          // dann gut eine ruhige Minute
        TickMonitor.Stats s = m.stats(t);
        assertEquals(0, s.over1000(), "die 2-s-Lücken liegen mehr als 60 s zurück");
        assertEquals(20.0, s.tps(), 1e-9);
    }

    @Test
    void lagSpikesAppearAsGapsAndLowerTps() {
        TickMonitor m = new TickMonitor();
        long[] cpu = {0};
        long t = run(m, 0, cpu, 1, 50, 1, true);
        t = run(m, t, cpu, 100, 50, 10, true);
        t = run(m, t, cpu, 10, 150, 140, true);
        t = run(m, t, cpu, 1, 1_200, 30, true);   // Chunk-Erzeugung: lange Lücke, wenig eigene Rechenzeit
        TickMonitor.Stats s = m.stats(t);
        assertEquals(111, s.ticks());
        assertEquals(11, s.over100());
        assertEquals(1, s.over1000());
        assertEquals(1200.0, s.gapMaxMs(), 1e-9);
        assertEquals(111 * 1e9 / ((100 * 50 + 10 * 150 + 1200) * MS), s.tps(), 1e-9);
        assertEquals(140.0, s.cpuMaxMs(), 1e-9, "Rechenzeit misst die Arbeit, nicht das Warten");
    }

    @Test
    void pauseOfAnEmptyServerIsNoGap() {
        // dev01: pause-when-empty-seconds=60 – ohne Spieler tickt nichts, beim Wiederkommen liegen Minuten dazwischen.
        TickMonitor m = new TickMonitor();
        long[] cpu = {0};
        long t = run(m, 0, cpu, 100, 50, 5, true);
        t = run(m, t, cpu, 1200, 50, 5, false);   // 60 s ohne Spieler, dann hält der Server an
        t = run(m, t, cpu, 1, 600_000, 5, true);
        t = run(m, t, cpu, 100, 50, 5, true);
        TickMonitor.Stats s = m.stats(t);
        assertEquals(0, s.over1000(), "Pause als Lücke gezählt");
        assertEquals(1, s.pauses());
        assertEquals(20.0, s.tps(), 1e-9);
    }

    @Test
    void stallShortlyAfterTheLastPlayerLeftIsAGap() {
        // Der Server hält erst nach pause-when-empty-seconds (60 s) an. Ein Hänger 5 s nach dem letzten Spieler –
        // Weltbau oder bauenbei über die Konsole – ist ein echter Hänger. Vorher galt jeder Abstand über 1 s nach
        // einem Tick ohne Spieler als Pause und verschwand aus den Lücken (Prüfbefund 2026-10-29).
        TickMonitor m = new TickMonitor();
        long[] cpu = {0};
        long t = run(m, 0, cpu, 100, 50, 5, true);
        t = run(m, t, cpu, 100, 50, 5, false);    // 5 s ohne Spieler
        t = run(m, t, cpu, 1, 2_000, 5, false);   // 2 s Hänger
        TickMonitor.Stats s = m.stats(t);
        assertEquals(1, s.over1000(), "Hänger als Pause verbucht");
        assertEquals(0, s.pauses());
    }

    @Test
    void gcPausesIncludeTheConcurrentCycleBean() {
        // Ab JDK 20 zählt „G1 Concurrent GC“ die Stop-the-World-Pausen Remark und Cleanup (gemessen mit JDK 21.0.12:
        // -Xlog:gc zeigt 4 Pausen Remark/Cleanup, die Bean count=4). Vorher wurde sie übersprungen.
        long[] t = TickMonitor.gcTotals(List.of(new FakeGc("G1 Young Generation", 10, 200),
                new FakeGc("G1 Concurrent GC", 4, 30), new FakeGc("G1 Old Generation", 0, 0),
                new FakeGc("unbekannt", -1, -1)));
        assertArrayEquals(new long[] {14, 230}, t);
    }

    /** Gestellte Bean; -1 = „nicht verfügbar“ wie im JDK. */
    private record FakeGc(String getName, long getCollectionCount, long getCollectionTime)
            implements java.lang.management.GarbageCollectorMXBean {

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public String[] getMemoryPoolNames() {
            return new String[0];
        }

        @Override
        public javax.management.ObjectName getObjectName() {
            return null;
        }
    }

    @Test
    void stallWithPlayersOnlineIsAGap() {
        TickMonitor m = new TickMonitor();
        long[] cpu = {0};
        long t = run(m, 0, cpu, 10, 50, 5, true);
        t = run(m, t, cpu, 1, 21_000, 5, true);   // 21 s Stillstand wie beim ersten Dorf auf dev01
        assertEquals(1, m.stats(t).over1000());
        assertEquals(0, m.stats(t).pauses());
    }

    @Test
    void tpsNeverExceedsTwentyWhenSpigotCatchesUp() {
        TickMonitor m = new TickMonitor();
        long[] cpu = {0};
        long t = run(m, 0, cpu, 10, 50, 1, true);
        t = run(m, t, cpu, 1, 1_500, 1, true);     // langsamer Tick …
        t = run(m, t, cpu, 100, 1, 1, true);       // … danach holt der Server Ticks ohne Pause nach
        t = run(m, t, cpu, 1197, 50, 1, true);
        t = run(m, t, cpu, 1, 51, 1, true);        // das Fenster schneidet den langsamen Tick gerade ab
        TickMonitor.Stats s = m.stats(t);
        assertEquals(0, s.over1000(), "der langsame Tick liegt außerhalb des Fensters");
        assertEquals(20.0, s.tps(), 1e-9, "1 298 Ticks in 60 s wären 21,6 TPS");
    }

    @Test
    void withoutThreadClockOnlyTheWorkIsUnknown() {
        TickMonitor m = new TickMonitor();
        long t = 0;
        for (int i = 0; i < 30; i++) {
            t += 50 * MS;
            m.sample(t, -1, true);
        }
        TickMonitor.Stats s = m.stats(t);
        assertEquals(-1.0, s.cpuMeanMs());
        assertEquals(20.0, s.tps(), 1e-9);
    }

    @Test
    void gcDeltaCoversTheLastMinute() {
        TickMonitor m = new TickMonitor();
        assertArrayEquals(new long[] {-1, -1}, m.gcDelta());
        for (int i = 0; i <= 90; i++) {
            m.gcSample(i * 1_000 * MS, i * 2L, i * 10L);
        }
        assertArrayEquals(new long[] {120, 600}, m.gcDelta());
    }

    @Test
    void reportLinesNameTheNumbers() {
        TickMonitor m = new TickMonitor();
        long[] cpu = {0};
        long t = run(m, 0, cpu, 1201, 50, 25, true);
        List<String> lines = PerformanceReport.lines(m.stats(t), m.measuring(t), new long[] {3, 42},
                3_000L * 1024 * 1024, 5_120L * 1024 * 1024, 5_120L * 1024 * 1024, 2_500, 2_389, 3_100, 812,
                "world 441 · world_baumeister_erde 2500");
        assertTrue(lines.get(0).startsWith("Leistung (letzte 60 s, 1200 Ticks): TPS 20,0"), lines.get(0));
        assertTrue(lines.get(1).contains("Ø 25,0 ms") && lines.get(1).contains("Auslastung 50 %"), lines.get(1));
        assertTrue(lines.get(2).startsWith("Heap: 3000 / 5120 MB") && lines.get(2).contains("3, zusammen 42 ms"),
                lines.get(2));
        assertTrue(lines.get(3).contains("2500 von 2389 erlaubt (HeapGuard) – Weltbau wartet"), lines.get(3));
    }
}
