package com.blackn0va.baumeister;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.function.LongSupplier;

/**
 * Ein laufendes Rückgängig (BuildService.restore) mit derselben Chunk-Disziplin wie der Stadtbau.
 *
 * Vorher (bis 2026-10-30) setzte das Rückgängig jeden Block mit getBlockAt().setBlockData(). Lag er in einem
 * entladenen Chunk, lud Spigot ihn synchron mitten im Takt, ohne Rate, und 512 Blöcke liefen ohne Blick auf die Uhr.
 * dev01, 2026-10-30 16:52: Rückgängig über 4,5 Mio. Blöcke parallel zu einem neuen Stadtbau mit 16 Helfern – jedes
 * dieser Ladevorgänge lief durch PlotSquared verschachtelt ins Chunk-System (siehe ChunkLoadGuard), 7 670
 * Fehlerspuren, StackOverflowError, nach 60 s der Watchdog, Server aus.
 *
 * Jetzt wird ein Block nur in einem geladenen Chunk gesetzt. Fehlt der Chunk, holt ein Plugin-Ticket ihn – höchstens
 * so viele je Tick, wie LoadControl erlaubt (stadt.neue-chunks-pro-tick, stadt.lag-ms) –, sonst wartet der Cursor bis
 * zum nächsten Tick. Alle CLOCK_EVERY Blöcke ein Blick auf die Uhr, ein neuer Teil von der Platte nur mit Restzeit.
 * Wer die isChunkLoaded-Prüfung entfernt, lädt wieder ungebremst – RestoreJobTest wird rot.
 *
 * Threads: nur Hauptthread. Fehler: Ein unlesbarer Teil wirft IOException aus tick(); der Aufrufer bricht ab und gibt
 * mit releaseAll() die Tickets frei.
 */
final class RestoreJob {

    /** Wie CityJob: so oft wie nötig, so selten wie möglich auf die Uhr sehen. */
    static final int CLOCK_EVERY = 64;

    /** Blöcke, rückwärts gelesen; set() liefert false, wenn der Eintrag übersprungen wurde (unbekannter Zustand). */
    interface Part {
        int size();

        int x(int i);

        int z(int i);

        boolean set(int i);
    }

    /** Teile, neueste zuerst; null am Ende. */
    interface Source {
        Part next() throws IOException;
    }

    /** Die Welt, soweit das Rückgängig sie braucht – im Test ein Zähler. */
    interface Chunks {
        boolean loaded(int cx, int cz);

        void hold(int cx, int cz);

        void release(int cx, int cz);
    }

    enum Step { WORKING, WAITING, DONE }

    private final Source source;
    private final Chunks chunks;
    private final LoadControl load;
    private final long nanosPerTick;
    private final int maxBlocks;
    private final int maxHeld;
    private final LongSupplier clock;
    private final LinkedHashSet<Long> held = new LinkedHashSet<>();
    private Part part;
    private int cursor = -1;
    private long restored;
    private long tickets;
    private boolean done;

    RestoreJob(Source source, Chunks chunks, LoadControl load, long nanosPerTick, int maxBlocks, int maxHeld,
            LongSupplier clock) {
        this.source = source;
        this.chunks = chunks;
        this.load = load;
        this.nanosPerTick = nanosPerTick;
        this.maxBlocks = maxBlocks;
        this.maxHeld = maxHeld;
        this.clock = clock;
    }

    /** Ein Takt. gapNanos = Abstand zum vorigen Takt (Lastregelung wie im Stadtbau). */
    Step tick(long gapNanos) throws IOException {
        if (done) {
            return Step.DONE;
        }
        int allowed = load.onTick(gapNanos);
        if (allowed < 0) {
            return Step.WAITING;  // Server hinkt: diesen Tick nichts, er soll aufholen
        }
        long start = clock.getAsLong();
        long deadline = start + nanosPerTick;
        int chunkLoads = allowed;
        int budget = maxBlocks;
        int n = 0;
        while (budget > 0) {
            if (part == null || cursor < 0) {
                // Einen Teil (bis 50 000 Einträge, gzip) nur mit mindestens halber Restzeit lesen.
                if (part != null && clock.getAsLong() > start + nanosPerTick / 2) {
                    return Step.WORKING;
                }
                part = source.next();
                if (part == null) {
                    done = true;
                    return Step.DONE;
                }
                cursor = part.size() - 1;
                continue;
            }
            int cx = part.x(cursor) >> 4;
            int cz = part.z(cursor) >> 4;
            if (!chunks.loaded(cx, cz)) {
                if (chunkLoads <= 0) {
                    return Step.WAITING;  // Cursor bleibt stehen, nächster Tick lädt weiter
                }
                chunkLoads--;
                hold(cx, cz);
                if (!chunks.loaded(cx, cz)) {
                    return Step.WAITING;
                }
            }
            if (part.set(cursor)) {
                restored++;
            }
            cursor--;
            budget--;
            if (++n % CLOCK_EVERY == 0 && clock.getAsLong() > deadline) {
                break;
            }
        }
        return Step.WORKING;
    }

    private void hold(int cx, int cz) {
        long k = ((long) cx << 32) ^ (cz & 0xffffffffL);
        // Auch für einen schon gemerkten Chunk neu bestellen: Plugin-Tickets gibt es je Plugin und Chunk nur einmal,
        // ein anderer Job desselben Plugins kann es freigegeben haben – sonst wartete der Cursor hier für immer.
        boolean fresh = held.add(k);
        chunks.hold(cx, cz);
        tickets++;
        if (fresh && held.size() > maxHeld) {
            long old = held.removeFirst();
            chunks.release((int) (old >> 32), (int) old);
        }
    }

    /** Alle Tickets freigeben – am Ende, bei Abbruch und beim Herunterfahren. */
    void releaseAll() {
        for (long k : held) {
            chunks.release((int) (k >> 32), (int) k);
        }
        held.clear();
    }

    long restored() {
        return restored;
    }

    long tickets() {
        return tickets;
    }

    int held() {
        return held.size();
    }

    boolean done() {
        return done;
    }
}
