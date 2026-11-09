package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.PlanReview.Accept;
import com.blackn0va.baumeister.plan.PlanReview.GiveUp;
import com.blackn0va.baumeister.plan.PlanReview.LintMode;
import com.blackn0va.baumeister.plan.PlanReview.Repair;
import com.blackn0va.baumeister.plan.PlanReview.Verdict;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Einbindung des PlanLinters in die Reparaturrunde, mit einer Attrappe für das LLM (feste Antworten, Aufrufe
 * gezählt). {@link #converse} ist ein NACHBAU der Schleife in BuildService.handleReply (Repair → genau ein weiterer
 * Aufruf mit Repair.llmRequest, sonst Ende), nicht die Schleife selbst: handleReply braucht Bukkit (Chat, NPC,
 * runSync). Geprüft ist hier PlanReview.judge mit dem Versuchszähler; ob handleReply judge so aufruft, belegt kein
 * Test – wer dort die Verzweigung ändert, muss diesen Nachbau mitziehen.
 */
class PlanReviewTest {

    /** Das 7×7-Haus aus dem System-Prompt (wie PlanLinterTest.HOUSE) – sauber, darf nichts kosten. */
    private static final String[] HOUSE = {
        "{\"op\":\"fill\",\"block\":\"cobblestone\",\"from\":[0,0,0],\"to\":[6,0,6]}",
        "{\"op\":\"walls\",\"block\":\"oak_planks\",\"from\":[0,1,0],\"to\":[6,3,6]}",
        "{\"op\":\"fill\",\"block\":\"oak_log\",\"from\":[0,1,0],\"to\":[0,3,0]}",
        "{\"op\":\"fill\",\"block\":\"oak_log\",\"from\":[6,1,0],\"to\":[6,3,0]}",
        "{\"op\":\"fill\",\"block\":\"oak_log\",\"from\":[0,1,6],\"to\":[0,3,6]}",
        "{\"op\":\"fill\",\"block\":\"oak_log\",\"from\":[6,1,6],\"to\":[6,3,6]}",
        "{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[3,1,0],\"facing\":\"front\"}",
        "{\"op\":\"set\",\"block\":\"glass_pane\",\"pos\":[1,2,0]}",
        "{\"op\":\"set\",\"block\":\"glass_pane\",\"pos\":[5,2,0]}",
        "{\"op\":\"set\",\"block\":\"glass_pane\",\"pos\":[0,2,3]}",
        "{\"op\":\"set\",\"block\":\"glass_pane\",\"pos\":[6,2,3]}",
        "{\"op\":\"roof\",\"block\":\"oak_stairs\",\"from\":[0,4,0],\"to\":[6,4,6],\"shape\":\"gable\","
                + "\"overhang\":1,\"gable_block\":\"oak_planks\"}",
        "{\"op\":\"set\",\"block\":\"lantern\",\"pos\":[3,1,4]}"};

    /** Turm als Vollzylinder – so baute das 7B ihn gemessen „mal hohl, mal massiv“. */
    private static final String SOLID_TOWER = HouseSite.plan(
            "{\"op\":\"cylinder\",\"block\":\"stone_bricks\",\"pos\":[5,0,5],\"radius\":4,\"height\":12}");
    /**
     * Becken mit Deckel: Wasser eingemauert. Ein schwerer Baufehler, den PlanAutoFix nicht richtet (welche Seite
     * offen sein soll, weiß nur der Entwurf) – daran prüfen die Tests die Reparaturrunde, seit Vollklötze und
     * Schwebendes ohne LLM behoben werden (2026-10-31).
     */
    private static final String[] SEALED_POOL_STEPS = {
        "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[4,0,4]}",
        "{\"op\":\"walls\",\"block\":\"stone\",\"from\":[0,1,0],\"to\":[4,2,4]}",
        "{\"op\":\"fill\",\"block\":\"water\",\"from\":[1,1,1],\"to\":[3,2,3]}",
        "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,3,0],\"to\":[4,3,4]}"};
    private static final String SEALED_POOL = HouseSite.plan(SEALED_POOL_STEPS);
    private static final String OPEN_POOL = HouseSite.plan(SEALED_POOL_STEPS[0], SEALED_POOL_STEPS[1],
            SEALED_POOL_STEPS[2]);

