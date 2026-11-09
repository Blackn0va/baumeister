package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Wann die Weltbau-Automatik eine selbst gewählte Kachel fallen lässt (WorldBuildService.keepAutoTile). Anlass
 * 2026-10-31: „auto aus, nach München, auto an – nichts passiert“, die Grand-Canyon-Kachel stand noch an.
 */
class WorldBuildAutoTest {

    @Test
    void playerLeftTheTileBeforeItsMapWasFetched() {
        // Auto an, gescannt, kein Spieler mehr dort, nichts in Arbeit → fallen lassen, München kommt dran.
        assertFalse(WorldBuildService.keepAutoTile(true, true, true, false));
    }

    @Test
    void autoOffDropsATileThatIsOnlyChosen() {
        assertFalse(WorldBuildService.keepAutoTile(true, false, true, true));
        assertFalse(WorldBuildService.keepAutoTile(true, false, false, false));
    }

    @Test
    void workInProgressIsNeverDropped() {
        // Abruf läuft, Planung läuft oder Bau läuft: die Arbeit beim Kartendienst ist getan, also fertig machen.
        assertTrue(WorldBuildService.keepAutoTile(false, false, true, false));
        assertTrue(WorldBuildService.keepAutoTile(false, true, true, false));
    }

    @Test
    void tileStaysWhilePlayersAreNearOrNotYetLookedFor() {
        assertTrue(WorldBuildService.keepAutoTile(true, true, true, true));
        // Direkt nach „auto an“ ist noch nicht gescannt – nicht vorschnell verwerfen.
        assertTrue(WorldBuildService.keepAutoTile(true, true, false, false));
    }
}
