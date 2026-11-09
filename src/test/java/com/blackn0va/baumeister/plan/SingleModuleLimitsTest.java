package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Schematics dürfen größer sein als LLM-Pläne (2026-11-08, Wunsch; Olymp 952 911
 * Blöcke gegen bau.max-blöcke 150 000): Die größere Grenze gilt nur für einen Plan aus genau einem Bauteil.
 */
class SingleModuleLimitsTest {

    /** 30 × 1 × 30 = 900 Blöcke Stein, 30 breit. */
    private static final ModuleLibrary BIG = new ModuleLibrary() {
        @Override
        public Optional<Module> find(String name) {
            if (!name.equals("gross")) {
                return Optional.empty();
            }
            List<CompiledBuild.Change> cells = new ArrayList<>();
            for (int x = 0; x < 30; x++) {
                for (int z = 0; z < 30; z++) {
                    cells.add(new CompiledBuild.Change(new Pos(x, 0, z), BlockSpec.of("stone")));
                }
            }
            return Optional.of(new Module("gross", "groß", new Pos(30, 1, 30), List.copyOf(cells)));
        }

        @Override
        public List<Module> all() {
            return List.of();
        }
    };

    private static final String MODULE = "{\"op\":\"module\",\"name\":\"gross\",\"pos\":[0,0,0],\"facing\":\"front\"}";
    private static final String STONE = "{\"op\":\"set\",\"block\":\"stone\",\"pos\":[0,1,0]}";

    private static PlanCompiler compiler(int singleBlocks, int singleExtent) {
        return new PlanCompiler(id -> true, "stone", new PlanCompiler.Limits(100, 20, singleBlocks, singleExtent), BIG);
    }

    @Test
    void oneSchematicMayBeAsLargeAsTheSchematicLimit() throws Exception {
        CompiledBuild b = compiler(1000, 40).compile(PlanParser.parse(HouseSite.plan(MODULE)));
        assertEquals(900, b.blockCount());
    }

    @Test
    void theSameModuleWithAnotherStepKeepsTheNormalLimit() {
        PlanException e = assertThrows(PlanException.class,
                () -> compiler(1000, 40).compile(PlanParser.parse(HouseSite.plan(MODULE, STONE))));
        assertTrue(e.getMessage().contains("zu groß"), e.getMessage());
    }

    @Test
    void schematicLimitStillHolds() {
        assertThrows(PlanException.class, () -> compiler(500, 40).compile(PlanParser.parse(HouseSite.plan(MODULE))));
        PlanException e = assertThrows(PlanException.class,
                () -> compiler(1000, 20).compile(PlanParser.parse(HouseSite.plan(MODULE))));
        assertTrue(e.getMessage().contains("zu groß"), e.getMessage());
    }
}