    private static final String HOLLOW_TOWER = HouseSite.plan(
            "{\"op\":\"cylinder\",\"block\":\"stone_bricks\",\"pos\":[5,0,5],\"radius\":4,\"height\":12,"
                    + "\"hollow\":true}");

    /** LLM-Attrappe: liefert die Antworten der Reihe nach und merkt sich jede Nachbesserungs-Bitte. */
    private static final class ScriptedLlm {
        final Deque<String> replies;
        final List<String> requests = new ArrayList<>();
        int calls;

        ScriptedLlm(String... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        String complete(String request) {
            calls++;
            if (request != null) {
                requests.add(request);
            }
            return replies.isEmpty() ? replies.toString() : replies.poll();
        }
    }

    private static Verdict converse(PlanReview review, ScriptedLlm llm) {
        String reply = llm.complete(null);
        int attempt = 0;
        while (true) {
            Verdict v = review.judge(reply, attempt);
            if (!(v instanceof Repair r)) {
                return v;
            }
            reply = llm.complete(r.llmRequest());
            attempt++;
        }
    }

    private static PlanReview review(String prompt, SiteSurvey survey, LintMode mode) {
        return new PlanReview(HouseSite.COMPILER, ModuleLibrary.EMPTY, new PlanReview.Context(prompt,
                RequestHints.parse(prompt, Set.of()), survey, false, 12, 1, mode));
    }

    // ---------------------------------------------------------------- Host-Log 2026-11-08

    /**
     * Llama 3.2 3B setzte 33 × „set elytra“ – ein Gegenstand, kein Block. Vorher wurde daraus still der Ersatzblock
     * (Steinsäule); jetzt zuerst eine Reparaturrunde, und erst ohne freie Runde bleibt es beim Ersatzblock.
     */
    @Test
    void itemsAsBlocksGoBackToTheLlmFirst() {
        String[] steps = java.util.Arrays.copyOf(HOUSE, HOUSE.length + 1);
        steps[HOUSE.length] = "{\"op\":\"set\",\"block\":\"elytra\",\"pos\":[3,1,3]}";
        PlanReview r = review("bau ein kleines Haus", HouseSite.free(), LintMode.STRICT);
        Repair first = assertInstanceOf(Repair.class, r.judge(HouseSite.plan(steps), 0));
        assertTrue(first.reason().contains("elytra"), first.reason());
        assertInstanceOf(Accept.class, r.judge(HouseSite.plan(steps), 1), "letzte Runde: Ersatzblock wie bisher");
        assertEquals(List.of("elytra", "foo"), PlanReview.unknownBlocks(List.of("Unbekannter Block elytra → stone",
                "Block stair als oak_stairs gelesen", "Unbekannter Block foo → stone",
                "Unbekannter Block elytra → stone")));
    }

    /**
     * Host-Log 2026-11-08 (Qwen2.5 14B, „Kanalbrücke“): bridge ohne block – vorher „Schritt 1: block fehlt“, die
     * Reparaturrunde lieferte denselben Fehler, verworfen. Jetzt nimmt der Baumeister die Vorgabe und baut.
     */
    @Test
    void formWithoutMaterialGetsTheDefaultInsteadOfARepairRound() {
        String json = HouseSite.plan(
                "{\"op\":\"bridge\",\"from\":[-10,0,0],\"to\":[10,0,20],\"width\":5,\"style\":\"arch\"}",
                "{\"op\":\"fill\",\"block\":\"cobblestone\",\"from\":[-11,-1,0],\"to\":[-11,-1,20]}");
        Accept a = assertInstanceOf(Accept.class, review("bau mir eine Kanalbrücke", HouseSite.free(),
                LintMode.STRICT).judge(json, 0));
        assertTrue(a.repairs().stream().anyMatch(r -> r.contains("bridge ohne Material – stone_bricks")),
                a.repairs().toString());
        assertEquals("stone_bricks", a.plan().steps().getFirst().block());
    }

