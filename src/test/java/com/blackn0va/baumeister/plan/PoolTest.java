package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.PlanLinter.Issue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/** Pool ohne LLM (dev01 2026-11-04 18:19–19:12: fünf Anläufe, „das ist aber ein komischer Pool“). */
class PoolTest {

    private static final Machines LIB = new Machines();
    private static final PlanCompiler COMPILER = new PlanCompiler(
            id -> org.bukkit.Material.getMaterial(id.toUpperCase(Locale.ROOT)) != null, "stone",
            new PlanCompiler.Limits(150_000, 192), LIB);

    private static BuildPlan plan(String prompt) throws PlanException {
        return PlanParser.parse(QuickOrders.match(prompt, LIB, 192).orElseThrow(() -> new AssertionError(
                "kein Schnellauftrag: " + prompt)));
    }

    @Test
    void theOrdersFromTheLogAreRecognised() throws Exception {
        assertTrue(plan("Bau mir einen Pool.").design().startsWith("swimming pool 7x11"));
        assertTrue(plan("Einen Pool").design().startsWith("swimming pool"));
        assertTrue(plan("bau noch einen großen Pool").design().startsWith("swimming pool 11x17"));
        assertTrue(plan("ein kleines Schwimmbecken mit Leiter").design().startsWith("swimming pool 5x7"));
    }

    @Test
    void ordersWithMoreWishesStayWithTheLlm() {
        for (String prompt : List.of("bau einen Pool mit Rutsche", "baue mir zu dem Bunker noch einen Pool",
                "bau einen Pool aus Glas", "bau ein Haus mit Pool", "bau einen großen kleinen Pool")) {
            assertTrue(QuickOrders.match(prompt, LIB, 192).isEmpty(), prompt);
        }
    }

    @Test
    void everySizeBuildsWithoutFindingsAndHasOpenWater() throws Exception {
        for (String prompt : List.of("bau einen kleinen Pool", "bau einen Pool", "bau einen großen Pool")) {
            BuildPlan p = plan(prompt);
            CompiledBuild b = COMPILER.compile(p);
            List<Issue> issues = PlanLinter.lint(p, b, LIB, true);
            assertTrue(issues.stream().noneMatch(PlanReview::severe), prompt + " – Befunde: " + issues);
            assertTrue(b.warnings().isEmpty(), prompt + " – Warnungen: " + b.warnings());
            long water = b.actions().stream().flatMap(a -> a.changes().stream())
                    .filter(c -> c.block().id().equals("water")).count();
            assertTrue(water > 50, prompt + ": nur " + water + " Wasser");
            // Oberfläche frei: über dem Wasser bei y −1 (Mitte) bleibt Luft
            Pos surface = new Pos(0, -1, 6);
            Pos above = new Pos(0, 0, 6);
            assertTrue(last(b, surface).equals("water"), prompt + ": Oberfläche " + last(b, surface));
            assertEquals("air", last(b, above), prompt);
            assertFalse(last(b, new Pos(0, -1, 3)).equals("air"), "Leiter an der Vorderwand");
        }
    }

    @Test
    void tooSmallLimitFallsBackToTheLlm() {
        assertTrue(Pool.plan(Pool.Size.LARGE, 16).isEmpty());
    }

    /** Zuletzt gesetzter Block an p (ohne Zustand). */
    private static String last(CompiledBuild b, Pos p) {
        String out = "nichts";
        for (var a : b.actions()) {
            for (var c : a.changes()) {
                if (c.pos().equals(p)) {
                    out = c.block().id();
                }
            }
        }
        return out;
    }
}
