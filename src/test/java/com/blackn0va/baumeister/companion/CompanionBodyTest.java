package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.companion.CompanionBody.Pose;
import com.blackn0va.baumeister.companion.CompanionBody.Situation;

import org.junit.jupiter.api.Test;

/** Körpersprache: Pose spiegelt den Spieler, Fackel im Dunkeln. */
class CompanionBodyTest {

    private static Situation s(boolean sneak, boolean swim, boolean glide, boolean sleep, boolean wet, boolean moving,
            boolean hover) {
        return new Situation(sneak, swim, glide, sleep, wet, moving, hover);
    }

    @Test
    void mirrorsThePlayer() {
        assertEquals(Pose.STANDING, CompanionBody.pose(s(false, false, false, false, false, false, false)));
        assertEquals(Pose.SNEAKING, CompanionBody.pose(s(true, false, false, false, false, true, false)));
        assertEquals(Pose.SLEEPING, CompanionBody.pose(s(false, false, false, true, false, false, false)));
        assertEquals(Pose.FALL_FLYING, CompanionBody.pose(s(false, false, true, false, false, true, true)));
        assertEquals(Pose.SWIMMING, CompanionBody.pose(s(false, true, false, false, true, true, false)));
    }

    @Test
    void doesNotLieDownWhileWalkingOrSwimWhileStanding() {
        assertEquals(Pose.STANDING, CompanionBody.pose(s(false, false, false, true, false, true, false)),
                "läuft noch zum Bett");
        assertEquals(Pose.STANDING, CompanionBody.pose(s(false, false, false, false, true, false, false)),
                "steht im seichten Wasser");
        assertEquals(Pose.STANDING, CompanionBody.pose(s(false, false, true, false, false, true, false)),
                "Spieler gleitet, Vera läuft am Boden");
    }

    @Test
    void holdsATorchOnlyInTheDark() {
        assertTrue(CompanionBody.torch(3, Pose.STANDING, false));
        assertFalse(CompanionBody.torch(12, Pose.STANDING, false));
        assertFalse(CompanionBody.torch(0, Pose.SLEEPING, false));
        assertFalse(CompanionBody.torch(0, Pose.SWIMMING, true));
    }
}