    /** Pyramide als Schnellauftrag (QuickForms): der strenge Prüfer nimmt sie ohne Runde an – keine Lage schwebt. */
    @Test
    void stepPyramidPassesTheStrictReviewer() {
        String json = QuickOrders.match("bau mir eine Pyramide aus Steinziegeln", Set.of(), 192).orElseThrow();
        Accept a = assertInstanceOf(Accept.class, review("bau mir eine Pyramide aus Steinziegeln", HouseSite.free(),
                LintMode.STRICT).judge(json, 0));
        assertEquals(15, a.build().max().x() - a.build().min().x() + 1);
        assertEquals(8, a.build().max().y() - a.build().min().y() + 1);
        assertTrue(a.remaining().stream().noneMatch(PlanReview::severe), a.remaining().toString());
        assertTrue(a.repairs().isEmpty(), "ohne Selbstkorrektur: " + a.repairs());
        // Eingang vorne in der Mitte, zwei hoch begehbar: auch durch den Ring der zweiten Lage (z = 1).
        Map<Pos, String> last = new java.util.HashMap<>();
        a.build().actions().forEach(act -> act.changes().forEach(c -> last.put(c.pos(), c.block().id())));
        int mx = a.build().min().x() + 7;
        int y0 = a.build().min().y();
        int z0 = a.build().min().z();
        for (Pos p : List.of(new Pos(mx, y0, z0), new Pos(mx, y0 + 1, z0), new Pos(mx, y0 + 1, z0 + 1))) {
            assertEquals("air", last.getOrDefault(p, "air"), "Eingang zu bei " + p);
        }
        assertEquals("stone_bricks", last.get(new Pos(mx - 1, y0 + 1, z0 + 1)), "Ring der zweiten Lage daneben");
    }

    // ---------------------------------------------------------------- Einbindung

    @Test
    void cleanPlanCostsNoSecondLlmCall() {
        ScriptedLlm llm = new ScriptedLlm(HouseSite.plan(HOUSE));
        Verdict v = converse(review("bau ein kleines Haus", HouseSite.free(), LintMode.STRICT), llm);
        assertInstanceOf(Accept.class, v, () -> v.toString());
        assertEquals(1, llm.calls, "ein sauberer Plan braucht genau einen Aufruf");
    }

    /**
     * Vollklotz (dev01 2026-10-30: Zylinder 33×12×33 auch nach der Reparaturrunde massiv, verworfen): Der Baumeister
     * macht ihn selbst hohl, ohne zweiten LLM-Aufruf. Vorher genau eine Reparaturrunde.
     */
    @Test
    void solidTowerIsMadeHollowWithoutAnotherLlmCall() {
        ScriptedLlm llm = new ScriptedLlm(SOLID_TOWER, HOLLOW_TOWER);
        Accept a = assertInstanceOf(Accept.class, converse(review("bau einen runden Turm aus Steinziegeln",
                HouseSite.free(), LintMode.STRICT), llm));
        assertEquals(1, llm.calls, "kein zweiter Aufruf");
        assertTrue(a.repairs().stream().anyMatch(r -> r.contains("innen hohl")), a.repairs().toString());
        assertTrue(a.plan().steps().getFirst().hollow());
        assertTrue(a.build().actions().stream().flatMap(x -> x.changes().stream())
                .noneMatch(c -> c.pos().equals(new Pos(5, 6, 5))), "Mitte des Turms leer");
    }

