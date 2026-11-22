package com.blackn0va.baumeister.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.BuildPlan;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.PlanAutoFix;
import com.blackn0va.baumeister.plan.PlanParser;
import com.blackn0va.baumeister.plan.PlanReview.Accept;
import com.blackn0va.baumeister.plan.Pos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Zuckerrohrfarm „wird um das Modul herum gebaut“ (AK-27, gemessen 2026-11-22 an der PlanBench-Antwort des 7B):
 * Sand und Wasser stehen VOR dem Modul im Plan, das Modul überschreibt nur seine Zellen, der Rest blieb als Mantel.
 * Nachgebaut mit denselben Schritten, auf den Kasten der Farm (x0..2, y0..4, z0..3) zugeschnitten.
 */
class ModuleShellFixTest {

    private static final PlanJudge JUDGE = new PlanJudge();
    private static final String ORDER = "eine Zuckerrohrfarm und eine Schmelzanlage nebeneinander";

    private static String plan(String... steps) {
        return "{\"design\":\"x\",\"reply\":\"x\",\"mode\":\"new\",\"steps\":[" + String.join(",", steps) + "]}";
    }

    private static Map<Pos, String> cells(CompiledBuild b) {
        Map<Pos, String> out = new HashMap<>();
        for (CompiledBuild.Action a : b.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                out.put(c.pos(), c.block().id());
            }
        }
        return out;
    }

    @Test
    void sandAndWaterPlannedBeforeTheModuleNoLongerEncaseIt() throws Exception {
        String module = "{\"op\":\"module\",\"name\":\"zuckerrohrfarm\",\"pos\":[0,0,0],\"facing\":\"front\"}";
        String json = plan(
                "{\"op\":\"fill\",\"block\":\"sand\",\"from\":[-1,0,-1],\"to\":[3,0,4]}",
                "{\"op\":\"fill\",\"block\":\"water\",\"from\":[0,1,0],\"to\":[3,4,4]}",
                module,
                "{\"op\":\"set\",\"block\":\"stone_bricks\",\"pos\":[3,0,1]}",
                "{\"op\":\"fill\",\"block\":\"stone_bricks\",\"from\":[-3,-1,-3],\"to\":[6,-1,6]}");
        Map<Pos, String> farm = cells(JUDGE.compiler().compile(PlanParser.parse(plan(module))));

        Accept a = assertInstanceOf(Accept.class, JUDGE.judge(ORDER, json, 0, 1));
        assertTrue(a.repairs().stream().anyMatch(r -> r.contains("um Module weggelassen")), a.repairs().toString());
        Map<Pos, String> built = cells(a.build());
        for (Map.Entry<Pos, String> e : built.entrySet()) {
            Pos p = e.getKey();
            if (p.y() >= 0 && !farm.containsKey(p)) {
                assertTrue(!e.getValue().equals("water") && !e.getValue().equals("sand"),
                        "Mantel übrig bei " + p + ": " + e.getValue());
            }
        }
        for (Map.Entry<Pos, String> e : farm.entrySet()) {
            assertEquals(e.getValue(), built.get(e.getKey()), "Modulzelle verändert bei " + e.getKey());
        }
        // Was ein Schritt NACH dem Modul in den Rand setzt, bleibt.
        assertEquals("stone_bricks", built.get(new Pos(3, 0, 1)));
    }

    /** So wie in der PlanBench-Antwort: Sand und Wasser reichen eine Zelle über den Rand ±1 – auch das fällt weg. */
    @Test
    void theMeasuredShellReachingOneBeyondTheMarginGoesAsAWhole() throws Exception {
        String module = "{\"op\":\"module\",\"name\":\"zuckerrohrfarm\",\"pos\":[0,0,0],\"facing\":\"front\"}";
        String json = plan(
                "{\"op\":\"fill\",\"block\":\"sand\",\"from\":[-1,0,-1],\"to\":[4,0,4]}",
                "{\"op\":\"fill\",\"block\":\"water\",\"from\":[0,1,0],\"to\":[4,4,4]}",
                module,
                "{\"op\":\"fill\",\"block\":\"stone_bricks\",\"from\":[-3,-1,-3],\"to\":[6,-1,6]}");
        Map<Pos, String> farm = cells(JUDGE.compiler().compile(PlanParser.parse(plan(module))));
        Optional<PlanAutoFix.Cells> shell = shells(json);
        assertTrue(shell.isPresent());
        Map<Pos, String> built = cells(JUDGE.compiler().compile(PlanParser.parse(json)));
        for (Map.Entry<Pos, String> e : built.entrySet()) {
            boolean filler = e.getValue().equals("water") || e.getValue().equals("sand");
            if (filler && !farm.containsKey(e.getKey())) {
                assertTrue(shell.get().cells().contains(e.getKey()), "Mantelrest bei " + e.getKey());
            }
        }
    }

    /** Gegenbeispiel 1 der Prüfung (2026-11-22): Hallenwand dicht am Modul – vorher 90 Blöcke herausgeschnitten. */
    @Test
    void hallWallsCloseToTheModuleAreNotCut() throws Exception {
        assertEquals(Optional.empty(), shells(plan(
                "{\"op\":\"walls\",\"block\":\"stone_bricks\",\"from\":[-1,0,-1],\"to\":[3,6,4]}",
                "{\"op\":\"module\",\"name\":\"zuckerrohrfarm\",\"pos\":[0,0,0],\"facing\":\"front\"}")));
    }

    /** Gegenbeispiel 2: Boden auf Modulhöhe, weit über den Rand hinaus – vorher ein Graben aus 18 Blöcken. */
    @Test
    void floorAtModuleHeightGetsNoTrench() throws Exception {
        for (String block : new String[] {"stone_bricks", "stone", "sand", "dirt"}) {
            assertEquals(Optional.empty(), shells(plan(
                    "{\"op\":\"fill\",\"block\":\"" + block + "\",\"from\":[-4,0,-4],\"to\":[6,0,7]}",
                    "{\"op\":\"module\",\"name\":\"zuckerrohrfarm\",\"pos\":[0,0,0],\"facing\":\"front\"}")),
                    block);
        }
    }

    /** Gegenbeispiel 3: Truhe, Trichter, Redstone am Modul gehören zur Maschine und bleiben. */
    @Test
    void partsNextToTheModuleStay() throws Exception {
        for (String part : new String[] {"chest", "hopper", "redstone_wire"}) {
            assertEquals(Optional.empty(), shells(plan(
                    "{\"op\":\"set\",\"block\":\"" + part + "\",\"pos\":[3,0,1]}",
                    "{\"op\":\"module\",\"name\":\"zuckerrohrfarm\",\"pos\":[0,0,0],\"facing\":\"front\"}")),
                    part);
        }
    }

    /**
     * Nur Füllmasse ist ein Mantel (Prüfung Runde 3, 2026-11-22): Derselbe fill in Reichweite des Modulkastens aus
     * Sand wird gerichtet, aus Glas, Ziegeln, Stein oder Bruchstein (Sockel, Vitrine, Podest) bleibt er stehen.
     */
    @Test
    void onlyLooseFillerCountsAsShell() throws Exception {
        String module = "{\"op\":\"module\",\"name\":\"zuckerrohrfarm\",\"pos\":[0,0,0],\"facing\":\"front\"}";
        for (String block : new String[] {"glass", "stone_bricks", "stone", "cobblestone"}) {
            assertEquals(Optional.empty(), shells(plan(
                    "{\"op\":\"fill\",\"block\":\"" + block + "\",\"from\":[-1,0,-1],\"to\":[3,0,4]}", module)),
                    block + " ist Baustoff, kein Mantel");
        }
        assertTrue(shells(plan("{\"op\":\"fill\",\"block\":\"sand\",\"from\":[-1,0,-1],\"to\":[3,0,4]}", module))
                .isPresent(), "Sand an derselben Stelle ist Mantel");
    }

    private static Optional<PlanAutoFix.Cells> shells(String json) throws Exception {
        BuildPlan plan = PlanParser.parse(json);
        CompiledBuild build = JUDGE.compiler().compile(plan);
        return PlanAutoFix.moduleShells(json, plan, build, JUDGE.compiler());
    }

    /** Gegenstück: Hallenboden unter dem Modul (y −1) und Wände 2 Blöcke daneben sind kein Mantel. */
    @Test
    void hallFloorAndWallsOutsideTheMarginStay() throws Exception {
        String json = plan(
                "{\"op\":\"fill\",\"block\":\"stone_bricks\",\"from\":[-3,-1,-3],\"to\":[5,-1,6]}",
                "{\"op\":\"walls\",\"block\":\"stone_bricks\",\"from\":[-3,0,-3],\"to\":[5,6,6]}",
                "{\"op\":\"module\",\"name\":\"zuckerrohrfarm\",\"pos\":[0,0,0],\"facing\":\"front\"}");
        Accept a = assertInstanceOf(Accept.class, JUDGE.judge(ORDER, json, 0, 1));
        assertTrue(a.repairs().stream().noneMatch(r -> r.contains("um Module weggelassen")), a.repairs().toString());
    }
}
