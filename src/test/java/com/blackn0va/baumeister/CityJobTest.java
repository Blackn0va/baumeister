package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.CityPlanner.Cell;
import com.blackn0va.baumeister.osm.CityPlanner.Section;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.AbstractList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/** Abschlussregel des Stadt- und Weltbaus und der Start ohne Arbeit im Hauptthread (der Rest braucht einen Server). */
class CityJobTest {

    @Test
    void runWithSkippedStagesIsNotComplete() {
        // Prüfbefund 2026-10-28: Vorher galt „Fertig!“ auch mit übersprungenen Etappen als fertig, und der Weltbau
        // merkte sich die Kachel samt Lücke für immer.
        assertTrue(CityJob.complete(0));
        assertFalse(CityJob.complete(1), "eine übersprungene Etappe – Kachel darf nicht als fertig gelten");
        assertFalse(CityJob.complete(16));
    }

    /** Zellen eines Abschnitts, die sich merken, aus welchem Thread sie gelesen werden. */
    private static final class WatchedCells extends AbstractList<Cell> {
        final Set<Thread> readers = ConcurrentHashMap.newKeySet();
        private final int n;

        WatchedCells(int n) {
            this.n = n;
        }

        @Override
        public Cell get(int i) {
            readers.add(Thread.currentThread());
            return new Cell(i % 37, i / 1369, i / 37 % 37, 0);
        }

        @Override
        public int size() {
            return n;
        }
    }

    private static World fakeWorld() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getMaxHeight" -> 320;
                    case "getMinHeight" -> -64;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Testwelt";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static StageSource oneStage() {
        return new StageSource() {
            @Override
            public String place() {
                return "Testort";
            }

            @Override
            public int count() {
                return 1;
            }

            @Override
            public CompletableFuture<CityPlanner.Plan> plan(int k) {
                return CompletableFuture.failedFuture(new IllegalStateException("nur Etappe 0"));
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    void constructorLeavesStageZeroToTheBackground() throws Exception {
        // Prüfbefund 2026-10-29: Der Konstruktor läuft im Hauptthread (/bm stadt los, jede Weltbau-Kachel) und
        // sortierte Etappe 0 dort selbst – 110–150 ms für 237 154 Zellen auf dem Laptop, unsichtbar im Ende-Log.
        // Jetzt darf er die Zellen der ersten Etappe nicht anfassen; das tut StagePrep im Hintergrund.
        WatchedCells cells = new WatchedCells(50_000);
        CityPlanner.Plan first = new CityPlanner.Plan(List.of(), List.of(new Section("Haus", 0, 0, cells, true)), 1,
                0, cells.size(), 0, 0, 36, 36, List.of());
        CityJob job = new CityJob(null, null, null, null, "Test", new Location(fakeWorld(), 100, 64, -200),
                oneStage(), first, 2000, 0, 8, 25, 150, null, j -> { });
        Thread caller = Thread.currentThread();
        assertFalse(cells.readers.contains(caller), "Etappe 0 im Thread des Konstruktors (Hauptthread) sortiert");
        Field f = CityJob.class.getDeclaredField("loading");
        f.setAccessible(true);
        CompletableFuture<?> loading = (CompletableFuture<?>) f.get(job);
        assertNotNull(loading, "Etappe 0 muss als Vorbereitung im Hintergrund anstehen");
        assertNotNull(loading.get(30, TimeUnit.SECONDS));
        assertFalse(cells.readers.isEmpty(), "die Vorbereitung hat die Zellen gelesen");
        assertFalse(cells.readers.contains(caller));
    }

    @Test
    void resumedJobContinuesAtItsStartStage() throws Exception {
        // Nach einem Neustart setzt der Stadtbau bei der ersten nicht ganz fertigen Etappe fort (CityResume). Diese
        // Etappe lädt gerade – sie, nicht die übernächste, ist der Fortsetzungspunkt.
        StageSource ten = new StageSource() {
            @Override
            public String place() {
                return "Testort";
            }

            @Override
            public int count() {
                return 10;
            }

            @Override
            public CompletableFuture<CityPlanner.Plan> plan(int k) {
                return new CompletableFuture<>();  // bleibt offen: nichts wird übernommen
            }

            @Override
            public void close() {
            }
        };
        CityPlanner.Plan first = new CityPlanner.Plan(List.of(), List.of(), 0, 0, 0, 0, 0, 0, 0, List.of());
        CityJob job = new CityJob(null, null, null, null, "Test", new Location(fakeWorld(), 0, 64, 0), ten, first, 7,
                2000, 0, 8, 25, 150, null, null, j -> { });
        assertEquals(7, job.resumeStage());
    }
}