    @Test
    void sealedPoolGetsExactlyOneRepairWithFeedback() {
        ScriptedLlm llm = new ScriptedLlm(SEALED_POOL, OPEN_POOL);
        PlanReview review = review("bau einen Brunnen", HouseSite.free(), LintMode.STRICT);
        Repair first = assertInstanceOf(Repair.class, review.judge(SEALED_POOL, 0));
        assertTrue(first.playerText().contains("Wasser eingemauert"), first.playerText());
        Verdict v = converse(review, llm);
        assertInstanceOf(Accept.class, v, () -> v.toString());
        assertEquals(2, llm.calls, "genau eine Nachbesserung");
        assertEquals(1, llm.requests.size());
        String request = llm.requests.getFirst();
        assertTrue(request.contains("is sealed in"), request);
        assertTrue(request.startsWith("The plan has building errors:"), request);
    }

    @Test
    void strictGivesUpAfterTheOneRepairLikeTheOverbuildGuard() {
        ScriptedLlm llm = new ScriptedLlm(SEALED_POOL, SEALED_POOL, OPEN_POOL);
        Verdict v = converse(review("bau einen Brunnen", HouseSite.free(), LintMode.STRICT), llm);
        GiveUp g = assertInstanceOf(GiveUp.class, v);
        assertEquals(2, llm.calls, "eine Nachbesserung, dann Schluss");
        assertFalse(g.siteCheck(), "Entwurfsfehler: beim Hochhaus greift danach der Rückfall");
        assertTrue(g.playerText().contains("Wasser eingemauert"), g.playerText());
    }

    @Test
    void repairModeBuildsAfterTheOneRepairAndKeepsTheFindings() {
        ScriptedLlm llm = new ScriptedLlm(SEALED_POOL, SEALED_POOL);
        Accept a = assertInstanceOf(Accept.class, converse(review("bau einen Brunnen", HouseSite.free(),
                LintMode.REPAIR), llm));
        assertEquals(2, llm.calls);
        assertTrue(a.remaining().stream().anyMatch(PlanReview::severe), a.remaining().toString());
    }

    @Test
    void offModeIsTheOldBehaviour() {
        ScriptedLlm llm = new ScriptedLlm(SOLID_TOWER);
        assertInstanceOf(Accept.class, converse(review("bau einen Turm", HouseSite.free(), LintMode.OFF), llm));
        assertEquals(1, llm.calls);
    }

    /**
     * Schwebendes: Bis 32 Blöcke über dem Halt bekommt es Pfeiler (dev01 2026-10-30/31: 93, 546, 712 Blöcke,
     * jedes Mal nach der Reparaturrunde verworfen); höher sitzt es falsch und geht weiter ans LLM.
     */
    @Test
    void floatingPartsGetPillarsButTooHighOnesGoToRepairAndLeakingWaterOnlyToTheLog() {
        List<String> floating = new ArrayList<>(List.of(HOUSE));
        floating.add("{\"op\":\"fill\",\"block\":\"stone\",\"from\":[10,5,10],\"to\":[11,5,11]}");
        Accept held = assertInstanceOf(Accept.class, review("bau ein Haus", HouseSite.free(), LintMode.STRICT)
                .judge(HouseSite.plan(floating.toArray(String[]::new)), 0));
        assertTrue(held.repairs().stream().anyMatch(r -> r.contains("Stützen")), held.repairs().toString());
        // vier Ecken, je von y 4 bis y 0
        assertEquals(20, held.build().actions().getFirst().changes().size());

        List<String> high = new ArrayList<>(List.of(HOUSE));
        high.add("{\"op\":\"fill\",\"block\":\"stone\",\"from\":[10,40,10],\"to\":[11,40,11]}");
        Repair r = assertInstanceOf(Repair.class, review("bau ein Haus", HouseSite.free(), LintMode.STRICT)
                .judge(HouseSite.plan(high.toArray(String[]::new)), 0));
        assertTrue(r.llmRequest().contains("float in the air"), r.llmRequest());

        // Becken mit offener Seite: Wasser läuft aus – leicht, wird gebaut.
        String pool = HouseSite.plan(
                "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[4,0,4]}",
                "{\"op\":\"walls\",\"block\":\"stone\",\"from\":[0,1,0],\"to\":[4,2,4]}",
                "{\"op\":\"fill\",\"block\":\"air\",\"from\":[2,1,0],\"to\":[2,1,0]}",
                "{\"op\":\"fill\",\"block\":\"water\",\"from\":[1,1,1],\"to\":[3,1,3]}");
        ScriptedLlm llm = new ScriptedLlm(pool);
        Accept a = assertInstanceOf(Accept.class, converse(review("bau einen Brunnen", HouseSite.free(),
                LintMode.STRICT), llm));
        assertEquals(1, llm.calls);
        assertTrue(a.remaining().stream().anyMatch(i -> i.rule() == PlanLinter.Rule.LEAKING_WATER),
                a.remaining().toString());
    }

