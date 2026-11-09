package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** Fertiger KI-Plan wartet auf das Ende des Stadt-/Weltbaus statt verworfen zu werden (2026-11-01). */
class DeferredStartTest {

    @Test
    void waitsWhileBusyAndStartsOnceFree() {
        DeferredStart d = new DeferredStart();
        AtomicInteger started = new AtomicInteger();
        assertTrue(d.offer("Blackkn0va", started::incrementAndGet));
        assertFalse(d.runIfFree(false), "Stadtbau läuft noch");
        assertEquals(0, started.get());
        assertTrue(d.runIfFree(true));
        assertEquals(1, started.get());
        assertFalse(d.runIfFree(true), "nur einmal");
        assertEquals(1, started.get());
    }

    @Test
    void onlyOneSlotAndCancelEmptiesIt() {
        DeferredStart d = new DeferredStart();
        assertTrue(d.offer("a", () -> { }));
        assertFalse(d.offer("b", () -> { }), "zweiter Plan bekommt keinen Platz");
        assertEquals("a", d.owner());
        assertTrue(d.cancel());
        assertFalse(d.waiting());
        assertTrue(d.offer("b", () -> { }));
    }

    @Test
    void slotIsFreedEvenIfTheStartThrows() {
        DeferredStart d = new DeferredStart();
        d.offer("a", () -> {
            throw new IllegalStateException("kaputt");
        });
        assertThrows(IllegalStateException.class, () -> d.runIfFree(true));
        assertFalse(d.waiting(), "sonst versuchte der Timer es jede Sekunde neu");
    }
}
