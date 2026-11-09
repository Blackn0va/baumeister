package com.blackn0va.baumeister.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** /kbau stop während der Planung verwirft den späten Plan (2026-11-01). */
class PlanTicketTest {

    @Test
    void issuedTicketIsCurrent() {
        PlanTicket t = new PlanTicket();
        assertTrue(t.isCurrent(t.issue()));
    }

    @Test
    void cancelInvalidatesTheRunningPlan() {
        PlanTicket t = new PlanTicket();
        int planned = t.issue();
        t.cancel();
        assertFalse(t.isCurrent(planned), "abgebrochener Plan darf nicht mehr bauen");
    }

    @Test
    void lateCallbackOfACancelledPlanCannotTakeOverANewOrder() {
        PlanTicket t = new PlanTicket();
        int old = t.issue();
        t.cancel();
        int fresh = t.issue();
        assertTrue(t.isCurrent(fresh));
        assertFalse(t.isCurrent(old));
    }
}