    /**
     * dev01 2026-10-30, Llama 3.1 8B: ein einzelner schwebender Block (y 31) und eine Glasscheibe vor der Wand kosteten
     * je zwei Läufe à 40 s und die Absage. Jetzt lässt der Baumeister beides weg und baut nach EINEM Aufruf. Eine
     * schwebende Gruppe aus vier Blöcken geht weiter ans LLM (floatingPartsGoToRepair…).
     */
    @Test
    void strayBlockAndPaneOutsideTheWallAreDroppedWithoutAnotherLlmCall() {
        List<String> steps = new ArrayList<>(List.of(HOUSE));
        steps.add("{\"op\":\"set\",\"block\":\"stone\",\"pos\":[3,12,3]}");
        steps.add("{\"op\":\"set\",\"block\":\"glass_pane\",\"pos\":[3,1,-3]}");
        ScriptedLlm llm = new ScriptedLlm(HouseSite.plan(steps.toArray(String[]::new)));
        Verdict v = converse(review("bau ein kleines Haus", HouseSite.free(), LintMode.STRICT), llm);
        Accept a = assertInstanceOf(Accept.class, v, () -> v.toString());
        assertEquals(1, llm.calls, "keine Reparaturrunde für Kleinfehler");
        boolean anyLeft = a.build().actions().stream().flatMap(x -> x.changes().stream())
                .anyMatch(c -> c.pos().equals(new Pos(3, 12, 3)) || c.pos().equals(new Pos(3, 1, -3)));
        assertFalse(anyLeft, "beide weggelassen");
        assertTrue(a.build().warnings().stream().anyMatch(w -> w.contains("danebengesetzt")),
                a.build().warnings()::toString);
    }

    /**
     * dev01 2026-10-30: Fackeln auf einer Mauerkrone ohne Halt kosteten eine Reparaturrunde (61 s) und danach die
     * Absage. Jetzt fallen sie selbst weg, gebaut wird nach EINEM Aufruf.
     */
    @Test
    void unsupportedLightsAreDroppedWithoutAnotherLlmCall() {
        List<String> steps = new ArrayList<>(List.of(HOUSE));
        steps.add("{\"op\":\"set\",\"block\":\"torch\",\"pos\":[3,6,3]}");
        steps.add("{\"op\":\"set\",\"block\":\"lantern\",\"pos\":[1,1,9]}");
        ScriptedLlm llm = new ScriptedLlm(HouseSite.plan(steps.toArray(String[]::new)));
        Verdict v = converse(review("bau ein kleines Haus", HouseSite.free(), LintMode.STRICT), llm);
        Accept a = assertInstanceOf(Accept.class, v, () -> v.toString());
        assertEquals(1, llm.calls, "keine Reparaturrunde für Fackeln ohne Halt");
        boolean anyLeft = a.build().actions().stream().flatMap(x -> x.changes().stream())
                .anyMatch(c -> c.pos().equals(new Pos(3, 6, 3)) || c.pos().equals(new Pos(1, 1, 9)));
        assertTrue(!anyLeft, "beide weggelassen");
        assertTrue(a.build().warnings().stream().anyMatch(w -> w.contains("ohne Halt")), a.build().warnings()::toString);
    }

