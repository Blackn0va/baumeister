package com.blackn0va.baumeister;

import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Speicherwächter für große Bauaufträge in den hohen Welten: keine NEUE Weltbau-Kachel, solange dort zu viele Chunks
 * geladen sind.
 *
 * Warum: Ein Chunk der 4 064 Blöcke hohen Erd-/Geländewelt kostet rund achtmal so viel Speicher wie ein normaler
 * (gemessen 2026-10-28, Testserver: 16 × 16 festgehaltene Chunks samt Ladering +289 MB gegenüber +36 MB). Mit 3 GB
 * Heap endete eine Weltbau-Kachel neben ein paar hundert festgehaltenen Messpunkten im OutOfMemoryError beim
 * Speichern, mit beschädigten Chunks danach.
 *
 * Warum Chunks zählen statt den Heap zu messen: Die erste Fassung las die Belegung nach der letzten
 * Speicherbereinigung (MemoryPoolMXBean.getCollectionUsage). Auf einem ruhigen Server räumt G1 die alte Generation
 * aber nicht auf, solange kein Platz gebraucht wird – der Wert blieb 9 Minuten bei 2 274/3 072 MB stehen, obwohl die
 * Kachel längst entladen war, und der Weltbau stand still (gemessen 2026-10-29). System.gc() hilft nicht: dev01
 * startet mit -XX:+DisableExplicitGC. Geladene Chunks dagegen fallen sofort, wenn die Tickets weg sind.
 *
 * Threads: nur Hauptthread (World.getLoadedChunks).
 */
final class HeapGuard {

    /** Anteil des Heaps, den geladene hohe Chunks höchstens belegen sollen, bevor eine neue Kachel anfängt. */
    static final double SHARE = 0.35;
    /**
     * Speicher je geladenem (vollem) Chunk einer hohen Welt samt seinem Anteil am Ladering, gemessen 2026-10-28:
     * 16 × 16 Block → 289 MB (1,1 MB je vollem Chunk), Spielerbereich Sichtweite 10 → ~0,7 MB. Genommen: 0,75 MB.
     */
    static final long BYTES_PER_TALL_CHUNK = 768L * 1024;
    private static final int TALL = 384;

    private HeapGuard() {
    }

    /** Sind in den hohen Welten schon zu viele Chunks geladen? */
    static boolean tight() {
        return tight(tallLoadedChunks(), Runtime.getRuntime().maxMemory());
    }

    static boolean tight(int tallChunks, long maxHeap) {
        return tallChunks > budget(maxHeap);
    }

    /** Wie viele volle hohe Chunks der Heap verträgt (bei 3 GB ≈ 1 430, bei 8 GB ≈ 3 800). */
    static long budget(long maxHeap) {
        return (long) (maxHeap * SHARE / BYTES_PER_TALL_CHUNK);
    }

    static int tallLoadedChunks() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            if (w.getMaxHeight() - w.getMinHeight() > TALL) {
                n += w.getLoadedChunks().length;
            }
        }
        return n;
    }

    /** Für Meldungen: geladene hohe Chunks / erlaubt. */
    static String describe() {
        return tallLoadedChunks() + "/" + budget(Runtime.getRuntime().maxMemory()) + " hohe Chunks";
    }
}
