package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Fortschrittsbalken: wer ihn sieht, dass er dem Auftrag folgt und am Ende verschwindet. */
class ProgressBarsTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NEAR = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FAR = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID OTHER_WORLD = UUID.fromString("00000000-0000-0000-0000-000000000004");

    /** Mitschreibender Balken statt BossBar. */
    private static final class FakeBar implements ProgressBars.Bar {
        String title;
        double progress;
        Set<UUID> viewers = Set.of();
        boolean removed;

        @Override
        public void title(String t) {
            title = t;
        }

        @Override
        public void progress(double p) {
            progress = p;
        }

        @Override
        public void viewers(Set<UUID> players) {
            viewers = players;
        }

        @Override
        public void remove() {
            removed = true;
            viewers = Set.of();
        }
    }

    private final Map<String, FakeBar> made = new HashMap<>();
    private final ProgressBars bars = new ProgressBars((key, kind) -> {
        FakeBar b = new FakeBar();
        made.put(key, b);
        return b;
    }, 64);

    private static ProgressBars.Job build(double progress) {
        return new ProgressBars.Job("bau-1", ProgressBars.Kind.BUILD, "Baumeister baut", progress, OWNER, "world",
                100, 100);
    }

    private static List<ProgressBars.Viewer> online() {
        return List.of(new ProgressBars.Viewer(OWNER, "stadtwelt", 5000, 5000),
                new ProgressBars.Viewer(NEAR, "world", 140, 120),
                new ProgressBars.Viewer(FAR, "world", 300, 100),
                new ProgressBars.Viewer(OTHER_WORLD, "world_nether", 100, 100));
    }

    @Test
    void ownerSeesTheBarEverywhereOthersOnlyNearTheSite() {
        bars.update(List.of(build(0.25)), online());
        FakeBar b = made.get("bau-1");
        assertEquals(Set.of(OWNER, NEAR), b.viewers);
        assertEquals(0.25, b.progress, 1e-9);
        assertEquals("Baumeister baut", b.title);
    }

    @Test
    void barDisappearsWhenTheJobEnds() {
        bars.update(List.of(build(0.5)), online());
        FakeBar b = made.get("bau-1");
        assertFalse(b.removed);
        bars.update(List.of(), online());
        assertTrue(b.removed, "Balken steht nach dem Ende noch");
        assertTrue(b.viewers.isEmpty());
        assertEquals(0, bars.size());
        // Ein neuer Auftrag bekommt einen frischen Balken, keinen wiederverwendeten bei 100 %.
        bars.update(List.of(new ProgressBars.Job("bau-2", ProgressBars.Kind.BUILD, "neu", 0, OWNER, "world", 0, 0)),
                online());
        assertEquals(List.of("bau-2"), bars.keys());
    }

    @Test
    void barFollowsProgressAndViewersEachTick() {
        bars.update(List.of(build(0.1)), online());
        List<ProgressBars.Viewer> moved = new ArrayList<>(online());
        moved.set(1, new ProgressBars.Viewer(NEAR, "world", 400, 400));
        bars.update(List.of(build(0.7)), moved);
        FakeBar b = made.get("bau-1");
        assertEquals(0.7, b.progress, 1e-9);
        assertEquals(Set.of(OWNER), b.viewers, "wer weggeht, verliert den Balken");
        assertEquals(1, made.size(), "kein zweiter Balken für denselben Auftrag");
    }

    @Test
    void radiusZeroShowsOnlyTheOwnerAndConsoleJobsOnlyNearby() {
        assertEquals(Set.of(OWNER), ProgressBars.viewersOf(build(0), online(), 0));
        ProgressBars.Job console = new ProgressBars.Job("x", ProgressBars.Kind.CITY, "t", 0, null, "world", 100, 100);
        assertEquals(Set.of(NEAR), ProgressBars.viewersOf(console, online(), 64));
    }

    @Test
    void progressIsClampedAndCityEstimateStaysBelowFull() {
        assertEquals(0, ProgressBars.clamp(-1));
        assertEquals(0, ProgressBars.clamp(Double.NaN));
        assertEquals(1, ProgressBars.clamp(3));
        // 2 von 10 Etappen geplant (je 1000 Blöcke), 1500 verarbeitet → 1500 / 10 000.
        assertEquals(0.15, ProgressBars.cityFraction(1500, 2000, 2, 10), 1e-9);
        assertEquals(0.99, ProgressBars.cityFraction(50_000, 2000, 2, 10), 1e-9);
        assertEquals(0, ProgressBars.cityFraction(0, 0, 0, 10));
    }

    @Test
    void clearRemovesEveryBar() {
        bars.update(List.of(build(0.3), new ProgressBars.Job("stadt", ProgressBars.Kind.CITY, "Stadt", 0.1, null,
                "stadtwelt", 0, 0)), online());
        bars.clear();
        assertTrue(made.values().stream().allMatch(b -> b.removed));
        assertEquals(0, bars.size());
    }
}
