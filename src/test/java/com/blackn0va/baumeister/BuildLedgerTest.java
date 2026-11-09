package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Baubuch für „Vera, reparier das“ – nur Bauten des Baumeisters, Soll-Zustand Block für Block. */
class BuildLedgerTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();

    private static List<BuildLedger.Cell> farm(int x0) {
        return List.of(new BuildLedger.Cell(x0, 64, 0, "minecraft:sand"),
                new BuildLedger.Cell(x0, 65, 0, "minecraft:sugar_cane[age=0]"),
                new BuildLedger.Cell(x0 + 1, 64, 0, "minecraft:repeater[delay=2,facing=north,locked=false,powered=false]"),
                new BuildLedger.Cell(x0 + 2, 64, 0, "minecraft:lever[face=floor,facing=north,powered=false]"));
    }

    @Test
    void cellsSurviveAReloadWithTheirStates(@TempDir Path dir) throws IOException {
        BuildLedger l = new BuildLedger(dir);
        BuildLedger.Entry e = l.add(ALICE, "Alice", "eine Zuckerrohrfarm", WORLD, 1000, farm(0), false).orElseThrow();
        BuildLedger again = new BuildLedger(dir);
        BuildLedger.Entry found = again.at(WORLD, 1, 64, 0, 0).orElseThrow();
        assertEquals(e.id(), found.id());
        assertEquals("eine Zuckerrohrfarm", found.order());
        assertEquals(farm(0), again.cells(found));
    }

    @Test
    void damageIsMissingOrReplacedMaterialNotAChangedState() {
        Map<String, String> world = Map.of("0,64,0", "minecraft:sand", "0,65,0", "minecraft:air", "1,64,0",
                "minecraft:cobblestone", "2,64,0", "minecraft:lever");  // Hebel umgelegt: anderer Zustand, gleiches Material
        List<BuildLedger.Cell> bad = BuildLedger.damaged(farm(0), (x, y, z) -> world.get(x + "," + y + "," + z));
        assertEquals(List.of(farm(0).get(2), farm(0).get(1)), bad, "Verstärker (y 64) vor Zuckerrohr (y 65)");
    }

    @Test
    void smallestBuildWinsAndOutsideFindsNothing(@TempDir Path dir) {
        BuildLedger l = new BuildLedger(dir);
        List<BuildLedger.Cell> house = List.of(new BuildLedger.Cell(-10, 60, -10, "minecraft:oak_planks"),
                new BuildLedger.Cell(10, 70, 10, "minecraft:oak_planks"));
        l.add(ALICE, "Alice", "ein Haus", WORLD, 1, house, false);
        l.add(ALICE, "Alice", "eine Zuckerrohrfarm", WORLD, 2, farm(0), false);
        assertEquals("eine Zuckerrohrfarm", l.at(WORLD, 1, 64, 0, 2).orElseThrow().order());
        assertEquals("ein Haus", l.at(WORLD, -8, 62, 5, 2).orElseThrow().order());
        assertTrue(l.at(WORLD, 50, 64, 50, 3).isEmpty());
        assertTrue(l.at(UUID.randomUUID(), 1, 64, 0, 2).isEmpty(), "andere Welt");
    }

    @Test
    void undoAndUpdateRemoveTheOldEntry(@TempDir Path dir) {
        BuildLedger l = new BuildLedger(dir);
        l.add(ALICE, "Alice", "eine Zuckerrohrfarm", WORLD, 42, farm(0), false);
        l.forget(ALICE, 42);
        assertTrue(l.at(WORLD, 1, 64, 0, 0).isEmpty(), "nach rückgängig nicht wieder aufbauen");

        l.add(ALICE, "Alice", "eine Zuckerrohrfarm", WORLD, 1, farm(0), false);
        l.add(ALICE, "Alice", "mach sie größer", WORLD, 2, farm(1), true);
        assertEquals(1, l.entries().size(), "Umbau ersetzt den alten Eintrag");
        assertEquals("mach sie größer", l.entries().getFirst().order());
    }

    @Test
    void hugeBuildsAreNotRecorded(@TempDir Path dir) {
        BuildLedger l = new BuildLedger(dir);
        List<BuildLedger.Cell> city = new java.util.ArrayList<>();
        for (int i = 0; i <= BuildLedger.MAX_CELLS; i++) {
            city.add(new BuildLedger.Cell(i, 64, 0, "minecraft:stone"));
        }
        assertTrue(l.add(ALICE, "Alice", "eine Stadt", WORLD, 1, city, false).isEmpty());
    }
}