    /**
     * dev01 2026-10-30: Drei Meldungen derselben Art (je Schritt eine) belegten alle Plätze, der eigentliche Fehler
     * kam nie an. Jetzt nennt die Rückmeldung zuerst jede Fehlerart einmal.
     */
    @Test
    void feedbackNamesEveryKindOfErrorFirst() {
        List<String> steps = new ArrayList<>();
        steps.add("{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[20,0,0]}");
        for (int x = 0; x <= 12; x += 3) {
            steps.add("{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[" + x + ",1,0],\"facing\":\"front\"}");
        }
        // Eingemauertes Becken weiter hinten (der frühere Vollklotz richtet PlanAutoFix seit 2026-10-31 selbst).
        steps.add("{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,15],\"to\":[4,0,19]}");
        steps.add("{\"op\":\"walls\",\"block\":\"stone\",\"from\":[0,1,15],\"to\":[4,2,19]}");
        steps.add("{\"op\":\"fill\",\"block\":\"water\",\"from\":[1,1,16],\"to\":[3,2,18]}");
        steps.add("{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,3,15],\"to\":[4,3,19]}");
        Repair r = assertInstanceOf(Repair.class, review("bau Türen und einen Brunnen", HouseSite.free(),
                LintMode.STRICT).judge(HouseSite.plan(steps.toArray(String[]::new)), 0));
        assertTrue(r.reason().contains("is not set in a wall"), r.reason());
        assertTrue(r.reason().contains("is sealed in"), "das Becken muss beim LLM ankommen: " + r.reason());
    }

    /**
     * Recherche (ReferenceLookup): Der Kölner Dom ist 157 Blöcke hoch. Ein Plan mit dem 7×7-Haus (6 Blöcke) geht zur
     * Nachbesserung; derselbe Plan ohne Vorgabe wird gebaut.
     */
    @Test
    void researchedHeightIsHeldAgainstThePlan() throws Exception {
        String house = HouseSite.plan(HOUSE);
        Repair r = assertInstanceOf(Repair.class, new PlanReview(HouseSite.COMPILER, ModuleLibrary.EMPTY,
                new PlanReview.Context("bau den Kölner Dom", RequestHints.parse("bau den Kölner Dom", Set.of()),
                        HouseSite.free(), false, 12, 1, LintMode.STRICT, 157)).judge(house, 0));
        assertTrue(r.reason().contains("about 157 blocks tall"), r.reason());
        assertInstanceOf(Accept.class, review("bau ein kleines Haus", HouseSite.free(), LintMode.STRICT)
                .judge(house, 0));
        assertTrue(PlanReview.heightProblem(HouseSite.COMPILER.compile(PlanParser.parse(house)), 7).isEmpty(),
                "innerhalb von 3 Blöcken bzw. 20 % ist genug");
    }

    @Test
    void feedbackStaysShort() {
        List<String> steps = new ArrayList<>();
        steps.add("{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[20,0,0]}");
        for (int x = 0; x <= 18; x += 3) {
            steps.add("{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[" + x + ",1,0],\"facing\":\"front\"}");
        }
        Repair r = assertInstanceOf(Repair.class, review("bau Türen", HouseSite.free(), LintMode.STRICT)
                .judge(HouseSite.plan(steps.toArray(String[]::new)), 0));
        assertEquals(PlanReview.MAX_FEEDBACK, r.reason().split("is not set in a wall").length - 1, r.reason());
        assertTrue(r.reason().contains("(4 more problem(s)"), r.reason());
    }

    // ---------------------------------------------------------------- Bauplatz

