package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.logic.LogicCircuit;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Logik-Schnellaufträge flach und in Stockwerken (Wunsch 2026-11-07): Taschenrechner und Dekoder ohne LLM. Die
 * Schaltungen selbst sind auf dem
 * Spigot-Testserver mit voller Wahrheitstabelle geprüft (LogicProbe „3d“, 2026-11-07).
 */
class LogicOrdersTest {

    private static final Machines LIB = new Machines();
    private static final PlanCompiler COMPILER = new PlanCompiler(
            id -> org.bukkit.Material.getMaterial(id.toUpperCase(Locale.ROOT)) != null, "stone",
            new PlanCompiler.Limits(150_000, 192), LIB);

    private static BuildPlan plan(String prompt) throws PlanException {
        return PlanParser.parse(QuickOrders.match(prompt, LIB, 192).orElseThrow(() -> new AssertionError(
                "kein Schnellauftrag: " + prompt)));
    }

    /** Baut ohne schweren Befund, Antwort passt in die 200 Zeichen von BuildService.acceptPlan. */
    private static void buildsCleanly(BuildPlan p) throws Exception {
        CompiledBuild b = COMPILER.compile(p);
        assertTrue(PlanLinter.lint(p, b, LIB, true).stream().noneMatch(PlanReview::severe), p.design());
        assertTrue(p.reply().length() <= 200, p.reply().length() + " Zeichen – " + p.reply());
        assertTrue(p.reply().endsWith("."), p.reply());
    }

    @Test
    void calculatorInThreeDPutsEveryResultBitOnItsOwnFloor() throws Exception {
        for (String prompt : List.of("bau einen Taschenrechner in 3D", "bau mir einen Taschenrechner übereinander",
                "bau einen gestapelten Taschenrechner", "bau einen Taschenrechner als Turm",
                "bau einen 3-D Taschenrechner", "bau einen kompakten Taschenrechner auf mehreren Ebenen")) {
            BuildPlan p = plan(prompt);
            assertEquals("3d " + QuickOrders.ADDER, p.steps().getFirst().expr(), prompt);
            assertTrue(p.reply().endsWith("3 Stockwerke, Lampen hinten, unten zuerst: S0 | S1 | C = 1, 2, 4."),
                    p.reply());
            buildsCleanly(p);
        }
        BuildPlan flat = plan("bau einen Taschenrechner");
        assertEquals(QuickOrders.ADDER, flat.steps().getFirst().expr());
        assertTrue(flat.reply().endsWith("Lampen hinten von links: S0, S1, C = 1, 2, 4."), flat.reply());
    }

    @Test
    void decoderStacksOnlyWhenAskedOrWhenItGetsTooWide() throws Exception {
        BuildPlan auto = plan("bau mir einen 3 zu 8 Dekoder");
        assertEquals(QuickOrders.DECODER_3_8, auto.steps().getFirst().expr(), "„3“ allein heißt nicht 3D");
        assertTrue(auto.reply().endsWith("2 Stockwerke, Lampen hinten von links, unten zuerst: D0, D1, D2, D3 | "
                + "D4, D5, D6, D7."), auto.reply());
        buildsCleanly(auto);
        BuildPlan small = plan("bau einen 2 zu 4 Dekoder");
        assertEquals(QuickOrders.DECODER_2_4, small.steps().getFirst().expr());
        assertTrue(small.reply().endsWith("Lampen hinten von links: D0, D1, D2, D3."), small.reply());
        buildsCleanly(small);
        BuildPlan tower = plan("bau einen Dekoder in 3D");
        assertEquals("3d " + QuickOrders.DECODER_3_8, tower.steps().getFirst().expr());
        assertTrue(tower.reply().contains("4 Stockwerke"), tower.reply());
        buildsCleanly(tower);
        assertTrue(QuickOrders.decoder("bau einen Dekoder mit Display").isEmpty(), "fremdes Wort → LLM");
        assertTrue(QuickOrders.decoder("bau einen Turm").isEmpty(), "ohne Dekoder-Wort kein Dekoder");
        assertTrue(QuickOrders.calculator("bau einen Turm").isEmpty(), "ohne Rechner-Wort kein Rechner");
    }

    @Test
    void decoderLightsExactlyOneLampForEveryLeverCombination() {
        for (String expr : List.of(QuickOrders.DECODER_3_8, QuickOrders.DECODER_2_4)) {
            LogicCircuit.Circuit c = LogicCircuit.compile(expr);
            assertEquals(List.of("A", "B", "C").subList(0, c.inputs().size()), c.inputs(), expr);
            // Belegung b in Tabellen-Reihenfolge (A niedrigstes Bit) → genau Lampe D<b> an.
            for (Map.Entry<String, Map<String, Boolean>> out : LogicCircuit.truthTables(c).entrySet()) {
                int lamp = Integer.parseInt(out.getKey().substring(1));
                int b = 0;
                for (Map.Entry<String, Boolean> row : out.getValue().entrySet()) {
                    assertEquals(b == lamp, row.getValue(), out.getKey() + " bei " + row.getKey());
                    b++;
                }
            }
        }
    }
}
