package com.blackn0va.baumeister.town;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Stadtleben: ohne KI keine Bewohner; an und Host da → N Bewohner; Host weg → keine (Vertrag v008, AK-22). Spielt
 * HostWatch und Population mit einer Uhr durch, wie TownLife sie im Takt benutzt.
 */
class PopulationTest {

    private static final DayPlan.Phase DAY = DayPlan.Phase.WORK;

    @Test
    void defaultIsOffAndNobodyAppears() {
        TownSettings s = TownSettings.defaults();
        assertFalse(s.enabled());
        assertEquals(0, Population.target(s.enabled(), true, DAY, s.count(), 3));
    }

    @Test
    void withoutAnswerFromTheHostNobodyAppears() {
        HostWatch h = new HostWatch();
        // Vor der ersten Antwort gilt der Host als weg (fail-closed).
        assertFalse(h.up());
        assertEquals(0, Population.target(true, h.up(), DAY, 4, 1));
        assertEquals(Population.Leave.REMOVE_NOW, Population.leave(true, h.up(), DAY));
    }

    @Test
    void onAndHostUpGivesTheConfiguredCount() {
        HostWatch h = new HostWatch();
        assertTrue(h.due(0));
        h.result(true, 10);
        assertEquals(4, Population.target(true, h.up(), DAY, 4, 1));
        assertEquals(12, Population.target(true, h.up(), DAY, 12, 2));
        assertEquals(Population.Leave.STAY, Population.leave(true, h.up(), DAY));
    }

    @Test
    void hostGoneMeansEverybodyLeavesWithinSixtySeconds() {
        HostWatch h = new HostWatch();
        assertTrue(h.due(0));
        h.result(true, 10);
        // Host fällt bei t=1 s aus. Die nächste Prüfung muss so früh kommen, dass ihr Ergebnis (2 × 5 s Frist in
        // LlmClient.status) noch vor 60 s da ist.
        long next = -1;
        for (long t = 1_000; t <= 60_000; t += 50) {
            if (h.due(t)) {
                next = t;
                break;
            }
        }
        assertTrue(next >= 0 && next + 10_000 <= 61_000, "nächste Prüfung erst bei " + next + " ms");
        h.result(false, next + 10_000);
        assertEquals(0, Population.target(true, h.up(), DAY, 4, 1));
        assertEquals(Population.Leave.REMOVE_NOW, Population.leave(true, h.up(), DAY));
    }

    @Test
    void failedResidentRequestCountsAsHostGoneAtOnce() {
        HostWatch h = new HostWatch();
        h.due(0);
        h.result(true, 0);
        h.down(5_000);
        assertFalse(h.up());
    }

    @Test
    void healthIsNotAskedMoreOftenThanTheIntervalNorTwiceAtOnce() {
        HostWatch h = new HostWatch();
        assertTrue(h.due(0));
        assertFalse(h.due(HostWatch.INTERVAL_MS), "läuft noch – keine zweite Prüfung");
        h.result(true, 100);
        assertFalse(h.due(HostWatch.INTERVAL_MS - 1));
        assertTrue(h.due(HostWatch.INTERVAL_MS));
        assertTrue(HostWatch.INTERVAL_MS <= 60_000);
    }

    @Test
    void switchingOffRemovesEverybody() {
        assertEquals(Population.Leave.REMOVE_NOW, Population.leave(false, true, DAY));
        assertEquals(0, Population.target(false, true, DAY, 4, 1));
    }

    @Test
    void atNightNobodyNewAndTheOthersGoHome() {
        assertEquals(0, Population.target(true, true, DayPlan.Phase.NIGHT, 4, 1));
        assertEquals(Population.Leave.GO_HOME, Population.leave(true, true, DayPlan.Phase.NIGHT));
        assertTrue(Population.NIGHT_LEAVE_MS <= 60_000);
    }

    @Test
    void noPlayersNoResidents() {
        assertEquals(0, Population.target(true, true, DAY, 4, 0));
    }

    @Test
    void residentsAppearOneAtATime() {
        assertTrue(Population.maySpawn(0, 4, 100, Long.MIN_VALUE / 2));
        assertFalse(Population.maySpawn(1, 4, 100 + Population.SPAWN_GAP_TICKS - 1, 100));
        assertTrue(Population.maySpawn(1, 4, 100 + Population.SPAWN_GAP_TICKS, 100));
        assertFalse(Population.maySpawn(4, 4, 10_000, 100));
    }
}
