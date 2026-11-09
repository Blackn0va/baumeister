package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.BuildPlan.Op;
import com.blackn0va.baumeister.plan.BuildPlan.Step;
import com.blackn0va.baumeister.plan.PlanLinter.Issue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * Maschinenhalle ohne LLM (dev01 2026-10-30, „bau mir eine fette maschine“: Gatter im Steinklotz, verworfen nach 214 s;
 * mit geschärftem Prompt Anlagen bei x 0, 2, 4 … übereinander).
 */
class MachineHallTest {

    private static final Machines LIB = new Machines();
    /** Echte Block-IDs wie in LlmProbe: ohne Server reicht der Enum-Name. */
    private static final PlanCompiler COMPILER = new PlanCompiler(
            id -> org.bukkit.Material.getMaterial(id.toUpperCase(Locale.ROOT)) != null, "stone",
            new PlanCompiler.Limits(150_000, 192), LIB);

    private static BuildPlan plan(String prompt) throws PlanException {
        return PlanParser.parse(QuickOrders.match(prompt, LIB, 192).orElseThrow(() -> new AssertionError(
                "kein Schnellauftrag: " + prompt)));
    }

    private static List<Step> modules(BuildPlan p) {
        return p.steps().stream().filter(s -> s.op() == Op.MODULE).toList();
    }

    @Test
    void fatMachineIsAHallWithEveryUsefulMachine() throws Exception {
        BuildPlan p = plan("bau mir eine fette maschine");
        assertEquals(MachineHall.USEFUL, modules(p).stream().map(Step::name).toList());
        assertEquals(3, modules(plan("bau eine Maschine")).size());
        assertEquals(2, modules(plan("eine kleine Maschine")).size());
        assertEquals(6, modules(plan("bau mir bitte eine riesige Maschinenhalle")).size());
    }

    @Test
    void machinesNeverOverlapAndKeepTwoBlocksApart() throws Exception {
        List<Step> ms = modules(plan("bau mir eine fette maschine"));
        for (int i = 1; i < ms.size(); i++) {
            Step prev = ms.get(i - 1);
            int prevEnd = prev.pos().x() + LIB.find(prev.name()).orElseThrow().size().x() - 1;
            int gap = ms.get(i).pos().x() - prevEnd - 1;
            assertTrue(gap >= 2, prev.name() + " → " + ms.get(i).name() + ": Abstand " + gap);
        }
    }

    @Test
    void hallPassesTheBuildCheckWithoutAnyRepair() throws Exception {
        BuildPlan p = plan("bau mir eine fette maschine");
        CompiledBuild b = COMPILER.compile(p);
        List<Issue> issues = PlanLinter.lint(p, b, LIB, true);
        assertTrue(issues.stream().noneMatch(PlanReview::severe), "Befunde: " + issues);
        assertTrue(b.warnings().isEmpty(), "Warnungen: " + b.warnings());
    }

    @Test
    void anythingMoreSpecificStaysWithTheLlm() {
        for (String prompt : List.of("bau eine Maschine die Kekse backt", "eine Maschine aus Gold",
                "bau eine große kleine Maschine", "bau ein Haus")) {
            assertTrue(QuickOrders.machineHall(prompt, LIB, 192).isEmpty(), prompt);
        }
        // Ein Bauteil per Name bleibt der bisherige Schnellweg (ein Modul).
        assertTrue(QuickOrders.match("bau ein und_gatter", LIB, 192).orElseThrow().contains("und_gatter"));
    }

    /** „bau mir einen Prozessor aus Redstone“ (2026-10-31): alle CPU-Bausteine, Bauprüfung ohne Befund. */
    @Test
    void processorWorkshopHoldsEveryCpuBuildingBlock() throws Exception {
        for (String prompt : List.of("bau mir einen prozessor aus redstone", "bau einen Computer", "bau eine CPU")) {
            BuildPlan p = plan(prompt);
            assertEquals(MachineHall.CPU, modules(p).stream().map(Step::name).toList(), prompt);
            assertTrue(p.reply().length() <= 200, p.reply());
            CompiledBuild b = COMPILER.compile(p);
            List<Issue> issues = PlanLinter.lint(p, b, LIB, true);
            assertTrue(issues.stream().noneMatch(PlanReview::severe), "Befunde: " + issues);
        }
        assertTrue(QuickOrders.cpuWorkshop("bau einen Computer mit Bildschirm", LIB, 192).isEmpty());
        assertTrue(QuickOrders.match("bau mir einen rechner", LIB, 192).orElseThrow().contains("\"logic\""),
                "Rechner bleibt der Taschenrechner");
    }

    @Test
    void tooSmallBuildLimitFallsBackToTheLlm() {
        assertTrue(QuickOrders.machineHall("bau mir eine fette maschine", LIB, 20).isEmpty());
    }
}
