package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Rückgängig mit Chunk-Disziplin (dev01 2026-10-30: ungebremstes synchrones Laden aus restoreSpool war der Einstieg in
 * die PlotSquared-Rekursion, Server per Watchdog aus).
 */
class RestoreJobTest {

    private static final long MS = 1_000_000L;

    /** Welt-Ersatz: zählt Tickets je Tick und jeden Block, der in einen ungeladenen Chunk gesetzt würde. */
    private static final class FakeChunks implements RestoreJob.Chunks {
        final Set<Long> loaded = new HashSet<>();
        final Map<Long, Integer> tickets = new HashMap<>();
        int heldNow;
        int thisTick;
        int maxPerTick;
        int intoUnloaded;

        static long key(int cx, int cz) {
            return ((long) cx << 32) ^ (cz & 0xffffffffL);
        }

        @Override
        public boolean loaded(int cx, int cz) {
            return loaded.contains(key(cx, cz));
        }

        @Override
        public void hold(int cx, int cz) {
            loaded.add(key(cx, cz));  // wie Spigot: das Ticket lädt sofort
            tickets.merge(key(cx, cz), 1, Integer::sum);
            heldNow++;
            thisTick++;
            maxPerTick = Math.max(maxPerTick, thisTick);
        }

        @Override
        public void release(int cx, int cz) {
            loaded.remove(key(cx, cz));
            heldNow--;
        }
    }

    /** n Blöcke in einer Reihe entlang x – je 16 ein neuer Chunk. */
    private static RestoreJob.Part row(int n, FakeChunks chunks, long[] clock, long nanosPerSet) {
        return new RestoreJob.Part() {
            @Override
            public int size() {
                return n;
            }

            @Override
            public int x(int i) {
                return i;
            }

            @Override
            public int z(int i) {
                return 0;
            }

            @Override
            public boolean set(int i) {
                if (!chunks.loaded(i >> 4, 0)) {
                    chunks.intoUnloaded++;
                }
                clock[0] += nanosPerSet;
                return true;
            }
        };
    }

    private static RestoreJob.Source once(RestoreJob.Part p) {
        boolean[] given = {false};
        return () -> {
            if (given[0]) {
                return null;
            }
            given[0] = true;
            return p;
        };
    }

    @Test
    void neverSetsIntoAnUnloadedChunkAndLoadsAtMostTheAllowedChunksPerTick() throws Exception {
        FakeChunks chunks = new FakeChunks();
        long[] clock = {0};
        RestoreJob job = new RestoreJob(once(row(4000, chunks, clock, 0)), chunks, new LoadControl(150 * MS, 8),
                25 * MS, 20_000, 900, () -> clock[0]);
        int ticks = 0;
        RestoreJob.Step step;
        do {
            chunks.thisTick = 0;
            step = job.tick(50 * MS);
            ticks++;
        } while (step != RestoreJob.Step.DONE && ticks < 10_000);
        assertEquals(RestoreJob.Step.DONE, step);
        assertEquals(4000, job.restored());
        assertEquals(0, chunks.intoUnloaded, "nie in einen ungeladenen Chunk gesetzt (sonst lädt Spigot synchron)");
        assertTrue(chunks.maxPerTick <= 8, "höchstens neue-chunks-pro-tick je Tick: " + chunks.maxPerTick);
        assertEquals(250, chunks.tickets.size(), "jeder der 250 Chunks einmal bestellt");
        job.releaseAll();
        assertEquals(0, chunks.heldNow, "am Ende alle Tickets frei");
    }

    @Test
    void looksAtTheClockEveryFewBlocks() throws Exception {
        FakeChunks chunks = new FakeChunks();
        for (int cx = 0; cx < 250; cx++) {
            chunks.loaded.add(FakeChunks.key(cx, 0));
        }
        long[] clock = {0};
        // 1 ms je Block (z. B. Physik, Lichtberechnung): Nach 25 ms muss der Tick enden, nicht erst nach 20 000.
        RestoreJob job = new RestoreJob(once(row(4000, chunks, clock, MS)), chunks, new LoadControl(150 * MS, 8),
                25 * MS, 20_000, 900, () -> clock[0]);
        job.tick(50 * MS);
        assertEquals(RestoreJob.CLOCK_EVERY, job.restored());
    }

    @Test
    void doesNothingWhileTheServerLagsBehind() throws Exception {
        FakeChunks chunks = new FakeChunks();
        long[] clock = {0};
        RestoreJob job = new RestoreJob(once(row(100, chunks, clock, 0)), chunks, new LoadControl(150 * MS, 8),
                25 * MS, 20_000, 900, () -> clock[0]);
        assertEquals(RestoreJob.Step.WAITING, job.tick(600 * MS));
        assertEquals(0, job.restored());
        assertEquals(0, job.tickets());
    }

    @Test
    void ordersAgainWhenSomeoneElseReleasedTheTicket() throws Exception {
        FakeChunks chunks = new FakeChunks();
        long[] clock = {0};
        // 200 Blöcke in EINEM Chunk, 1 ms je Block: Der erste Tick bestellt den Chunk und endet nach 64 Blöcken mitten
        // darin. Dann gibt ein anderer Job desselben Plugins das Ticket frei (je Plugin und Chunk nur eines).
        RestoreJob.Part oneChunk = new RestoreJob.Part() {
            @Override
            public int size() {
                return 200;
            }

            @Override
            public int x(int i) {
                return i % 16;
            }

            @Override
            public int z(int i) {
                return 0;
            }

            @Override
            public boolean set(int i) {
                if (!chunks.loaded(0, 0)) {
                    chunks.intoUnloaded++;
                }
                clock[0] += MS;
                return true;
            }
        };
        RestoreJob job = new RestoreJob(once(oneChunk), chunks, new LoadControl(150 * MS, 8), 25 * MS, 20_000, 900,
                () -> clock[0]);
        job.tick(50 * MS);
        assertEquals(RestoreJob.CLOCK_EVERY, job.restored());
        chunks.loaded.clear();
        RestoreJob.Step step;
        int ticks = 0;
        do {
            step = job.tick(50 * MS);
            ticks++;
        } while (step != RestoreJob.Step.DONE && ticks < 100);
        assertEquals(RestoreJob.Step.DONE, step, "nicht für immer auf einen gemerkten, aber entladenen Chunk warten");
        assertEquals(0, chunks.intoUnloaded);
    }
}
