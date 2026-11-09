package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

/** Tickets erst nach dem asynchronen Laden (Paper), synchron wie bisher ohne (Spigot) – 2026-11-01. */
class ChunkHolderTest {

    /** Welt-Ersatz: zählt Tickets und hält offene asynchrone Bestellungen fest. */
    private static final class FakeChunks implements ChunkHolder.Chunks {
        final boolean async;
        final Set<Long> loaded = new HashSet<>();
        final List<Long> added = new ArrayList<>();
        final List<Long> removed = new ArrayList<>();
        final Map<Long, CompletableFuture<Object>> orders = new HashMap<>();

        FakeChunks(boolean async) {
            this.async = async;
        }

        @Override
        public boolean isLoaded(int cx, int cz) {
            return loaded.contains(ChunkHolder.key(cx, cz));
        }

        @Override
        public void addTicket(int cx, int cz) {
            added.add(ChunkHolder.key(cx, cz));
            loaded.add(ChunkHolder.key(cx, cz));  // synchrones Ticket lädt sofort (wie Spigot/Paper im Hauptthread)
        }

        @Override
        public void removeTicket(int cx, int cz) {
            removed.add(ChunkHolder.key(cx, cz));
        }

        @Override
        public CompletableFuture<?> loadAsync(int cx, int cz) {
            if (!async) {
                return null;
            }
            CompletableFuture<Object> f = new CompletableFuture<>();
            orders.put(ChunkHolder.key(cx, cz), f);
            return f;
        }

        void finish(int cx, int cz) {
            loaded.add(ChunkHolder.key(cx, cz));
            orders.remove(ChunkHolder.key(cx, cz)).complete(new Object());
        }
    }

    @Test
    void paperOrdersAsyncAndTicketsOnlyAfterTheChunkIsLoaded() {
        FakeChunks w = new FakeChunks(true);
        ChunkHolder h = new ChunkHolder(w, 10);
        h.hold(3, -4);
        assertTrue(w.added.isEmpty(), "kein Ticket, solange der Chunk noch geladen wird – sonst lädt es synchron");
        assertEquals(1, h.asyncOrders());
        assertTrue(h.contains(ChunkHolder.key(3, -4)), "gilt schon als gehalten, damit er nicht doppelt bestellt wird");
        h.hold(3, -4);
        assertEquals(1, w.orders.size(), "keine zweite Bestellung");
        w.finish(3, -4);
        assertEquals(List.of(ChunkHolder.key(3, -4)), w.added);
        assertEquals(1, h.tickets());
    }

    @Test
    void spigotKeepsTheSynchronousTicket() {
        FakeChunks w = new FakeChunks(false);
        ChunkHolder h = new ChunkHolder(w, 10);
        h.hold(1, 1);
        assertEquals(List.of(ChunkHolder.key(1, 1)), w.added);
        assertEquals(0, h.asyncOrders());
    }

    @Test
    void alreadyLoadedChunkGetsItsTicketAtOnce() {
        FakeChunks w = new FakeChunks(true);
        w.loaded.add(ChunkHolder.key(5, 5));
        ChunkHolder h = new ChunkHolder(w, 10);
        h.hold(5, 5);
        assertEquals(List.of(ChunkHolder.key(5, 5)), w.added);
        assertTrue(w.orders.isEmpty());
    }

    @Test
    void chunkEvictedBeforeItLoadedGetsNoLateTicket() {
        FakeChunks w = new FakeChunks(true);
        ChunkHolder h = new ChunkHolder(w, 2);
        h.hold(0, 0);
        h.hold(0, 1);
        h.hold(0, 2);  // 0,0 fällt raus, solange er noch geladen wird
        assertFalse(h.contains(ChunkHolder.key(0, 0)));
        w.finish(0, 0);
        assertFalse(w.added.contains(ChunkHolder.key(0, 0)), "sonst bliebe der Chunk ohne Freigabe für immer geladen");
    }

    @Test
    void lateResultAfterTheBuildEndedGetsNoTicket() {
        FakeChunks w = new FakeChunks(true);
        ChunkHolder h = new ChunkHolder(w, 10);
        h.hold(7, 7);
        h.releaseAll();
        w.finish(7, 7);
        assertTrue(w.added.isEmpty());
        h.hold(8, 8);
        assertEquals(1, h.asyncOrders(), "nach dem Ende nimmt der Halter nichts mehr an");
    }

    @Test
    void failedAsyncLoadFallsBackToTheTicket() {
        FakeChunks w = new FakeChunks(true);
        ChunkHolder h = new ChunkHolder(w, 10);
        h.hold(2, 2);
        w.orders.remove(ChunkHolder.key(2, 2)).completeExceptionally(new IllegalStateException("Test"));
        assertEquals(List.of(ChunkHolder.key(2, 2)), w.added);
        assertEquals(1, h.asyncFailures());
    }

    /** Paper-Signatur wie in der Paper-API: CompletableFuture<Chunk> getChunkAtAsync(int, int). */
    public static final class PaperLikeWorld {
        public CompletableFuture<Object> getChunkAtAsync(int x, int z) {
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void asyncMethodIsFoundOnlyWhereTheServerHasIt() {
        assertNotNull(ChunkHolder.asyncMethod(new PaperLikeWorld()));
        assertNull(ChunkHolder.asyncMethod(new Object()), "Spigot: keine Methode → synchroner Weg");
        assertNull(ChunkHolder.asyncMethod(null));
    }
}
