package com.blackn0va.baumeister;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

/**
 * /bm leistung: was der Server in der letzten Minute geleistet hat und was gerade läuft – auf einen Blick, ohne
 * Konsole, Spark oder jcmd. Anlass: Auf dev01 (Celeron J4125, 4 Kerne) war jede Frage „läuft der Stadtbau zu
 * schnell?“ oder „reicht der Speicher für die Erdwelt?“ nur über das Log und RCON-Messproben zu beantworten.
 *
 * Nur baumeister.admin (BaumeisterCommand): Welt- und Auftragsdaten anderer Spieler gehen Spieler nichts an.
 *
 * Threads: nur Hauptthread (Welten, Chunks, Scheduler). Fehler: keine.
 */
final class PerformanceReport {

    private static final long MB = 1024L * 1024;

    private PerformanceReport() {
    }

    static void send(BaumeisterPlugin plugin, CommandSender sender, TickMonitor monitor, Set<String> restartPending) {
        long now = System.nanoTime();
        Runtime rt = Runtime.getRuntime();
        int tall = HeapGuard.tallLoadedChunks();
        int chunks = 0;
        int entities = 0;
        StringBuilder worlds = new StringBuilder();
        for (World w : Bukkit.getWorlds()) {
            int c = w.getLoadedChunks().length;
            chunks += c;
            entities += w.getEntities().size();
            if (c > 0) {
                worlds.append(worlds.isEmpty() ? "" : " · ").append(w.getName()).append(' ').append(c);
            }
        }
        for (String line : lines(monitor.stats(now), monitor.measuring(now), monitor.gcDelta(),
                rt.totalMemory() - rt.freeMemory(), rt.totalMemory(), rt.maxMemory(), tall,
                HeapGuard.budget(rt.maxMemory()), chunks, entities, worlds.toString())) {
            Texts.info(sender, line);
        }
        Texts.info(sender, "Bau: " + plugin.service().statusLine());
        if (plugin.city().running()) {
            plugin.city().control(sender, "fortschritt", null);
        }
        WorldBuildService weltbau = plugin.weltbau();
        if (weltbau != null) {
            // Dieselbe Zeile wie /bm weltbau status – Warteschlange von Hand, Spielernähe, laufende Kachel.
            weltbau.command(sender, "bm", new String[] {"weltbau", "status"});
        }
        Texts.info(sender, String.format(Locale.GERMANY, "Bukkit-Aufgaben: %d geplant, %d im Hintergrund aktiv.",
                Bukkit.getScheduler().getPendingTasks().size(), Bukkit.getScheduler().getActiveWorkers().size()));
        if (!restartPending.isEmpty()) {
            Texts.info(sender, "Wirkt erst nach Neustart: " + String.join(", ", restartPending) + ".");
        }
    }

    /** Die Messzeilen ohne Bukkit – testbar. */
    static List<String> lines(TickMonitor.Stats s, long measuringNanos, long[] gc, long used, long committed,
            long max, int tallChunks, long tallBudget, int chunks, int entities, String perWorld) {
        List<String> out = new ArrayList<>();
        if (s.ticks() == 0) {
            out.add("Leistung: noch keine Messwerte (die Messung läuft ab dem ersten Tick).");
        } else {
            long seconds = Math.min(60, Math.round(measuringNanos / 1e9));
            out.add(String.format(Locale.GERMANY,
                    "Leistung (letzte %d s, %d Ticks): TPS %.1f · Tick-Abstand Ø %.1f ms, 95 %% %.0f, max %.0f · "
                            + "Lücken > 100 ms: %d, > 1 s: %d%s",
                    seconds, s.ticks(), s.tps(), s.gapMeanMs(), s.gapP95Ms(), s.gapMaxMs(), s.over100(),
                    s.over1000(), s.pauses() > 0 ? " · Pausen ohne Spieler: " + s.pauses() : ""));
            out.add(s.cpuMeanMs() < 0 ? "Rechenzeit des Hauptthreads: nicht messbar (JVM ohne Thread-CPU-Uhr)."
                    : String.format(Locale.GERMANY,
                            "Rechenzeit Hauptthread je Tick (≈ MSPT ohne Warten auf Chunks/Platte): Ø %.1f ms, "
                                    + "95 %% %.1f, max %.0f · Auslastung %.0f %%",
                            s.cpuMeanMs(), s.cpuP95Ms(), s.cpuMaxMs(), Math.min(100.0, s.cpuMeanMs() / 50.0 * 100)));
        }
        out.add(String.format(Locale.GERMANY, "Heap: %d / %d MB belegt (Müll bis zur nächsten GC eingerechnet), "
                + "%d MB reserviert%s", used / MB, max / MB, committed / MB,
                gc[0] < 0 ? "" : String.format(Locale.GERMANY, " · GC-Pausen letzte Minute: %d, zusammen %d ms",
                        gc[0], gc[1])));
        out.add(String.format(Locale.GERMANY, "Chunks: %d geladen (%s), davon hohe Welten %d von %d erlaubt "
                + "(HeapGuard)%s · Objekte: %d", chunks, perWorld.isEmpty() ? "keine" : perWorld, tallChunks,
                tallBudget, tallChunks > tallBudget ? " – Weltbau wartet" : "", entities));
        return out;
    }
}
