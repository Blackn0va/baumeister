package com.blackn0va.baumeister;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Misst Tick-Abstand und Rechenzeit des Hauptthreads der letzten Minute für /bm leistung.
 *
 * Warum selbst messen: Spigot (anders als Paper) hat keine API für TPS oder MSPT; der Wert steckt nur in NMS-Feldern
 * (recentTps, tickTimes), deren Namen sich mit jeder Fassung ändern können. Vanilla /tick query gibt es, aber seine
 * Antwort landet bei einem Plugin-Absender nicht. Gemessen wird deshalb jeden Tick mit einer eigenen Aufgabe:
 * - Tick-Abstand (Wanduhr): 50 ms, solange der Server mitkommt; darüber genau die Zeit, die ein Tick gebraucht hat.
 *   Daraus TPS und die „Lücken“ (> 100 ms, > 1 s) – dieselbe Größe, mit der die Lastregelung (LoadControl) bremst
 *   und damit die Stadtbauten auf dev01 bewertet.
 * - Rechenzeit des Hauptthreads (ThreadMXBean, CPU-Uhr des Threads): die Arbeit je Tick auch dann, wenn er unter
 *   50 ms bleibt. Sie ist KEINE volle MSPT: Warten auf Chunk-Erzeugung oder Platte zählt nicht mit. Genau deshalb
 *   stehen beide Werte nebeneinander – kleine Rechenzeit bei großen Lücken heißt „Hauptthread wartet“.
 * Kosten: je Tick eine nanoTime und eine Thread-CPU-Abfrage (unter Linux ein clock_gettime), also Mikrosekunden.
 *
 * Pausen: dev01 läuft mit pause-when-empty-seconds=60 – ohne Spieler tickt der Server nach einer Minute nicht mehr.
 * Ein langer Abstand nach mindestens PAUSE_AFTER_EMPTY_TICKS Ticks ohne Spieler ist deshalb keine Lücke, sondern
 * Pause, und zählt nicht (eigene Zahl).
 *
 * Threads: sample/stats nur auf dem Hauptthread (start() plant die Aufgabe dort). Fehler: keine; ohne
 * Thread-CPU-Uhr der JVM fehlt nur die Rechenzeit.
 */
final class TickMonitor {

    static final long WINDOW_NANOS = 60_000_000_000L;
    /** Abstand nach einer spielerlosen Zeit, ab dem es als Pause gilt statt als Lücke. */
    static final long PAUSE_NANOS = 1_000_000_000L;
    /**
     * So viele Ticks ohne Spieler müssen vorher gelaufen sein, damit ein langer Abstand als Pause zählt. Der Server
     * zählt wie Vanilla Ticks ohne Spieler und hält bei pause-when-empty-seconds × 20 an (dev01: 60 s = 1 200,
     * server.properties gelesen 2026-10-29); 1 100 lässt etwas Spiel. Vorher genügte EIN Tick ohne Spieler – ein
     * echter Hänger in der Minute nach dem letzten Spieler (Weltbau, bauenbei über die Konsole) verschwand dann aus
     * den Lücken (Prüfbefund 2026-10-29). Steht pause-when-empty-seconds unter 55, zählt eine echte Pause als Lücke –
     * lieber eine Lücke zu viel als ein Hänger zu wenig.
     */
    static final int PAUSE_AFTER_EMPTY_TICKS = 1_100;
    /** Platz für eine Minute mit 20 TPS und Reserve; ältere Werte fallen ohnehin aus dem Fenster. */
    static final int CAPACITY = 1_600;

    private final long[] stamp = new long[CAPACITY];
    private final long[] gap = new long[CAPACITY];
    private final long[] cpu = new long[CAPACITY];
    private int head;
    private int size;
    private long last = -1;
    private long lastCpu = -1;
    private boolean lastHadPlayers = true;
    /** Ticks in Folge ohne Spieler bis einschließlich des letzten. */
    private int emptyTicks;
    private int pauses;
    private long started = -1;

    /** Speicherbereinigung: eine Probe je Sekunde, Unterschied über die Minute. */
    private record GcSample(long stamp, long count, long millis) {
    }

    private final Deque<GcSample> gc = new ArrayDeque<>();
    private int ticksSinceGc;

    /**
     * Ergebnis über das Fenster. cpu* sind -1, wenn die JVM keine Thread-CPU-Uhr hat.
     *
     * @param covered abgedeckte Zeit in ns (Summe der Abstände)
     */
    record Stats(int ticks, double tps, double gapMeanMs, double gapP95Ms, double gapMaxMs, int over100, int over1000,
            double cpuMeanMs, double cpuP95Ms, double cpuMaxMs, long covered, int pauses) {
    }

    /** Ein Tick: jetzt (nanoTime), CPU-Zeit des Hauptthreads (ns, -1 = unbekannt), Spieler online? */
    void sample(long now, long cpuNow, boolean players) {
        if (started < 0) {
            started = now;
        }
        if (last >= 0) {
            long g = now - last;
            if (!lastHadPlayers && emptyTicks >= PAUSE_AFTER_EMPTY_TICKS && g > PAUSE_NANOS) {
                pauses++;
            } else {
                stamp[head] = now;
                gap[head] = g;
                cpu[head] = cpuNow >= 0 && lastCpu >= 0 ? cpuNow - lastCpu : -1;
                head = (head + 1) % CAPACITY;
                size = Math.min(CAPACITY, size + 1);
            }
        }
        last = now;
        lastCpu = cpuNow;
        lastHadPlayers = players;
        emptyTicks = players ? 0 : emptyTicks + 1;
    }

