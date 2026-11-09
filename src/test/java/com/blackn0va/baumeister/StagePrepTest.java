package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.CityPlanner.Cell;
import com.blackn0va.baumeister.osm.CityPlanner.Section;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Vorarbeit der Etappen im Hintergrund: Chunk-Reihenfolge ohne Ergebnisänderung, Chunk-Liste wie vorher. */
class StagePrepTest {

    /** Ein Abschnitt wie vom Planer: jede Stelle einmal, nach y sortiert, innerhalb der Schicht kreuz und quer. */
    private static List<Cell> scrambledSection(long seed, int x0, int z0, int size, int layers) {
        List<Cell> cells = new ArrayList<>();
        for (int y = 0; y < layers; y++) {
            List<Cell> layer = new ArrayList<>();
            for (int x = x0; x < x0 + size; x++) {
                for (int z = z0; z < z0 + size; z++) {
                    layer.add(new Cell(x, y, z, (x * 31 + z * 7 + y) % 5));
                }
            }
            Collections.shuffle(layer, new Random(seed + y));
            cells.addAll(layer);
        }
        return cells;
    }

    /** Welt nach dem Setzen ohne Physik: letzte Zelle je Stelle gewinnt. */
    private static Map<Long, Integer> apply(List<Cell> cells) {
        Map<Long, Integer> world = new HashMap<>();
        for (Cell c : cells) {
            world.put(((long) c.x() << 40) ^ ((long) (c.y() & 0xfffff) << 20) ^ (c.z() & 0xfffff), c.mat());
        }
        return world;
    }

    private static int chunkSwitches(List<Cell> cells, int baseX, int baseZ) {
        int switches = 0;
        long last = Long.MIN_VALUE;
        for (Cell c : cells) {
            long k = StagePrep.key((baseX + c.x()) >> 4, (baseZ + c.z()) >> 4);
            if (k != last) {
                switches++;
                last = k;
            }
        }
        return switches;
    }

    @Test
    void chunkOrderBuildsTheSameBlocksLayerByLayer() {
        List<Cell> cells = scrambledSection(7, -16, -16, 32, 6);
        List<Cell> ordered = StagePrep.chunkOrder(cells, 4096 + 5, 4096 - 3);
        assertEquals(apply(cells), apply(ordered), "gleiche Blöcke an gleichen Stellen");
        assertEquals(cells.size(), ordered.size());
        for (int i = 1; i < ordered.size(); i++) {
            assertTrue(ordered.get(i - 1).y() <= ordered.get(i).y(), "von unten nach oben bleibt");
        }
    }

    @Test
    void chunkOrderTouchesEachChunkOncePerLayer() {
        // Gemessen-gleiche Lage wie der Testlauf: Abschnitt 32 × 32, Basis nicht auf Chunkgrenze → 9 Chunks je Schicht.
        int baseX = 4096 + 5;
        int baseZ = 4096 - 3;
        List<Cell> cells = scrambledSection(11, -16, -16, 32, 4);
        List<Cell> ordered = StagePrep.chunkOrder(cells, baseX, baseZ);
        int before = chunkSwitches(cells, baseX, baseZ);
        int after = chunkSwitches(ordered, baseX, baseZ);
        assertEquals(4 * 9, after, "je Schicht jeder der 9 Chunks genau einmal");
        assertTrue(before > 10 * after, "vorher kreuz und quer: " + before);
    }

    @Test
    void chunkOrderIsStableForDuplicatePositions() {
        // Planer liefert jede Stelle einmal; doppelte Stellen dürften trotzdem ihre Reihenfolge nicht tauschen.
        List<Cell> cells = List.of(new Cell(3, 0, 3, 1), new Cell(40, 0, 3, 2), new Cell(3, 0, 3, 3));
        List<Cell> ordered = StagePrep.chunkOrder(cells, 0, 0);
        assertEquals(3, ordered.get(1).mat(), "die spätere Zelle derselben Stelle bleibt später");
        assertEquals(apply(cells), apply(ordered));
    }

    @Test
    void chunkListMatchesTheBoundingBoxesInSectionOrder() {
        int baseX = 100;
        int baseZ = -37;
        Section a = new Section("a", 0, 0, scrambledSection(1, 0, 0, 20, 1));
        Section b = new Section("b", 0, 0, scrambledSection(2, -40, 10, 8, 2), true);
        long[] got = StagePrep.chunks(List.of(StagePrep.box(a.cells()), StagePrep.box(b.cells())), baseX, baseZ);
        LinkedHashSet<Long> want = new LinkedHashSet<>();
        for (Section s : List.of(a, b)) {
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (Cell c : s.cells()) {
                minX = Math.min(minX, c.x());
                maxX = Math.max(maxX, c.x());
                minZ = Math.min(minZ, c.z());
                maxZ = Math.max(maxZ, c.z());
            }
            for (int cx = (baseX + minX) >> 4; cx <= (baseX + maxX) >> 4; cx++) {
                for (int cz = (baseZ + minZ) >> 4; cz <= (baseZ + maxZ) >> 4; cz++) {
                    want.add(StagePrep.key(cx, cz));
                }
            }
        }
        assertArrayEquals(want.stream().mapToLong(Long::longValue).toArray(), got);
    }

