package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.blackn0va.baumeister.plan.CompiledBuild.Action;
import com.blackn0va.baumeister.plan.CompiledBuild.Change;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Reihenfolge innerhalb einer Schicht (2026-11-15): Die Rastersuche muss Block für Block dieselbe Reihenfolge liefern
 * wie die alte Suche über die ganze Schicht – sie ist nur schneller (dev01 stand bei der riesigen Lobby zweimal über
 * 10 s in nearestNeighbour).
 */
class PlanCompilerOrderTest {

    private static final BlockSpec STONE = stone();

    private static BlockSpec stone() {
        try {
            return BlockSpec.parse("stone");
        } catch (PlanException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Action at(int x, int z) {
        return new Action(0, List.of(new Change(new Pos(x, 64, z), STONE, 0)));
    }

    /** n verschiedene Stellen um (ox, oz), Seitenlänge spread. */
    private static List<Action> scatter(Random r, int n, int spread, int ox, int oz) {
        Set<Long> used = new HashSet<>();
        List<Action> out = new ArrayList<>();
        while (out.size() < n) {
            int x = ox + r.nextInt(spread) - spread / 2;
            int z = oz + r.nextInt(spread) - spread / 2;
            if (used.add((long) x << 32 | z & 0xFFFFFFFFL)) {
                out.add(at(x, z));
            }
        }
        return out;
    }

    private static void assertSameOrder(List<Action> layer, Pos cursor, String what) {
        List<Action> grid = new ArrayList<>();
        List<Action> linear = new ArrayList<>();
        Pos endGrid = PlanCompiler.nearestNeighbour(new ArrayList<>(layer), cursor, grid);
        Pos endLinear = PlanCompiler.nearestNeighbourLinear(new ArrayList<>(layer), cursor, linear);
        assertEquals(linear.size(), grid.size(), what);
        for (int i = 0; i < linear.size(); i++) {
            assertEquals(linear.get(i).anchor(), grid.get(i).anchor(), what + ": Schritt " + i);
        }
        assertEquals(endLinear, endGrid, what);
    }

    @Test
    void denseSparseAndFarAwayStartsGiveTheOldOrder() {
        Random r = new Random(20261115);
        int[] sizes = {PlanCompiler.GRID_FROM, 300, 700, 2000, 4000};
        int[] spreads = {60, 90, 400, 3000};
        for (int round = 0; round < 40; round++) {
            int n = sizes[round % sizes.length];
            int spread = Math.max(spreads[round % spreads.length], (int) Math.ceil(Math.sqrt(n * 1.5)) + 1);
            List<Action> layer = scatter(r, n, spread, r.nextInt(200) - 100, r.nextInt(200) - 100);
            Pos cursor = round % 3 == 0 ? new Pos(0, 64, -3) : new Pos(r.nextInt(4000) - 2000, 64,
                    r.nextInt(4000) - 2000);
            assertSameOrder(layer, cursor, "Runde " + round + " n=" + n + " spread=" + spread);
        }
    }

    @Test
    void twoFarApartGroupsAndLinesGiveTheOldOrder() {
        Random r = new Random(7);
        List<Action> layer = new ArrayList<>(scatter(r, 400, 30, 0, 0));
        layer.addAll(scatter(r, 400, 30, 5000, -5000));
        for (int x = -300; x <= 300; x++) {
            layer.add(at(x, 900));
        }
        assertSameOrder(layer, new Pos(0, 64, -3), "Gruppen und Linie");
    }

    @Test
    void aFullSquareLikeTheLobbyFloorGivesTheOldOrder() {
        List<Action> layer = new ArrayList<>();
        for (int x = -40; x <= 40; x++) {
            for (int z = 0; z <= 80; z++) {
                layer.add(at(x, z));
            }
        }
        // Gleiche Abstände überall: Hier entscheidet allein der Gleichstand (kleineres x, dann z).
        assertSameOrder(layer, new Pos(0, 64, -3), "81 × 81");
    }
}