    /** Werte der letzten WINDOW_NANOS vor now. */
    Stats stats(long now) {
        long[] gaps = new long[size];
        long[] cpus = new long[size];
        int n = 0;
        int nc = 0;
        long sum = 0;
        long cpuSum = 0;
        int over100 = 0;
        int over1000 = 0;
        for (int i = 0; i < size; i++) {
            int idx = Math.floorMod(head - 1 - i, CAPACITY);
            if (now - stamp[idx] > WINDOW_NANOS) {
                break;
            }
            long g = gap[idx];
            gaps[n++] = g;
            sum += g;
            if (g > 100_000_000L) {
                over100++;
            }
            if (g > 1_000_000_000L) {
                over1000++;
            }
            if (cpu[idx] >= 0) {
                cpus[nc++] = cpu[idx];
                cpuSum += cpu[idx];
            }
        }
        if (n == 0) {
            return new Stats(0, 0, 0, 0, 0, 0, 0, -1, -1, -1, 0, pauses);
        }
        long[] g = Arrays.copyOf(gaps, n);
        Arrays.sort(g);
        // TPS aus der abgedeckten Zeit: 20 Abstände von 50 ms = 1 s = 20 TPS. Nie über 20 – Spigot holt nach einem
        // langsamen Tick verlorene Zeit nach, dann liegen kurze Abstände nebeneinander, die keine 25 TPS sind.
        double tps = Math.min(20.0, n * 1e9 / sum);
        double cpuMean = -1;
        double cpuP95 = -1;
        double cpuMax = -1;
        if (nc > 0) {
            long[] c = Arrays.copyOf(cpus, nc);
            Arrays.sort(c);
            cpuMean = cpuSum / (double) nc / 1e6;
            cpuP95 = percentile(c, 0.95) / 1e6;
            cpuMax = c[nc - 1] / 1e6;
        }
        return new Stats(n, tps, sum / (double) n / 1e6, percentile(g, 0.95) / 1e6, g[n - 1] / 1e6, over100,
                over1000, cpuMean, cpuP95, cpuMax, sum, pauses);
    }

    /** Wie lange schon gemessen wird (ns), für „erst seit 12 s“. */
    long measuring(long now) {
        return started < 0 ? 0 : now - started;
    }

    /** Nächster Rang (nearest rank) – bei 1 200 Werten genau genug und ohne Interpolation nachvollziehbar. */
    static long percentile(long[] sorted, double p) {
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    // ------------------------------------------------------------------ Speicherbereinigung

    void gcSample(long now, long count, long millis) {
        gc.addLast(new GcSample(now, count, millis));
        while (gc.size() > 1 && now - gc.peekFirst().stamp() > WINDOW_NANOS) {
            gc.removeFirst();
        }
    }

    /** {Anzahl, Millisekunden} der Speicherbereinigungen im Fenster; {-1, -1} ohne zwei Proben. */
    long[] gcDelta() {
        if (gc.size() < 2) {
            return new long[] {-1, -1};
        }
        GcSample a = gc.peekFirst();
        GcSample b = gc.peekLast();
        return new long[] {b.count() - a.count(), b.millis() - a.millis()};
    }

    /** {Anzahl, Millisekunden} über die GC-Beans; -1 (nicht verfügbar) zählt als 0. */
    static long[] gcTotals(List<GarbageCollectorMXBean> beans) {
        long count = 0;
        long millis = 0;
        for (GarbageCollectorMXBean b : beans) {
            // Alle Beans, auch „G1 Concurrent GC“: Ab JDK 20 zählt sie die Stop-the-World-Pausen Remark und Cleanup
            // eines Markierzyklus (gemessen mit JDK 21.0.12: -Xlog:gc zeigt 4 Pausen Remark/Cleanup, die Bean
            // count=4), nicht die Arbeit neben dem Spiel. Vorher wurde sie übersprungen und /bm leistung zählte zu
            // wenige Pausen – mit IHOP 15 % aus start.sh laufen solche Zyklen öfter. Wer sie wieder auslässt, macht
            // TickMonitorTest.gcPausesIncludeTheConcurrentCycleBean rot.
            count += Math.max(0, b.getCollectionCount());
            millis += Math.max(0, b.getCollectionTime());
        }
        return new long[] {count, millis};
    }

    // ------------------------------------------------------------------ Bukkit

    /** Plant die Messung jeden Tick auf dem Hauptthread; endet mit dem Plugin (Bukkit hebt die Aufgabe auf). */
    static TickMonitor start(Plugin plugin) {
        TickMonitor m = new TickMonitor();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        boolean cpuClock = threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled();
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.nanoTime();
            m.sample(now, cpuClock ? threads.getCurrentThreadCpuTime() : -1,
                    !Bukkit.getOnlinePlayers().isEmpty());
            if (++m.ticksSinceGc >= 20) {
                m.ticksSinceGc = 0;
                long[] gcNow = gcTotals(ManagementFactory.getGarbageCollectorMXBeans());
                m.gcSample(now, gcNow[0], gcNow[1]);
            }
        }, 1L, 1L);
        return m;
    }
}
