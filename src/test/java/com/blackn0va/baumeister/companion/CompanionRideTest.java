package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Mitfahren im Boot, auf dem Kamel und dem Glücklichen Ghast – Plätze wie in Vanilla (2026-11-07). */
class CompanionRideTest {

    @Test
    void seatsFollowVanilla() {
        assertEquals(2, CompanionRide.seats("oak_boat"));
        assertEquals(2, CompanionRide.seats("bamboo_raft"));
        assertEquals(1, CompanionRide.seats("oak_chest_boat"), "Truhe nimmt den zweiten Platz");
        assertEquals(1, CompanionRide.seats("bamboo_chest_raft"));
        assertEquals(2, CompanionRide.seats("camel"));
        assertEquals(4, CompanionRide.seats("happy_ghast"));
        for (String one : new String[] {"minecart", "horse", "pig", "strider", "donkey"}) {
            assertEquals(1, CompanionRide.seats(one), one);
        }
    }

    @Test
    void boardsOnlyWithAFreeSeatAndWhenNear() {
        assertTrue(CompanionRide.board("oak_boat", 1, 3.0, false), "Spieler am Ruder, ein Platz frei");
        assertFalse(CompanionRide.board("oak_boat", 2, 3.0, false), "voll");
        assertFalse(CompanionRide.board("minecart", 1, 1.0, false), "Lore: nur ein Platz");
        assertFalse(CompanionRide.board("oak_boat", 1, 30.0, false), "zu weit – erst folgen");
        assertFalse(CompanionRide.board("oak_boat", 1, 3.0, true), "sitzt schon");
    }
}