    /**
     * Anbau rechts mit Tür in der gemeinsamen Wand x=6: Die Wandblöcke daneben setzt der Plan nicht (geklemmt, der
     * Altbau steht) – ohne die Bestandsblöcke als Halt meldete der Linter „not set in a wall“.
     */
    @Test
    void doorInTheSharedWallIsNotAnError() {
        String annex = HouseSite.plan(
                "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[7,0,8],\"to\":[10,0,12]}",
                "{\"op\":\"walls\",\"block\":\"oak_planks\",\"from\":[6,1,8],\"to\":[10,3,12]}",
                "{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[6,1,10],\"facing\":\"right\"}");
        Verdict v = review("bau rechts eine Werkstatt an das Haus an", HouseSite.withHouse(), LintMode.STRICT)
                .judge(annex, 0);
        Accept a = assertInstanceOf(Accept.class, v, () -> v.toString());
        assertTrue(a.remaining().stream().noneMatch(i -> i.rule() == PlanLinter.Rule.DOOR), a.remaining().toString());
    }

    /** Überbau-Schutz unverändert scharf: ein Durchbruch über die ganze Wand geht zurück, danach Absage. */
    @Test
    void overbuildGuardStaysFailClosedAfterTheFit() {
        String breakthrough = HouseSite.plan(
                "{\"op\":\"walls\",\"block\":\"stone_bricks\",\"from\":[6,1,8],\"to\":[10,3,12]}",
                "{\"op\":\"fill\",\"block\":\"air\",\"from\":[6,1,8],\"to\":[6,3,12]}");
        PlanReview review = review("bau rechts eine Werkstatt an das Haus an", HouseSite.withHouse(),
                LintMode.STRICT);
        Repair r = assertInstanceOf(Repair.class, review.judge(breakthrough, 0));
        assertTrue(r.reason().contains("changes 15 blocks of existing structure 1"), r.reason());
        GiveUp g = assertInstanceOf(GiveUp.class, review.judge(breakthrough, 1));
        assertTrue(g.siteCheck(), "Bauplatzfehler: kein Ersatzbau");
    }

    /**
     * Prüferbefund 2026-10-29: Aussichtsplattform direkt auf einem Hügel (freier Platz, Gelände bis y3). Der Linter
     * fragte für „schwebend“ den Scan nicht ab – Reparatur, dann Absage für einen gültigen Plan.
     */
    @Test
    void platformOnAHillDoesNotFloat() {
        Map<Pos, String> w = HouseSite.flatGround();
        for (int x = 8; x <= 14; x++) {
            for (int z = 8; z <= 14; z++) {
                for (int y = -1; y <= 3; y++) {
                    w.put(new Pos(x, y, z), y == 3 ? "grass_block" : "dirt");
                }
            }
        }
        SiteSurvey hill = HouseSite.scan(w);
        assertFalse(hill.hasStructures(), "Hügel ist Gelände, kein Bauwerk");
        List<String> steps = new ArrayList<>(List.of(HOUSE));
        steps.add("{\"op\":\"fill\",\"block\":\"stone\",\"from\":[10,4,10],\"to\":[12,4,12]}");
        Verdict v = review("bau ein Haus und eine Aussichtsplattform auf dem Hügel", hill, LintMode.STRICT)
                .judge(HouseSite.plan(steps.toArray(String[]::new)), 0);
        assertInstanceOf(Accept.class, v, () -> v.toString());

        // Dieselbe Plattform eine Schicht höher (y5, unter ihr Luft): Pfeiler von je einem Block auf den Hügel
        // (Gelände trägt – ohne den Scan liefen sie bis y 0 hinunter und wären 5 hoch).
        List<String> high = new ArrayList<>(List.of(HOUSE));
        high.add("{\"op\":\"fill\",\"block\":\"stone\",\"from\":[10,5,10],\"to\":[12,5,12]}");
        Accept a = assertInstanceOf(Accept.class, review("bau ein Haus", hill, LintMode.STRICT)
                .judge(HouseSite.plan(high.toArray(String[]::new)), 0));
        List<CompiledBuild.Change> pillars = a.build().actions().getFirst().changes();
        assertEquals(4, pillars.size(), pillars::toString);
        assertTrue(pillars.stream().allMatch(c -> c.pos().y() == 4), pillars::toString);
    }

