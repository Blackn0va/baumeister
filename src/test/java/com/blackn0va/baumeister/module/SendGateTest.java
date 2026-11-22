package com.blackn0va.baumeister.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Host-POST „bei Änderung und höchstens alle 60 s“ (Vertrag v012). */
class SendGateTest {

    private static final long S = 1_000_000_000L;

    @Test
    void firstStateGoesAtOnceThenAtMostEverySixtySeconds() {
        SendGate g = new SendGate();
        assertEquals("a", g.next("a", 0));
        g.done("a", true);
        assertNull(g.next("b", 30 * S), "Änderung nach 30 s schon gesendet");
        assertNull(g.next("c", 59 * S));
        assertEquals("c", g.next("c", 60 * S), "jüngster Stand nach 60 s");
        g.done("c", true);
    }

    @Test
    void unchangedStateIsNotResentBeforeTheRefresh() {
        SendGate g = new SendGate();
        g.next("a", 0);
        g.done("a", true);
        assertNull(g.next("a", 61 * S));
        assertNull(g.next("a", 9 * 60 * S));
        assertEquals("a", g.next("a", 10 * 60 * S), "Auffrischung für einen neu gestarteten Host");
    }

    @Test
    void failedSendIsRetriedAfterTheGapButNotBefore() {
        SendGate g = new SendGate();
        g.next("a", 0);
        g.done("a", false);
        assertNull(g.next("a", 10 * S));
        assertEquals("a", g.next("a", 60 * S), "nicht zugestellt = noch offen");
    }

    @Test
    void neverTwoAtOnce() {
        SendGate g = new SendGate();
        g.next("a", 0);
        assertNull(g.next("b", 120 * S), "zweite Sendung, während die erste läuft");
        g.done("a", true);
        assertEquals("b", g.next("b", 120 * S));
    }
}
