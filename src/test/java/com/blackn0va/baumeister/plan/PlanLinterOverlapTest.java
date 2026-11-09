package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.PlanLinter.Issue;
import com.blackn0va.baumeister.plan.PlanLinter.Rule;
import com.blackn0va.baumeister.plan.PlanLinter.Severity;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Regel (g): zwei Bauteile im selben Raum – und was ausdrücklich KEINE Überlappung ist. */
class PlanLinterOverlapTest {

    private static List<Issue> lint(String... steps) throws PlanException {
        BuildPlan plan = PlanParser.parse(HouseSite.plan(steps));
        return PlanLinter.lint(plan, HouseSite.COMPILER.compile(plan), true);
    }

    private static List<Issue> overlaps(List<Issue> issues) {
        return issues.stream().filter(i -> i.rule() == Rule.OVERLAP).toList();
    }

    @Test
    void secondBuildingInTheSameSpaceIsAnError() throws PlanException {
        List<Issue> found = overlaps(lint(
                "{\"op\":\"fill\",\"block\":\"cobblestone\",\"from\":[0,0,0],\"to\":[6,0,6]}",
                "{\"op\":\"walls\",\"block\":\"oak_planks\",\"from\":[0,1,0],\"to\":[6,3,6]}",
                "{\"op\":\"walls\",\"block\":\"stone_bricks\",\"from\":[0,1,0],\"to\":[6,4,6]}"));
        assertEquals(1, found.size(), found.toString());
        Issue i = found.getFirst();
        assertEquals(Severity.ERROR, i.severity());
        assertEquals(3, i.step());
        assertTrue(i.llmText().contains("overwrites 72 of the 72 blocks of step 2"), i.llmText());
    }

    @Test
    void postsWindowsFloorsAndCarvingAreNoOverlap() throws PlanException {
        List<Issue> found = overlaps(lint(
                "{\"op\":\"walls\",\"block\":\"cobblestone\",\"from\":[0,0,0],\"to\":[6,0,6]}",
                "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[0,0,0],\"to\":[6,0,6]}",
                "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[10,0,0],\"to\":[16,5,6]}",
                "{\"op\":\"fill\",\"block\":\"air\",\"from\":[11,1,1],\"to\":[15,4,5]}",
                "{\"op\":\"walls\",\"block\":\"glass\",\"from\":[10,2,0],\"to\":[16,3,6]}",
                "{\"op\":\"walls\",\"block\":\"oak_planks\",\"from\":[20,1,0],\"to\":[26,3,6]}",
                "{\"op\":\"fill\",\"block\":\"oak_log\",\"from\":[20,1,0],\"to\":[20,3,0]}",
                "{\"op\":\"fill\",\"block\":\"oak_log\",\"from\":[26,1,6],\"to\":[26,3,6]}"));
        assertTrue(found.isEmpty(), found.toString());
    }
}
