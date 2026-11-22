package com.blackn0va.baumeister.town;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Tagesablauf nach Weltzeit (Vertrag v008, AK-23). Weltzeit 0 = 6 Uhr, 1000 Ticks = 1 Stunde. */
class DayPlanTest {

    @Test
    void worldTimeBecomesClockHour() {
        assertEquals(6, DayPlan.hour(0));
        assertEquals(12, DayPlan.hour(6000));
        assertEquals(0, DayPlan.hour(18000));
        assertEquals(5, DayPlan.hour(23999));
        // Mehrere Tage und negative Zeiten falten in den Tag.
        assertEquals(12, DayPlan.hour(24_000L * 7 + 6000));
        assertEquals(5, DayPlan.hour(-1));
    }

    @Test
    void phasesFollowTheDay() {
        assertEquals(DayPlan.Phase.MORNING, DayPlan.phase(0));
        assertEquals(DayPlan.Phase.WORK, DayPlan.phase(3000));
        assertEquals(DayPlan.Phase.NOON, DayPlan.phase(6000));
        assertEquals(DayPlan.Phase.AFTERNOON, DayPlan.phase(8000));
        assertEquals(DayPlan.Phase.EVENING, DayPlan.phase(11000));
        assertEquals(DayPlan.Phase.NIGHT, DayPlan.phase(13000));
        assertEquals(DayPlan.Phase.NIGHT, DayPlan.phase(23999));
    }

    @Test
    void eachPhaseHasItsGoal() {
        assertEquals(DayPlan.Goal.WORK, DayPlan.phase(1000).goal());
        assertEquals(DayPlan.Goal.PLAZA, DayPlan.phase(6500).goal());
        assertEquals(DayPlan.Goal.HOME, DayPlan.phase(11500).goal());
        assertEquals(DayPlan.Goal.SLEEP, DayPlan.phase(18000).goal());
    }
}
