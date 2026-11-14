package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

/** 2026-11-14: Bauplatz laden, ohne den Server-Thread anzuhalten (bis 4,4 s gemessen). */
class ChunkPrefetchTest {

    /** Ersatz-Server: geladene Chunks, Tickets, ein Takt zum Weiterdrehen; paper = mit asynchronem Laden. */
    private static final class FakeHost implements ChunkPrefetch.Host {
        final Set<Long> loaded = new HashSet<>();
        final Set<Long> tickets = new HashSet<>();
        final List<Long> syncLoads = new ArrayList<>();
        final List<CompletableFuture<Void>> pending = new ArrayList<>();
        final List<BooleanSupplier> timers = new ArrayList<>();
        final List<Runnable> main = new ArrayList<>();
        final boolean paper;
        Set<Long> broken = Set.of();

        FakeHost(boolean paper) {
            this.paper = paper;
        }

        @Override
        public boolean loaded(int cx, int cz) {
            return loaded.contains(ChunkPrefetch.key(cx, cz));
        }

        @Override
        public CompletableFuture<?> loadAsync(int cx, int cz) {
            if (!paper) {
                return null;
            }
            // Wie Paper: Wenn der zurückgegebene Future fertig ist, ist der Chunk geladen.
            CompletableFuture<Void> f = new CompletableFuture<>();
            long k = ChunkPrefetch.key(cx, cz);
            pending.add(f);
            return f.thenRun(() -> loaded.add(k));
        }

        @Override
        public void loadNow(int cx, int cz) {
            long k = ChunkPrefetch.key(cx, cz);
            if (broken.contains(k)) {
                throw new IllegalStateException("Region kaputt");
            }
            syncLoads.add(k);
            loaded.add(k);
        }

        @Override
        public void hold(int cx, int cz) {
            tickets.add(ChunkPrefetch.key(cx, cz));
        }

        @Override
        public void release(int cx, int cz) {
            tickets.remove(ChunkPrefetch.key(cx, cz));
        }

        @Override
        public void mainThread(Runnable r) {
            main.add(r);
        }

        boolean serverThread = true;

        @Override
        public boolean onMainThread() {
            return serverThread;
        }

        @Override
        public void everyTick(BooleanSupplier task) {
            timers.add(task);
        }

        /** Einen Tick weiter; true, solange noch ein Takt läuft. */
        boolean tick() {
            timers.removeIf(t -> !t.getAsBoolean());
            List<Runnable> now = new ArrayList<>(main);
            main.clear();
            now.forEach(Runnable::run);
            return !timers.isEmpty();
        }
    }

    private static long[] box(int n) {
        long[] k = new long[n * n];
        int i = 0;
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                k[i++] = ChunkPrefetch.key(x - 2, -z);
            }
        }
        return k;
    }

    @Test
    void allLoadedRunsAtOnceInTheSameTick() {
        FakeHost host = new FakeHost(false);
        long[] keys = box(3);
        for (long k : keys) {
            host.loaded.add(k);
        }
        List<ChunkPrefetch> got = new ArrayList<>();
        int[] calls = {0};
        assertNull(ChunkPrefetch.ensure(host, keys, 2, p -> {
            calls[0]++;
            got.add(p);
        }));
        assertEquals(1, calls[0], "Normalfall (Spieler daneben): kein Umweg");
        assertNull(got.getFirst());
        assertTrue(host.timers.isEmpty());
    }

    @Test
    void spigotLoadsAtMostPerTickAndHoldsUntilRelease() {
        FakeHost host = new FakeHost(false);
        long[] keys = box(4);  // 16 Chunks
        host.loaded.add(keys[0]);
        ChunkPrefetch[] done = {null};
        ChunkPrefetch p = ChunkPrefetch.ensure(host, keys, 2, x -> done[0] = x);
        assertTrue(host.syncLoads.isEmpty(), "Nichts im Tick des Auftrags laden – genau das hielt den Server an");
        int ticks = 0;
        while (host.tick()) {
            ticks++;
            assertTrue(host.syncLoads.size() <= 2 * ticks, "höchstens 2 je Tick");
        }
        assertEquals(15, host.syncLoads.size(), "nur die fehlenden");
        assertEquals(7, ticks, "15 fehlende zu je 2: der letzte Tick lädt einen und läuft zu Ende");
        assertEquals(p, done[0]);
        assertEquals(15, host.tickets.size(), "gehalten, bis gelesen ist");
        p.release();
        p.release();
        assertTrue(host.tickets.isEmpty());
    }

    @Test
    void paperLoadsInTheBackgroundAndContinuesOnTheMainThread() {
        FakeHost host = new FakeHost(true);
        long[] keys = box(2);
        boolean[] done = {false};
        ChunkPrefetch.ensure(host, keys, 2, p -> done[0] = true);
        assertEquals(4, host.pending.size());
        assertTrue(host.syncLoads.isEmpty(), "Paper: kein synchrones Laden");
        host.tick();
        assertFalse(done[0], "erst, wenn alle da sind");
        host.pending.forEach(f -> f.complete(null));
        // Paper entlädt Chunks ohne Ticket gleich wieder (gemessen: 1 von 16 noch da) – sie müssen schon gehalten sein.
        assertEquals(4, host.tickets.size(), "beim Fertigwerden gehalten, nicht erst im nächsten Tick");
        host.loaded.clear();
        assertFalse(done[0], "Rückruf springt erst auf den Server-Thread");
        host.tick();
        assertTrue(done[0]);
        assertEquals(4, host.tickets.size());
    }

    @Test
    void aChunkThatWillNotLoadIsSkippedNotFatal() {
        FakeHost host = new FakeHost(false);
        long[] keys = box(2);
        host.broken = Set.of(keys[1]);
        boolean[] done = {false};
        ChunkPrefetch p = ChunkPrefetch.ensure(host, keys, 4, x -> done[0] = true);
        while (host.tick()) {
            // weiter
        }
        assertTrue(done[0], "fail-open: weiter ohne diesen Chunk, das Lesen lädt ihn wie früher");
        assertEquals(3, p.held());
    }
}
