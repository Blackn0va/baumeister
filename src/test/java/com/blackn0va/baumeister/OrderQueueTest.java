package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Warteschlange: Platz, Grenzen, Reihenfolge, Abbruch nur eigener Aufträge. */
class OrderQueueTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void secondOrderIsQueuedWithItsPositionAndComesOutInOrder() {
        OrderQueue<String> q = new OrderQueue<>(10, 2);
        assertEquals(1, q.offer(A, "Anna", "Haus", "a1"));
        assertEquals(2, q.offer(B, "Bert", "Turm", "b1"));
        assertEquals(3, q.offer(A, "Anna", "Brunnen", "a2"));
        assertEquals(1, q.positionOf(A));
        assertEquals(2, q.positionOf(B));
        assertEquals("a1", q.poll().order());
        assertEquals("b1", q.poll().order());
        assertEquals("a2", q.poll().order());
        assertNull(q.poll());
        assertEquals(0, q.positionOf(A));
    }

    @Test
    void perPlayerAndTotalLimitsRefuse() {
        OrderQueue<String> q = new OrderQueue<>(3, 2);
        assertEquals(1, q.offer(A, "Anna", "1", "1"));
        assertEquals(2, q.offer(A, "Anna", "2", "2"));
        assertEquals(OrderQueue.TOO_MANY, q.offer(A, "Anna", "3", "3"), "dritter Auftrag desselben Spielers");
        assertEquals(3, q.offer(B, "Bert", "1", "b"));
        assertEquals(OrderQueue.FULL, q.offer(null, "CONSOLE", "k", "k"));
        assertEquals(3, q.size());
    }

    @Test
    void consoleCountsAsOneOwner() {
        OrderQueue<String> q = new OrderQueue<>(10, 1);
        assertEquals(1, q.offer(null, "CONSOLE", "k1", "k1"));
        assertEquals(OrderQueue.TOO_MANY, q.offer(null, "Rcon", "k2", "k2"));
    }

    @Test
    void playersCancelOnlyTheirOwnOrdersAdminsAny() {
        OrderQueue<String> q = new OrderQueue<>(10, 3);
        q.offer(A, "Anna", "a1", "a1");
        q.offer(B, "Bert", "b1", "b1");
        q.offer(A, "Anna", "a2", "a2");
        assertNull(q.cancelAt(2, A, false), "Anna darf Berts Auftrag nicht abbrechen");
        assertEquals(3, q.size());
        assertEquals("a2", q.cancelAt(3, A, false).order());
        assertNull(q.cancelAt(7, A, true));
        assertEquals("b1", q.cancelAt(2, A, true).order(), "Admin darf fremde abbrechen");
        q.offer(B, "Bert", "b2", "b2");
        List<OrderQueue.Entry<String>> gone = q.cancelAll(A);
        assertEquals(List.of("a1"), gone.stream().map(OrderQueue.Entry::order).toList());
        assertEquals(List.of("b2"), q.entries().stream().map(OrderQueue.Entry::order).toList());
    }

    @Test
    void listingMarksOwnOrdersAndCutsLongQueues() {
        OrderQueue<String> q = new OrderQueue<>(20, 20);
        assertEquals(List.of("Die Warteschlange ist leer."), q.describe(A, 10));
        for (int i = 1; i <= 12; i++) {
            q.offer(i % 2 == 0 ? A : B, i % 2 == 0 ? "Anna" : "Bert", "Auftrag " + i, "x" + i);
        }
        List<String> lines = q.describe(A, 10);
        assertEquals("1. Bert: Auftrag 1", lines.get(0));
        assertEquals("2. Anna (du): Auftrag 2", lines.get(1));
        assertEquals("… und 2 weitere.", lines.get(10));
        assertEquals(12, lines.size());
    }

    @Test
    void maxZeroSwitchesTheQueueOff() {
        OrderQueue<String> q = new OrderQueue<>(0, 2);
        assertFalse(q.enabled());
        assertEquals(OrderQueue.FULL, q.offer(A, "Anna", "x", "x"));
        q.limits(5, 2);
        assertTrue(q.enabled());
    }

    @Test
    void takeOverKeepsOrderAfterReload() {
        OrderQueue<String> old = new OrderQueue<>(10, 2);
        old.offer(A, "Anna", "a", "a");
        old.offer(B, "Bert", "b", "b");
        OrderQueue<String> fresh = new OrderQueue<>(10, 2);
        fresh.takeOver(old);
        assertTrue(old.isEmpty());
        assertEquals(List.of("a", "b"), fresh.entries().stream().map(OrderQueue.Entry::order).toList());
    }
}