    /**
     * Nachbau der Arbeiterschleife von CityJob.work mit dem echten StagePrep.takeNext: Jeder Arbeiter nimmt den
     * nächsten freien Abschnitt und setzt je Tick eine zufällige Zahl Zellen – wie der Takt mit Zeitgrenze.
     * Rückgabe: Welt; ticks[0] = Zahl der simulierten Ticks.
     */
    private static Map<Long, Integer> buildWithWorkers(List<Section> plan, int workers, long seed, int[] ticks) {
        Random rnd = new Random(seed);
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        List<int[]> boxes = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            queue.add(i);
            boxes.add(StagePrep.box(plan.get(i).cells()));
        }
        int[] task = new int[workers];
        int[] cursor = new int[workers];
        java.util.Arrays.fill(task, -1);
        Map<Long, Integer> world = new HashMap<>();
        boolean busy = true;
        while (busy) {
            busy = false;
            ticks[0]++;
            for (int w = 0; w < workers; w++) {
                if (task[w] < 0 || cursor[w] >= plan.get(task[w]).cells().size()) {
                    if (queue.isEmpty()) {
                        task[w] = -1;
                        continue;
                    }
                    busy = true;
                    List<int[]> running = new ArrayList<>();
                    for (int o = 0; o < workers; o++) {
                        if (o != w && task[o] >= 0 && cursor[o] < plan.get(task[o]).cells().size()) {
                            running.add(boxes.get(task[o]));
                        }
                    }
                    Integer next = StagePrep.takeNext(queue, boxes::get, running);
                    if (next == null) {
                        task[w] = -1;
                        continue;
                    }
                    task[w] = next;
                    cursor[w] = 0;
                }
                busy = true;
                List<Cell> cells = plan.get(task[w]).cells();
                int end = Math.min(cells.size(), cursor[w] + 1 + rnd.nextInt(40));
                world.putAll(apply(cells.subList(cursor[w], end)));
                cursor[w] = end;
            }
        }
        return world;
    }

    /** Reihen von Häusern mit gemeinsamen Wänden (wie der Planer sie von der Mitte nach außen reiht), dazu Türme. */
    private static List<Section> terraces() {
        List<Section> plan = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            for (int i = 0; i < 12; i++) {
                List<Cell> house = new ArrayList<>();
                for (int y = 0; y < 4; y++) {
                    for (int x = i * 10; x <= i * 10 + 10; x++) {
                        for (int z = row * 20; z <= row * 20 + 8; z++) {
                            house.add(new Cell(x, y, z, (i + row) % 4));
                        }
                    }
                }
                plan.add(new Section("Haus " + row + "/" + i, 0, 0, house, true));
            }
        }
        for (int i = 0; i < 4; i++) {
            List<Cell> tower = new ArrayList<>();
            for (int y = 0; y < 8; y++) {
                for (int x = i * 30 + 3; x <= i * 30 + 6; x++) {
                    for (int z = 2; z <= 5; z++) {
                        tower.add(new Cell(x, y, z, 7));
                    }
                }
            }
            plan.add(new Section("Turm " + i, 0, 0, tower, true));
        }
        return plan;
    }

    @Test
    void sharedWallsFollowThePlanOrderWithManyHelpers() {
        // Gemessen 2026-10-29: Mit 4 Helfern hing die Farbe geteilter Wände vom Takt ab (alter Stand: 4 457 Blöcke
        // Unterschied zum Bau nacheinander).
        List<Section> plan = terraces();
        Map<Long, Integer> sequential = new HashMap<>();
        for (Section s : plan) {
            sequential.putAll(apply(s.cells()));
        }
        for (long seed = 0; seed < 40; seed++) {
            assertEquals(sequential, buildWithWorkers(plan, 5, seed, new int[1]), "Takt " + seed + " weicht vom Plan ab");
        }
    }

    @Test
    void independentSectionIsPulledForward() {
        // Haus 1 teilt eine Wand mit dem laufenden Haus 0 und wartet; Haus 2 in der nächsten Reihe darf vor.
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>(List.of(1, 2));
        List<int[]> boxes = List.of(new int[] {0, 0, 10, 8}, new int[] {10, 0, 20, 8}, new int[] {0, 20, 10, 28});
        assertEquals(2, StagePrep.takeNext(queue, boxes::get, List.of(boxes.get(0))));
        assertEquals(List.of(1), List.copyOf(queue));
        assertEquals(null, StagePrep.takeNext(queue, boxes::get, List.of(boxes.get(0))), "Haus 1 wartet weiter");
    }

    @Test
    void laterSectionNeverOvertakesAnEarlierNeighbour() {
        // Haus 2 berührt das noch wartende Haus 1 – es darf nicht vor, sonst überschriebe Haus 1 danach die Wand.
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>(List.of(1, 2));
        List<int[]> boxes = List.of(new int[] {0, 0, 10, 8}, new int[] {10, 0, 20, 8}, new int[] {20, 0, 30, 8});
        assertEquals(null, StagePrep.takeNext(queue, boxes::get, List.of(boxes.get(0))));
    }

    @Test
    void helpersStayBusyDespiteSharedWalls() {
        // Nur den vordersten nehmen kostete gemessen ein Drittel Tempo. Mit Vorziehen arbeiten die Helfer an anderen
        // Reihen weiter: deutlich weniger Ticks als mit einem einzigen Arbeiter.
        List<Section> plan = terraces();
        int[] one = new int[1];
        int[] five = new int[1];
        buildWithWorkers(plan, 1, 3, one);
        buildWithWorkers(plan, 5, 3, five);
        assertTrue(five[0] * 2 < one[0], "Ticks mit 5 Arbeitern " + five[0] + ", mit einem " + one[0]);
    }

    @Test
    void sectionsWithoutSharedAreaRunInParallel() {
        int[] a = {0, 0, 9, 9};
        assertTrue(StagePrep.blocked(a, List.of(new int[] {9, 9, 20, 20})), "eine gemeinsame Ecke reicht");
        assertTrue(!StagePrep.blocked(a, List.of(new int[] {10, 0, 20, 9}, new int[] {0, 10, 9, 20})));
        assertTrue(!StagePrep.blocked(null, List.of(a)), "leerer Abschnitt wartet auf nichts");
    }

    @Test
    void emptySectionOrdersNoChunks() {
        // Ein leerer Abschnitt hätte mit dem alten Rechteck (MAX_VALUE..MIN_VALUE + Basis) überlaufen können.
        List<int[]> boxes = new ArrayList<>();
        boxes.add(StagePrep.box(List.of()));
        assertEquals(0, StagePrep.chunks(boxes, 4096, 4096).length);
    }

    @Test
    void prepareKeepsPlanTotalsAndOrdersEverySection() {
        List<Section> sections = List.of(new Section("a", 0, 0, scrambledSection(3, 0, 0, 24, 2)),
                new Section("b", 0, 0, scrambledSection(4, 30, 30, 10, 3), true));
        CityPlanner.Plan p = new CityPlanner.Plan(List.of("stone"), sections, 1, 0, 24 * 24 * 2 + 300, 0, 0, 40, 40,
                List.of());
        StagePrep.Prepared r = StagePrep.prepare(p, 4101, 4093);
        assertEquals(p.blocks(), r.plan().blocks());
        assertEquals(2, r.plan().sections().size());
        assertTrue(r.plan().sections().get(1).building(), "Gebäude bleibt Gebäude (TerrainFit braucht das)");
        Set<Long> chunks = new HashSet<>();
        for (long k : r.chunks()) {
            assertTrue(chunks.add(k), "jeder Chunk nur einmal");
        }
        for (int i = 0; i < 2; i++) {
            assertEquals(apply(sections.get(i).cells()), apply(r.plan().sections().get(i).cells()));
        }
    }

    @Test
    void preparedPlanKeepsTheStreetSigns() {
        // Zusammenführung Tempo/Stadt 2026-10-29: StagePrep baute den Plan über den Konstruktor ohne signs neu, die
        // Schilder standen danach im Spiel ohne Text.
        CityPlanner.Sign sign = new CityPlanner.Sign(3, 1, 4, "birch_sign[rotation=4]", List.of("", "Mannheimer Str.",
                "", ""));
        Section s = new Section("a", 0, 0, scrambledSection(1, 0, 0, 8, 1));
        CityPlanner.Plan p = new CityPlanner.Plan(List.of("stone"), List.of(s), 0, 1, s.cells().size(), 0, 0, 7, 7,
                List.of(sign));
        assertEquals(List.of(sign), StagePrep.prepare(p, 0, 0).plan().signs());
    }
}
