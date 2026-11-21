package com.blackn0va.baumeister.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.Orientation.Cardinal;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** Gedächtnis im Client (AK-5.1): Frist, Umkreis und Welt wie im Plugin, neue Bauwerke planen frisch. */
class ClientMemoryTest {

    private final Object world = new Object();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-21T10:00:00Z"));
    private final ClientMemory<String> memory = new ClientMemory<>(now::get);

    private void remember() {
        memory.remember(new ClientMemory.Entry<>(world, 100, 64, 100, Cardinal.NORTH, -3, 3, "{\"steps\":[]}",
                "bau ein Haus", now.get(), "zellen"));
    }

    @Test
    void sameRulesAsThePlugin() {
        assertEquals(Duration.ofHours(1), ClientMemory.TTL);
        assertEquals(48, ClientMemory.RADIUS);
    }

    @Test
    void followUpNearbyWithinTheHourFindsTheLastBuild() {
        remember();
        now.set(now.get().plus(Duration.ofMinutes(59)));
        var m = memory.recall(world, 120, 64, 130, "mach das Dach rot");
        assertTrue(m.isPresent());
        assertEquals(59, m.get().minutesAgo(now.get()));
    }

    @Test
    void afterTheHourTheFollowUpPlansFresh() {
        remember();
        now.set(now.get().plus(Duration.ofMinutes(61)));
        assertFalse(memory.recall(world, 100, 64, 100, "mach das Dach rot").isPresent());
    }

    @Test
    void anotherWorldNeverSeesTheBuild() {
        remember();
        assertFalse(memory.recall(new Object(), 100, 64, 100, "mach das Dach rot").isPresent(),
                "Nether/anderer Server: gleiche Koordinaten, anderer Bau");
        assertFalse(memory.recall(null, 100, 64, 100, "mach das Dach rot").isPresent());
    }

    @Test
    void walkingAwayBeyondTheRadiusPlansFresh() {
        remember();
        assertTrue(memory.recall(world, 148, 64, 100, "mach das Dach rot").isPresent(), "genau 48 zählt noch");
        assertFalse(memory.recall(world, 149, 64, 100, "mach das Dach rot").isPresent());
    }

    @Test
    void aNewStandaloneStructureIgnoresTheMemory() {
        remember();
        assertFalse(memory.recall(world, 100, 64, 100, "bau mir ein Haus").isPresent());
    }

    @Test
    void forgetClearsTheMemory() {
        remember();
        assertTrue(memory.forget());
        assertFalse(memory.recall(world, 100, 64, 100, "mach das Dach rot").isPresent());
        assertFalse(memory.forget());
    }
}
