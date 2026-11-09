package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.PlanLinter.Issue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * Bunker und Fort Knox ohne LLM (dev01 2026-10-30: „baue mir einen unterirdische bunkeranlage“ dreimal verworfen;
 * Wunsch).
 */
class BunkerTest {

    private static final Machines LIB = new Machines();
    private static final PlanCompiler COMPILER = new PlanCompiler(
            id -> org.bukkit.Material.getMaterial(id.toUpperCase(Locale.ROOT)) != null, "stone",
            new PlanCompiler.Limits(150_000, 192), LIB);

    private static BuildPlan plan(String prompt) throws PlanException {
        return PlanParser.parse(QuickOrders.match(prompt, LIB, 192).orElseThrow(() -> new AssertionError(
                "kein Schnellauftrag: " + prompt)));
    }

    private static boolean has(CompiledBuild b, Pos p, String id) {
        return b.actions().stream().flatMap(a -> a.changes().stream())
                .anyMatch(c -> c.pos().equals(p) && c.block().id().equals(id));
    }

    @Test
    void theOrdersFromTheChatAreRecognised() throws Exception {
        assertTrue(plan("baue mir hier fort knox").design().startsWith("Fort Knox"));
        assertTrue(plan("bau mir Fort Knox nach unten und oben").design().startsWith("Fort Knox"));
        assertTrue(plan("baue mir einen unterirdische bunkeranlage").design().contains("2 level(s)"));
        assertTrue(plan("bau einen großen Bunker").design().contains("3 level(s)"));
        assertTrue(plan("bau mir einen kleinen Schutzraum").design().contains("1 level(s)"));
    }

    @Test
    void anythingElseStaysWithTheLlm() {
        for (String prompt : List.of("bau ein Fort", "bau einen Bunker mit Kino", "bau ein Haus",
                "bau einen großen kleinen Bunker")) {
            assertTrue(QuickOrders.bunker(prompt, 192).isEmpty(), prompt);
        }
    }

    @Test
    void everyVariantPassesTheBuildCheckWithoutRepair() throws Exception {
        for (String prompt : List.of("bau mir hier fort knox", "bau einen kleinen Bunker", "bau einen Bunker",
                "bau einen großen Bunker")) {
            BuildPlan p = plan(prompt);
            assertTrue(p.steps().size() <= PlanParser.MAX_STEPS, prompt + ": " + p.steps().size() + " Schritte");
            CompiledBuild b = COMPILER.compile(p);
            List<Issue> issues = PlanLinter.lint(p, b, LIB, true);
            assertTrue(issues.stream().noneMatch(PlanReview::severe), prompt + " – Befunde: " + issues);
            assertTrue(b.warnings().isEmpty(), prompt + " – Warnungen: " + b.warnings());
        }
    }

    @Test
    void fortKnoxGoesDownAndUpWithTheRealSize() throws Exception {
        CompiledBuild b = COMPILER.compile(plan("bau mir hier fort knox"));
        // Gebäude 32 × 37 und 13 hoch (y -1 … 12 plus Brüstung), Tresor bis y -14.
        assertTrue(has(b, new Pos(-16, 5, 6), "polished_diorite"));
        assertTrue(has(b, new Pos(15, 5, 42), "polished_diorite"));
        assertTrue(b.min().y() <= -14, "Tresor unter der Erde: " + b.min());
        assertTrue(b.max().y() >= 16, "Wachposten auf dem Dach: " + b.max());
        assertTrue(has(b, new Pos(0, -13, 18), "gold_block"), "Gold im unteren Tresorgeschoss");
        assertTrue(has(b, new Pos(0, -7, 18), "gold_block"), "Gold im oberen Tresorgeschoss");
        // Der Innenraum ist ausgehöhlt (Erde und Stein weg), die Leiter läuft durch alle Decken.
        assertTrue(has(b, new Pos(5, -4, 10), "air"), "Untergeschoss leer");
        for (int y : new int[] {-1, 6, 12}) {
            assertTrue(has(b, new Pos(-11, y, 41), "ladder"), "Leiter durch die Decke bei y " + y);
        }
    }

    @Test
    void bunkerLadderReachesEveryLevel() throws Exception {
        CompiledBuild b = COMPILER.compile(plan("bau einen großen Bunker"));
        // 3 Ebenen: Decken bei -2, -8, -14, Boden bei -20 – die Leiter steht in jeder Decke.
        for (int y : new int[] {-1, -2, -8, -14, -19}) {
            assertTrue(has(b, new Pos(-2, y, 1), "ladder"), "Leiter bei y " + y);
        }
        assertTrue(has(b, new Pos(8, -19, 23), "gold_block"), "Tresorraum auf der untersten Ebene");
        assertEquals(-20, b.min().y());
    }

    /** BuildService.acceptPlan kürzt die Antwort auf 200 Zeichen – sie muss vorher zu Ende sein. */
    @Test
    void repliesFitIntoTheChatLine() throws Exception {
        for (String prompt : List.of("bau mir fort knox", "bau einen großen Bunker", "bau einen kleinen Bunker",
                "bau einen Taschenrechner")) {
            String reply = plan(prompt).reply();
            assertTrue(reply.length() <= 200, prompt + ": " + reply.length() + " Zeichen – " + reply);
            assertTrue(reply.endsWith("."), reply);
        }
    }

    @Test
    void tooSmallBuildLimitFallsBackToTheLlm() {
        assertTrue(QuickOrders.bunker("bau mir fort knox", 40).isEmpty());
        assertTrue(QuickOrders.bunker("bau einen großen Bunker", 20).isEmpty());
    }

    @Test
    void calculatorIsTheTestedTwoBitAdder() throws Exception {
        for (String prompt : List.of("mir einen taschenrechner, der auch tatsächlich rechnen kann, mit redstone",
                "mir einen taschenrechner, der auch tatsächlich rechnen kann, mit redstone. schaue im internet bitte "
                        + "nach einem plan", "bau einen Addierer")) {
            BuildPlan p = plan(prompt);
            assertEquals(1, p.steps().size(), prompt);
            assertEquals(QuickOrders.ADDER, p.steps().getFirst().expr(), prompt);
        }
        assertTrue(QuickOrders.calculator("bau einen Taschenrechner mit Display").isEmpty());
        CompiledBuild b = COMPILER.compile(plan("bau einen taschenrechner"));
        assertTrue(PlanLinter.lint(plan("bau einen taschenrechner"), b, LIB, true).stream()
                .noneMatch(PlanReview::severe));
    }
}
