package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.PlanLinter.Issue;
import com.blackn0va.baumeister.plan.PlanLinter.Rule;
import com.blackn0va.baumeister.plan.PlanReview.Accept;
import com.blackn0va.baumeister.plan.PlanReview.LintMode;
import com.blackn0va.baumeister.plan.PlanReview.Repair;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * KI-Plan: Tür außerhalb der Wand wird in die Wand gerichtet (AK-27). Grundlinie PlanBench 2026-11-01: Wachturm und
 * Kirche verworfen, „Tür nicht in der Wand“ auch nach der Reparaturrunde – das Modell baute die Wand richtig und
 * stellte die Tür daneben (frei, in der Luft, mitten in den Raum). Die Fälle hier sind diesen Antworten nachgebaut.
 */
class PlanAutoFixDoorTest {

    /** Wachturm 5×5: Boden y0, Wände y1..5, Plattform obendrauf – so wie das 7B ihn plant. */
    private static final String[] TOWER = {
        "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[0,0,0],\"to\":[4,0,4]}",
        "{\"op\":\"walls\",\"block\":\"oak_log\",\"from\":[0,1,0],\"to\":[4,5,4]}",
        "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[0,6,0],\"to\":[4,6,4]}"};

    private static PlanReview review(String prompt) {
        return new PlanReview(HouseSite.COMPILER, ModuleLibrary.EMPTY, new PlanReview.Context(prompt,
                RequestHints.parse(prompt, Set.of()), HouseSite.free(), false, 12, 1, LintMode.STRICT));
    }

    private static String with(String... extra) {
        String[] steps = java.util.Arrays.copyOf(TOWER, TOWER.length + extra.length);
        System.arraycopy(extra, 0, steps, TOWER.length, extra.length);
        return HouseSite.plan(steps);
    }

    private static BuildPlan.Step lastDoor(Accept a) {
        BuildPlan.Step s = a.plan().steps().getLast();
        assertEquals(BuildPlan.Op.DOOR, s.op(), a.plan().steps().toString());
        return s;
    }

    @Test
    void doorStandingFreeBesideTheTowerIsMovedIntoTheOuterWall() {
        Accept a = assertInstanceOf(Accept.class, review("Bau mir einen Wachturm aus Holz")
                .judge(with("{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[8,0,8],\"facing\":\"front\"}"), 0));
        assertTrue(a.remaining().stream().noneMatch(i -> i.rule() == Rule.DOOR), a.remaining().toString());
        assertTrue(a.repairs().stream().anyMatch(r -> r.contains("Tür von [8,0,8] in die Außenwand")),
                a.repairs().toString());
        BuildPlan.Step door = lastDoor(a);
        // Bodenhöhe y1 (auf dem Boden y0); nächste Wandstelle zu [8,8]: [3,1,4] hinten und [4,1,3] rechts gleich weit,
        // „front“ (gewünscht) ist keine davon – hinten kommt vor rechts.
        assertEquals(new Pos(3, 1, 4), door.pos());
        assertEquals("back", door.facing());
    }

    @Test
    void doorInTheMiddleOfTheRoomGoesToTheNearestWallDeterministically() {
        String json = with("{\"op\":\"set\",\"block\":\"spruce_door[facing=north]\",\"pos\":[2,1,2]}");
        Accept first = assertInstanceOf(Accept.class, review("einen Leuchtturm").judge(json, 0));
        Accept again = assertInstanceOf(Accept.class, review("einen Leuchtturm").judge(json, 0));
        BuildPlan.Step door = lastDoor(first);
        // Vier Wände gleich weit, set nennt keine Seite – vorn (Spielerseite) gewinnt, Mitte der Wand.
        assertEquals(new Pos(2, 1, 0), door.pos());
        assertEquals("front", door.facing());
        assertEquals("spruce_door", door.block());
        assertEquals(first.json(), again.json(), "derselbe Plan bekommt dieselbe Tür");
        assertTrue(first.remaining().stream().noneMatch(i -> i.rule() == Rule.DOOR), first.remaining().toString());
    }

    @Test
    void onATieTheSideTheLlmAskedForWins() {
        Accept a = assertInstanceOf(Accept.class, review("Bau mir einen Wachturm aus Holz")
                .judge(with("{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[2,1,2],\"facing\":\"right\"}"), 0));
        BuildPlan.Step door = lastDoor(a);
        assertEquals(new Pos(4, 1, 2), door.pos());
        assertEquals("right", door.facing());
    }

    @Test
    void doorHangingInTheAirComesDownToGroundLevel() {
        Accept a = assertInstanceOf(Accept.class, review("einen Pferdestall aus Holz")
                .judge(with("{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[2,4,-1],\"facing\":\"front\"}"), 0));
        BuildPlan.Step door = lastDoor(a);
        assertEquals(new Pos(2, 1, 0), door.pos(), "vordere Wand auf Bodenhöhe, nicht oben in der Luft");
        assertEquals("front", door.facing());
    }

