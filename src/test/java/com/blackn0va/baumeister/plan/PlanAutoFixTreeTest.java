package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.PlanReview.Accept;
import com.blackn0va.baumeister.plan.PlanReview.LintMode;
import com.blackn0va.baumeister.plan.PlanReview.Verdict;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * KI-Plan: Baum ohne pos bekommt einen Platz vor dem Bauwerk (T-024). Gemessen 2026-11-22 (PlanBench, Qwen2.5 7B,
 * Vulkan): „eine Burg mit Bäumen davor“ kam mit zwei tree-Schritten ohne pos, die Reparaturrunde ergänzte nur einen,
 * der Plan wurde verworfen. Die Burg hier ist genau diese Antwort.
 */
class PlanAutoFixTreeTest {

    private static final String CASTLE = "{\"op\":\"castle\",\"block\":\"stone_bricks\",\"from\":[0,0,0],"
            + "\"to\":[24,0,24],\"height\":6,\"facing\":\"front\",\"style\":\"round\"}";
    private static final String LOOSE_TREE = "{\"op\":\"tree\",\"block\":\"oak_log\",\"style\":\"round\"}";

    private static PlanReview review(String prompt) {
        return new PlanReview(HouseSite.COMPILER, ModuleLibrary.EMPTY, new PlanReview.Context(prompt,
                RequestHints.parse(prompt, Set.of()), HouseSite.free(), false, 12, 1, LintMode.STRICT));
    }

    /** Die gemessene Antwort: zwei gleiche tree-Schritte, die der Normalisierer zu einem macht – vorn in der Mitte. */
    @Test
    void castleWithTreesWithoutPositionIsAccepted() {
        Verdict v = review("eine Burg mit Bäumen davor").judge(HouseSite.plan(CASTLE, LOOSE_TREE, LOOSE_TREE), 0);
        Accept a = assertInstanceOf(Accept.class, v, v.toString());
        List<BuildPlan.Step> trees = a.plan().steps().stream().filter(s -> s.op() == BuildPlan.Op.TREE).toList();
        assertEquals(new Pos(12, 0, -PlanAutoFix.TREE_FRONT), trees.getFirst().pos(), a.plan().steps().toString());
        assertTrue(a.repairs().stream().anyMatch(r -> r.contains("tree ohne pos")), a.repairs().toString());
    }

    @Test
    void twoDifferentTreesWithoutPositionStandSideBySide() {
        String cone = "{\"op\":\"tree\",\"block\":\"spruce_log\",\"style\":\"cone\"}";
        Verdict v = review("eine Burg mit Bäumen davor").judge(HouseSite.plan(CASTLE, LOOSE_TREE, cone), 0);
        Accept a = assertInstanceOf(Accept.class, v, v.toString());
        List<BuildPlan.Step> trees = a.plan().steps().stream().filter(s -> s.op() == BuildPlan.Op.TREE).toList();
        assertEquals(2, trees.size(), a.plan().steps().toString());
        // Mitte der Burg x = 12, Abstand 10: x 7 und 17, 8 vor der Vorderkante z = 0, auf dem Boden.
        assertEquals(new Pos(7, 0, -PlanAutoFix.TREE_FRONT), trees.get(0).pos());
        assertEquals(new Pos(17, 0, -PlanAutoFix.TREE_FRONT), trees.get(1).pos());
        assertTrue(a.repairs().stream().anyMatch(r -> r.contains("tree ohne pos")), a.repairs().toString());
    }

    /** Ein Baum mit eigenem Ort bleibt, wo er ist; nur der ohne Ort wird gesetzt. */
    @Test
    void treeWithPositionKeepsIt() {
        PlanAutoFix.Fixed f = PlanAutoFix.treePositions(HouseSite.plan(CASTLE,
                "{\"op\":\"tree\",\"block\":\"oak_log\",\"pos\":[12,0,30],\"style\":\"round\"}", LOOSE_TREE));
        assertEquals(1, f.notes().size(), f.notes().toString());
        assertTrue(f.json().contains("\"pos\":[12,0,30]"), f.json());
        assertTrue(f.json().contains("\"pos\":[12,0,-8]"), f.json());
    }

    /** Nur Bäume: wie der Schnellauftrag 8 vor dem Ursprung, nebeneinander. */
    @Test
    void treesAloneStandInFrontOfTheOrigin() {
        PlanAutoFix.Fixed f = PlanAutoFix.treePositions(HouseSite.plan(LOOSE_TREE));
        assertTrue(f.json().contains("\"pos\":[0,0,8]"), f.json());
    }

    /** Andere Ops ohne pos bleiben eine Absage – dort trägt der Ort die Funktion. */
    @Test
    void otherOpsWithoutPositionAreLeftAlone() {
        String json = HouseSite.plan(CASTLE, "{\"op\":\"set\",\"block\":\"lantern\"}");
        assertTrue(PlanAutoFix.treePositions(json).notes().isEmpty());
    }
}
