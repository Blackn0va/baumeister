package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Absturz-Audit 2026-10-30: Redstone auffrischen, solange die Baustelle ihre Chunk-Tickets noch hält. */
class BuildJobAfterBuildTest {

    private final List<String> calls = new ArrayList<>();
    private final List<Runnable> scheduled = new ArrayList<>();

    private void run(boolean placedAny, boolean enabled) {
        BuildJob.afterBuild(placedAny, enabled, scheduled::add, () -> calls.add("auffrischen"),
                () -> calls.add("freigeben"));
    }

    @Test
    void ticketsStayUntilRedstoneWasRefreshed() {
        run(true, true);
        assertEquals(List.of(), calls, "nichts sofort – die Tickets halten bis zum späteren Tick");
        scheduled.forEach(Runnable::run);
        assertEquals(List.of("auffrischen", "freigeben"), calls);
    }

    @Test
    void ticketsAreReleasedEvenIfRefreshThrows() {
        BuildJob.afterBuild(true, true, scheduled::add, () -> {
            throw new IllegalStateException("kaputt");
        }, () -> calls.add("freigeben"));
        assertThrows(IllegalStateException.class, () -> scheduled.forEach(Runnable::run));
        assertEquals(List.of("freigeben"), calls);
    }

    @Test
    void nothingPlacedOrShuttingDownReleasesAtOnce() {
        run(false, true);
        run(true, false);
        assertEquals(List.of("freigeben", "freigeben"), calls);
        assertEquals(0, scheduled.size(), "beim Herunterfahren kein späterer Tick");
    }
}
