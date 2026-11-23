package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.blackn0va.baumeister.town.work.BuildSettings;

import org.junit.jupiter.api.Test;

/**
 * Bewohner-Bau im BuildService (Vertrag v013, AK-45), soweit ohne Server prüfbar: Wann ein Bewohner gar nicht erst
 * anfängt (fail-closed, Spieler gehen vor), und dass ConfigCheck dieselben Grenzen kennt wie BuildSettings.
 */
class TownBuildServiceTest {

    @Test
    void residentsOnlyBuildWhenNobodyElseNeedsTheBuilderAndSomeoneIsAccountable() {
        assertNull(BuildService.townRefusal(null, false, false, false, true, true, true));
        assertEquals("niemand_berechtigt", BuildService.townRefusal(null, false, false, false, true, false, true));
        assertEquals("bewohner_weg", BuildService.townRefusal(null, false, false, false, true, true, false));
        assertEquals("stadt_oder_weltbau", BuildService.townRefusal("Stadtbau", false, false, false, true, true,
                true));
        assertEquals("baumeister_beschäftigt", BuildService.townRefusal(null, true, false, false, true, true, true));
        assertEquals("baumeister_beschäftigt", BuildService.townRefusal(null, false, true, false, true, true, true));
        assertEquals("baumeister_beschäftigt", BuildService.townRefusal(null, false, false, true, true, true, true));
        assertEquals("warteschlange", BuildService.townRefusal(null, false, false, false, false, true, true));
    }

    @Test
    void configCheckKnowsTheBuildLimits() {
        ConfigCheck.Range perField = ConfigCheck.RANGES.get(BuildSettings.KEY_PER_FIELD);
        ConfigCheck.Range pause = ConfigCheck.RANGES.get(BuildSettings.KEY_PAUSE);
        assertEquals(BuildSettings.MIN_PER_FIELD, (int) perField.min());
        assertEquals(BuildSettings.MAX_PER_FIELD, (int) perField.max());
        assertEquals(BuildSettings.MIN_PAUSE, (int) pause.min());
        assertEquals(BuildSettings.MAX_PAUSE, (int) pause.max());
    }
}
