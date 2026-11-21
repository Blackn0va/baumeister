package com.blackn0va.baumeister.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.PlanParser;
import com.blackn0va.baumeister.plan.PlanReview.Accept;
import com.blackn0va.baumeister.plan.PlanReview.Verdict;
import com.blackn0va.baumeister.plan.Pos;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * AK-14 (T-012): Ein set mit genau dem Block, den ein Modul bzw. eine Logikschaltung an dieser Stelle schon hat,
 * fällt weg (PlanAutoFix.moduleCells), statt den Plan als Überbau zu verwerfen. Gemessen auf dev01 seit 2026-11-01:
 * 4 von 14 verworfenen KI-Plänen. Über PlanJudge, damit die echte Bibliothek (Machines) und das Urteil wie im Plugin
 * laufen – die Zellen der Ausgangslampe und des Eingangshebels werden aus dem übersetzten Modul gelesen, nicht
 * hartkodiert, damit ein umgebautes Modul den Test nicht stillschweigend ins Leere laufen lässt.
 */
class ModuleCellFixTest {

    private static final String ORDER = "bau mir einen Redstone Computer";
    private static PlanJudge judge;

    @BeforeAll
    static void setUp() {
        judge = new PlanJudge();
    }

    private static String plan(String... steps) {
        return "{\"design\":\"Probe\",\"reply\":\"Probe\",\"mode\":\"new\",\"steps\":[" + String.join(",", steps)
                + "]}";
    }

    /** Erste Zelle mit diesem Block, die Schritt 0 (das Modul bzw. die Schaltung) setzt. */
    private static Map.Entry<Pos, BlockSpec> cell(String firstStep, String id) throws Exception {
        CompiledBuild build = judge.compiler().compile(PlanParser.parse(plan(firstStep)));
        for (CompiledBuild.Action a : build.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                if (a.step() == 0 && c.block().id().equals(id)) {
                    return Map.entry(c.pos(), c.block());
                }
            }
        }
        throw new AssertionError("kein " + id + " in " + firstStep);
    }

    private static String set(String block, Pos p) {
        return "{\"op\":\"set\",\"block\":\"" + block + "\",\"pos\":[" + p.x() + "," + p.y() + "," + p.z() + "]}";
    }

    private static Verdict judge(String json) {
        // repairAttempts 0: ohne Richten wäre der Überbau sofort eine Absage (GiveUp), wie im letzten Versuch.
        return judge.judge(ORDER, json, 0, 0);
    }

    private static final String REGISTER = "{\"op\":\"module\",\"name\":\"register_4bit\",\"pos\":[0,0,0],"
            + "\"facing\":\"front\"}";
    private static final String LOGIC = "{\"op\":\"logic\",\"expr\":\"Q = A und B oder nicht C\",\"pos\":[0,0,0],"
            + "\"facing\":\"front\"}";

    @Test
    void lampOnTheModulesOwnLampIsDroppedAndBuilt() throws Exception {
        Pos lamp = cell(REGISTER, "redstone_lamp").getKey();
        Verdict v = judge(plan(REGISTER, set("redstone_lamp", lamp)));
        Accept a = assertInstanceOf(Accept.class, v, "Urteil: " + v);
        assertEquals(1, a.plan().steps().size(), "der überflüssige set fällt weg");
        assertTrue(a.repairs().stream().anyMatch(r -> r.contains("Schritt 2: set redstone_lamp weggelassen")
                && r.contains("register_4bit")), "Nachbesserungen: " + a.repairs());
    }

    @Test
    void leverOnTheCircuitsOwnInputLeverIsDroppedAndBuilt() throws Exception {
        Pos lever = cell(LOGIC, "lever").getKey();
        Verdict v = judge(plan(LOGIC, set("lever", lever)));
        Accept a = assertInstanceOf(Accept.class, v, "Urteil: " + v);
        assertEquals(1, a.plan().steps().size());
        assertTrue(a.repairs().stream().anyMatch(r -> r.contains("Schritt 2: set lever weggelassen")
                && r.contains("Logikschaltung")), "Nachbesserungen: " + a.repairs());
    }

    /** Anderer Block auf der Modulzelle: das hätte die Maschine verändert, bleibt Überbau wie bisher. */
    @Test
    void otherBlockOnAModuleCellIsStillRejected() throws Exception {
        Pos lamp = cell(REGISTER, "redstone_lamp").getKey();
        Verdict v = judge(plan(REGISTER, set("glowstone", lamp)));
        assertFalse(v instanceof Accept, "Urteil: " + v);
        assertTrue(v.toString().contains("overwrites 1 block(s) of the module \"register_4bit\""), "Urteil: " + v);
    }

    /** AK-14.2: gleicher Typ, aber ein anderer verlangter Zustand (Hebel anders angebracht) wird nicht gerichtet. */
    @Test
    void sameBlockWithOtherRequestedStateIsStillRejected() throws Exception {
        Map.Entry<Pos, BlockSpec> lever = cell(LOGIC, "lever");
        String face = lever.getValue().props().get("face");
        assertNotNull(face, "Hebel der Schaltung ohne face: " + lever.getValue());
        String other = "ceiling".equals(face) ? "floor" : "ceiling";
        Verdict v = judge(plan(LOGIC, set("lever[face=" + other + "]", lever.getKey())));
        assertFalse(v instanceof Accept, "Urteil: " + v);
        assertTrue(v.toString().contains("overwrites 1 block(s) of the logic circuit"), "Urteil: " + v);
    }
}