    /** Die Anschlussprüfung verweist auf die ATTACH-Vorgabe, nicht mehr auf die entfallene Anschlussstellen-Liste. */
    @Test
    void missingAttachFeedbackPointsToTheFixedWall() throws PlanException {
        CompiledBuild apart = HouseSite.COMPILER.compile(PlanParser.parse(HouseSite.plan(
                "{\"op\":\"walls\",\"block\":\"oak_planks\",\"from\":[-4,0,14],\"to\":[-2,2,16]}")));
        PlanException left = org.junit.jupiter.api.Assertions.assertThrows(PlanException.class,
                () -> PlanReview.checkOverbuild(HouseSite.withHouse(), "bau links eine Werkstatt an", apart, 12));
        assertTrue(left.getMessage().contains("Follow the ATTACH wall: share x=2"), left.getMessage());
        assertFalse(left.getMessage().contains("listed attach points"), left.getMessage());
        assertFalse(left.getMessage().contains("right side"), left.getMessage());

        PlanException grow = org.junit.jupiter.api.Assertions.assertThrows(PlanException.class,
                () -> PlanReview.checkOverbuild(HouseSite.withHouse(), "erweitere das Haus", apart, 12));
        assertFalse(grow.getMessage().contains("listed attach points"), grow.getMessage());
        assertTrue(grow.getMessage().contains("x=2 or x=6, z=8 or z=12"), grow.getMessage());
    }

    /**
     * Vorher traf der Umbau-Regex Teilwörter: „reiß“ in „dreißig“, „änder“ in „Geländer“/„Rändern“ – der Durchbruch
     * wurde dann ohne Rückfrage gebaut. Ein echter Umbauwunsch hebt den Schutz weiter auf.
     */
    @Test
    void overbuildGuardIgnoresRebuildStemsInsideOtherWords() throws PlanException {
        CompiledBuild breakthrough = HouseSite.COMPILER.compile(PlanParser.parse(HouseSite.plan(
                "{\"op\":\"walls\",\"block\":\"stone_bricks\",\"from\":[6,1,8],\"to\":[10,3,12]}",
                "{\"op\":\"fill\",\"block\":\"air\",\"from\":[6,1,8],\"to\":[6,3,12]}")));
        for (String prompt : List.of("bau eine Treppe mit Geländer", "bau ein Hochhaus mit dreißig Stockwerken",
                "bau ein Haus mit dunklen Rändern")) {
            PlanException e = org.junit.jupiter.api.Assertions.assertThrows(PlanException.class,
                    () -> PlanReview.checkOverbuild(HouseSite.withHouse(), prompt, breakthrough, 12), prompt);
            assertTrue(e.getMessage().contains("blocks of existing structure 1"), e.getMessage());
        }
        for (String prompt : List.of("reiß das Haus ab und bau neu", "Umbau des Hauses",
                "das alte Haus einreißen und eine Werkstatt bauen", "Mauer niederreißen, dann neu bauen")) {
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> PlanReview.checkOverbuild(HouseSite.withHouse(), prompt, breakthrough, 12), prompt);
        }
    }

    @Test
    void unknownLintModeFallsBackToStrict() {
        assertEquals(LintMode.STRICT, LintMode.parse("strenge"));
        assertEquals(LintMode.STRICT, LintMode.parse(null));
        assertEquals(LintMode.REPAIR, LintMode.parse(" Reparieren "));
        assertEquals(LintMode.OFF, LintMode.parse("aus"));
    }
}
