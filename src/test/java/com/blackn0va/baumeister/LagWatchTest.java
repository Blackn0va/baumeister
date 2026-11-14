package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Lag-Wächter (2026-11-14): was von selbst schaltet, je Sekunde; Eingabe eines Spielers macht die Messung ungültig. */
class LagWatchTest {

    private static final UUID W = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void boxContainsItsCornersAndNothingOutsideOrInAnotherWorld() {
        LagWatch.Box b = new LagWatch.Box(W, -2, 60, -2, 5, 70, 5);
        assertTrue(b.contains(W, -2, 60, -2));
        assertTrue(b.contains(W, 5, 70, 5));
        assertFalse(b.contains(W, 6, 65, 0));
        assertFalse(b.contains(W, 0, 59, 0));
        assertFalse(b.contains(UUID.randomUUID(), 0, 65, 0));
    }

    @Test
    void activityPerSecondCountsEverythingButInteractions() {
        LagWatch.Counter c = new LagWatch.Counter(new LagWatch.Box(W, 0, 0, 0, 1, 1, 1));
        c.redstone = 300;
        c.pistons = 40;
        c.dispensed = 20;
        c.hopper = 30;
        c.spawned = 10;
        LagWatch.Result r = c.result(8.0);
        assertEquals(400, r.activity());
        assertEquals(50.0, r.perSecond(), 1e-9);
        assertTrue(r.lagging(40));
        assertFalse(r.lagging(50), "genau an der Grenze ist noch keine Lag-Maschine");
        assertTrue(r.line().contains("je_s=50.0"), r.line());
    }

    @Test
    void aPlayerAtTheLeverIsInputNotLag() {
        LagWatch.Counter c = new LagWatch.Counter(new LagWatch.Box(W, 0, 0, 0, 1, 1, 1));
        c.redstone = 10_000;
        c.interactions = 1;
        assertFalse(c.result(8.0).lagging(1), "mit Eingabe ist die Messung kein Leerlauf");
        assertEquals(0.0, new LagWatch.Counter(c.box).result(0).perSecond());
    }
}
