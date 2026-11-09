package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.PlanReview.Accept;
import com.blackn0va.baumeister.plan.PlanReview.LintMode;
import com.blackn0va.baumeister.plan.PlanReview.Verdict;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Je ein Test für jeden Verwerfgrund aus den dev01-Logs bis 2026-10-31 (55 verworfene Pläne), mit genau den dort
 * gemessenen Maßen: Vorher Reparaturrunde und danach Absage, jetzt baut der Baumeister – ohne zweiten LLM-Aufruf.
 */
class PlanAutoFixTest {

    private static final Set<String> MODULES = Set.of("kombischloss", "klingel", "und_gatter", "xor_gatter");

    private static final Machines LIB = new Machines();
    /** Wie im Plugin: mit Bauteil-Bibliothek (HouseSite.COMPILER kennt keine Module) und den Grenzen aus Settings. */
    private static final PlanCompiler COMPILER = new PlanCompiler(id -> true, "stone",
            new PlanCompiler.Limits(150_000, 192), LIB);

    private static Verdict judge(String prompt, String plan, int attempt, int targetHeight) {
        PlanReview review = new PlanReview(COMPILER, LIB, new PlanReview.Context(prompt,
                RequestHints.parse(prompt, MODULES), HouseSite.free(), false, 12, 1, LintMode.STRICT, targetHeight));
        return review.judge(plan, attempt);
    }

    private static Accept accepted(String prompt, String plan) {
        Verdict v = judge(prompt, plan, 0, 0);
        return assertInstanceOf(Accept.class, v, v::toString);
    }

    /** 16:37–16:50: kombischloss als ganzer Plan, Auftrag ohne Maschinenwort – 24 Mal verworfen. */
    @Test
    void lockAsTheWholePlanIsBuiltEvenWithoutAMachineWord() {
        String plan = HouseSite.plan("{\"op\":\"module\",\"name\":\"kombischloss\",\"pos\":[0,0,0]}");
        Accept a = accepted("bau mir was zum abschliessen", plan);
        assertEquals("kombischloss", a.plan().steps().getFirst().name());
    }

    /** 2026-10-28: Logikgatter als Fassadenschmuck eines Hauses – werden entfernt, das Haus wird gebaut. */
    @Test
    void decorativeModulesInAHouseAreRemovedWithoutTheLlm() throws Exception {
        String plan = HouseSite.plan(
                "{\"op\":\"house\",\"block\":\"oak_planks\",\"from\":[0,0,0],\"to\":[9,0,9],\"floors\":2}",
                "{\"op\":\"module\",\"name\":\"und_gatter\",\"pos\":[12,0,0]}");
        Accept a = accepted("bau ein haus", plan);
        assertTrue(PlanAutoFix.moduleShare(PlanParser.parse(plan), COMPILER.compile(PlanParser.parse(plan)))
                > 0, "das Modul setzt wirklich Blöcke");
        assertTrue(a.plan().steps().stream().noneMatch(s -> s.op() == BuildPlan.Op.MODULE), a.plan().toString());
        assertTrue(a.repairs().stream().anyMatch(r -> r.contains("Verzierung entfernt")), a.repairs().toString());
    }

    /** 22:02:57: house 32×1 – zweimal verworfen. */
    @Test
    void houseFootprintOutsideTheRangeIsClamped() {
        String plan = HouseSite.plan(
                "{\"op\":\"house\",\"block\":\"oak_planks\",\"from\":[0,0,0],\"to\":[31,0,0],\"floors\":1}");
        Accept a = accepted("bau ein haus", plan);
        BuildPlan.Step s = a.plan().steps().getFirst();
        assertEquals(23, s.to().x());
        assertEquals(4, s.to().z());
        assertTrue(a.repairs().stream().anyMatch(r -> r.contains("32×1 auf 24×5")), a.repairs().toString());
    }

    /** 22:01:51 und 13:23: skyscraper unter 5×5 – fünfmal verworfen. */
    @Test
    void tooSmallSkyscraperIsWidened() {
        String plan = HouseSite.plan("{\"op\":\"skyscraper\",\"block\":\"stone_bricks\",\"from\":[0,0,0],"
                + "\"to\":[3,0,2],\"floors\":6}");
        Accept a = accepted("bau ein hochhaus", plan);
        assertTrue(a.repairs().stream().anyMatch(r -> r.contains("4×3 auf 5×5")), a.repairs().toString());
    }