    /** Eine Trennwand im Inneren ist keine Außenwand: Von draußen käme man durch diese Tür nie hinein. */
    @Test
    void innerPartitionWallIsNotTakenForTheDoor() {
        String json = HouseSite.plan(
                "{\"op\":\"fill\",\"block\":\"cobblestone\",\"from\":[0,0,0],\"to\":[10,0,6]}",
                "{\"op\":\"walls\",\"block\":\"stone_bricks\",\"from\":[0,1,0],\"to\":[10,3,6]}",
                "{\"op\":\"fill\",\"block\":\"stone_bricks\",\"from\":[5,1,1],\"to\":[5,3,5]}",
                "{\"op\":\"fill\",\"block\":\"stone_bricks\",\"from\":[0,4,0],\"to\":[10,4,6]}",
                "{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[6,1,3],\"facing\":\"front\"}");
        Accept a = assertInstanceOf(Accept.class, review("eine kleine Kirche aus Stein").judge(json, 0));
        BuildPlan.Step door = lastDoor(a);
        assertTrue(door.pos().x() != 5, "nicht in die Trennwand x=5: " + door.pos());
        assertTrue(door.pos().x() == 0 || door.pos().x() == 10 || door.pos().z() == 0 || door.pos().z() == 6,
                "in eine Außenwand: " + door.pos());
        assertEquals(1, door.pos().y());
    }

    /**
     * Gegenbeispiel 4 der Prüfung (2026-11-22): Haus mit Dach in einem ummauerten Garten. Vorher gewann die niedrigste
     * Lage, die Tür landete in der Gartenmauer bei [2,0,-3] – und das Haus hatte keinen Eingang.
     */
    @Test
    void houseWallWinsOverALowGardenWall() {
        String json = HouseSite.plan(
                "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[0,0,0],\"to\":[4,0,4]}",
                "{\"op\":\"walls\",\"block\":\"oak_planks\",\"from\":[0,1,0],\"to\":[4,3,4]}",
                "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[0,4,0],\"to\":[4,4,4]}",
                "{\"op\":\"walls\",\"block\":\"cobblestone\",\"from\":[-3,0,-3],\"to\":[7,1,7]}",
                "{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[2,1,2],\"facing\":\"front\"}");
        Accept a = assertInstanceOf(Accept.class, review("ein Haus mit Garten").judge(json, 0));
        BuildPlan.Step door = lastDoor(a);
        assertEquals(new Pos(2, 1, 0), door.pos(), "Hauswand vorn, nicht die Gartenmauer");
        assertEquals("front", door.facing());
    }

    /** Ohne Wand bleibt alles wie bisher: Befund an das LLM, keine geratene Tür. */
    @Test
    void withoutAnyWallTheDoorStaysAFinding() {
        String json = HouseSite.plan(
                "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[0,0,0],\"to\":[4,0,4]}",
                "{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[2,1,2],\"facing\":\"front\"}");
        Repair r = assertInstanceOf(Repair.class, review("einen Marktstand").judge(json, 0));
        assertTrue(r.reason().contains("is not set in a wall"), r.reason());

        BuildPlan plan = parse(json);
        CompiledBuild build = compile(plan);
        List<Issue> issues = PlanLinter.lint(plan, build, true);
        assertTrue(issues.stream().anyMatch(i -> i.rule() == Rule.DOOR), issues.toString());
        assertEquals(Optional.empty(), PlanAutoFix.doors(json, plan, build, issues, p -> false));
    }

    /** Beckenwand ist keine Hauswand: Hinter der Tür stünde Wasser. */
    @Test
    void basinWallFullOfWaterGetsNoDoor() {
        String json = HouseSite.plan(
                "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[4,0,4]}",
                "{\"op\":\"walls\",\"block\":\"stone\",\"from\":[0,1,0],\"to\":[4,2,4]}",
                "{\"op\":\"fill\",\"block\":\"water\",\"from\":[1,1,1],\"to\":[3,2,3]}",
                "{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[8,0,8],\"facing\":\"front\"}");
        BuildPlan plan = parse(json);
        CompiledBuild build = compile(plan);
        List<Issue> issues = PlanLinter.lint(plan, build, true);
        assertTrue(issues.stream().anyMatch(i -> i.rule() == Rule.DOOR), issues.toString());
        assertEquals(Optional.empty(), PlanAutoFix.doors(json, plan, build, issues, p -> false));
    }

    private static BuildPlan parse(String json) {
        try {
            return PlanParser.parse(json);
        } catch (PlanException e) {
            throw new AssertionError(e);
        }
    }

    private static CompiledBuild compile(BuildPlan plan) {
        try {
            return HouseSite.COMPILER.compile(plan);
        } catch (PlanException e) {
            throw new AssertionError(e);
        }
    }
}
