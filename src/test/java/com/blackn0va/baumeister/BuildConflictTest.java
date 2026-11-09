package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Absturz-Audit 2026-10-30: Kein normaler Bau, solange Stadt- oder Weltbau läuft (Plugin-Tickets). */
class BuildConflictTest {

    @Test
    void freeWhenNeitherCityNorWorldBuildRuns() {
        assertNull(BuildService.conflictMessage(false, false));
    }

    @Test
    void blockedWhileCityBuildRuns() {
        String msg = BuildService.conflictMessage(true, false);
        assertNotNull(msg);
        assertTrue(msg.contains("Stadtbau"), msg);
    }

    @Test
    void blockedWhileWorldBuildRuns() {
        String msg = BuildService.conflictMessage(false, true);
        assertNotNull(msg);
        assertTrue(msg.contains("weltbau stop"), msg);
    }
}
