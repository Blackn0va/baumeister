package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Wer Vera einmal gerufen hat, bekommt sie bei jedem Beitreten – bis „tschüss“ (Vorfall dev01 2026-11-05 17:27). */
class ComeBackListTest {

    @Test
    void companionReturnsOnEveryJoinNotJustTheFirst() {
        ComeBackList list = new ComeBackList();
        UUID a = UUID.randomUUID();
        assertTrue(list.summoned(a));
        // Beitreten, Neustart, Beitreten, Neustart …: jedes Mal kommt Vera.
        for (int join = 0; join < 3; join++) {
            list = ComeBackList.fromJson(list.toJson());
            assertTrue(list.wantsCompanion(a), "Beitreten " + join);
            assertFalse(list.summoned(a), "schon drauf – nichts zu speichern");
        }
    }

    @Test
    void goodbyeEndsIt() {
        ComeBackList list = new ComeBackList();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        list.summoned(a);
        list.summoned(b);
        assertTrue(list.dismissed(a));
        assertFalse(list.dismissed(a));
        ComeBackList back = ComeBackList.fromJson(list.toJson());
        assertFalse(back.wantsCompanion(a));
        assertTrue(back.wantsCompanion(b));
        assertEquals(1, back.size());
    }

    @Test
    void neverSummonedMeansNeverHeard() {
        assertFalse(ComeBackList.fromJson("[]").wantsCompanion(UUID.randomUUID()));
    }
}