    /** 13:43: 13×201×5 bei 192 je Richtung – gekürzt statt verworfen. */
    @Test
    void slightlyTooTallBuildIsCropped() {
        PlanReview review = new PlanReview(COMPILER, LIB, new PlanReview.Context("bau einen turm",
                RequestHints.parse("bau einen turm", MODULES), HouseSite.free(), false, 12, 1, LintMode.STRICT));
        Verdict v = review.judge(HouseSite.plan(
                "{\"op\":\"walls\",\"block\":\"stone_bricks\",\"from\":[0,0,0],\"to\":[12,200,4]}"), 0);
        Accept a = assertInstanceOf(Accept.class, v, v::toString);
        assertEquals(191, a.build().max().y());
        assertTrue(a.build().warnings().stream().anyMatch(w -> w.contains("gekürzt")), a.build().warnings()::toString);
    }

    /** 16:33–16:35: World Trade Center viermal mit 130 statt 184 Blöcken verworfen – im letzten Versuch gebaut. */
    @Test
    void lastAttemptBuildsAReferenceThatIsTooLowButNotFar() {
        String plan = HouseSite.plan("{\"op\":\"skyscraper\",\"block\":\"stone_bricks\",\"from\":[0,0,0],"
                + "\"to\":[15,0,15],\"floors\":32}");
        // Erster Versuch: Nachbesserung wie bisher.
        assertInstanceOf(PlanReview.Repair.class, judge("bau das world trade center", plan, 0, 184));
        Verdict last = judge("bau das world trade center", plan, 1, 184);
        Accept a = assertInstanceOf(Accept.class, last, last::toString);
        assertTrue(a.repairs().stream().anyMatch(r -> r.contains("statt etwa 184")), a.repairs().toString());
        // Weit darunter (Everest „1 Block hoch“) bleibt eine Absage.
        String flat = HouseSite.plan("{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[9,0,9]}");
        assertInstanceOf(PlanReview.GiveUp.class, judge("bau den mount everest", flat, 1, 184));
    }

    /** 21:11:44: cylinder 33×12×33 massiv – nach der Reparaturrunde weiter massiv, verworfen. */
    @Test
    void solidCylinderFromTheLogIsMadeHollow() {
        String plan = HouseSite.plan("{\"op\":\"cylinder\",\"block\":\"stone_bricks\",\"pos\":[16,0,16],"
                + "\"radius\":16,\"height\":12}");
        Accept a = accepted("bau einen runden turm", plan);
        assertTrue(a.plan().steps().getFirst().hollow());
    }

    /** Eine Box als Vollklotz wird zur geschlossenen Hülle (op hollow), nicht zur oben offenen. */
    @Test
    void solidBoxBecomesAClosedShell() {
        String plan = HouseSite.plan(
                "{\"op\":\"fill\",\"block\":\"stone_bricks\",\"from\":[0,0,0],\"to\":[9,9,9]}");
        Accept a = accepted("bau einen bunker", plan);
        assertEquals(BuildPlan.Op.HOLLOW, a.plan().steps().getFirst().op());
    }

    /** 13:53: 712 Blöcke bei y 8 schwebend (Dach ohne Pfosten) – bekommt Pfeiler, wird gebaut. */
    @Test
    void floatingRoofFromTheLogGetsPillars() {
        String plan = HouseSite.plan(
                "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[14,0,14]}",
                "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[0,8,0],\"to\":[14,8,14]}");
        Accept a = accepted("bau einen unterstand", plan);
        List<CompiledBuild.Change> pillars = a.build().actions().getFirst().changes();
        assertEquals(4 * 7, pillars.size(), "vier Ecken, je y 1..7");
        assertTrue(pillars.stream().allMatch(c -> c.block().id().equals("oak_planks")));
        assertFalse(a.remaining().stream().anyMatch(i -> i.rule() == PlanLinter.Rule.FLOATING),
                a.remaining().toString());
    }
}
