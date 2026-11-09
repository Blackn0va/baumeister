package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.PlanReview.Accept;
import com.blackn0va.baumeister.plan.PlanReview.LintMode;
import com.blackn0va.baumeister.plan.PlanReview.Verdict;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Syntax-Reparatur der LLM-Antwort. Die beiden abgeschnittenen Antworten stammen wörtlich aus dem Protokoll des
 * Baumeister-Hosts auf dem GPU-PC des Wunsch): Beide endeten bei max_tokens mitten in einem Schritt, das Plugin verwarf
 * sie („JSON fehlerhaft:
 * java.io.EOFException: End of input … path $.steps[26]“), und die Reparaturrunde sprengte den Kontext.
 */
class JsonRepairTest {

    /** 19:32:25 – Update des Taschenrechners, nach 765 Token abgeschnitten (26 vollständige Schritte). */
    private static final String CUT_UPDATE;

    static {
        StringBuilder s = new StringBuilder("{\"design\":\"ein funktionierender Taschenrechner mit Leitern für Zahlen "
                + "und Schaltflächen für Operationen, die mit Redstone realisiert sind. Die Leitern sind an die "
                + "vorherige Konstruktion angepasst.\",\"reply\":\"Ich aktualisiere deinen Taschenrechner, damit er "
                + "voll funktional ist!\",\"mode\":\"update\",\"steps\":[{\"op\":\"fill\",\"block\":\"stone_bricks\","
                + "\"from\":[-10,0,0],\"to\":[10,0,10]}");
        for (int x = -8; x <= 10; x += 2) {
            s.append(",{\"op\":\"line\",\"block\":\"redstone_wire\",\"from\":[").append(x).append(",1,0],\"to\":[")
                    .append(x).append(",1,10]}");
        }
        for (int z = 2; z <= 10; z += 2) {
            s.append(",{\"op\":\"line\",\"block\":\"redstone_wire\",\"from\":[-8,1,").append(z)
                    .append("],\"to\":[10,1,").append(z).append("]}");
        }
        for (int x = -8; x <= 8; x += 2) {
            s.append(",{\"op\":\"set\",\"block\":\"lever\",\"pos\":[").append(x).append(",1,1],\"facing\":\"right\"}");
        }
        s.append(",{\"op\":\"set\",\"block\":\"lever\",\"pos\":[-8,1,3],\"facing\":\"right\"}");
        s.append(",{\"op\":\"set\",\"block\":\"lever\",\"pos\":[-6,1,3],\"facing\":\"right");
        CUT_UPDATE = s.toString();
    }

    /** 19:26:13 – Reparaturantwort, nach nur 376 Token abgeschnitten (Kontext voll): 9 vollständige Schritte. */
    private static final String CUT_REPAIR = "{\"design\":\"ein funktionierender Taschenrechner mit Schaltungen für "
            + "Addition und Subtraktion. Hebel für die Eingaben, Lampen für die Ausgaben. Die Schaltungen sind in Reihen "
            + "aufgebaut, damit sie leicht verständlich sind.\",\"reply\":\"Ich baue dir einen funktionsfähigen "
            + "Taschenrechner mit Schaltungen für Addition und Subtraktion!\",\"mode\":\"new\",\"steps\":[\n"
            + "{\"op\":\"fill\",\"block\":\"stone_bricks\",\"from\":[-1,0,-1],\"to\":[25,0,11]},\n"
            + "{\"op\":\"line\",\"block\":\"stone_bricks\",\"from\":[-1,1,-1],\"to\":[25,1,-1]},\n"
            + "{\"op\":\"line\",\"block\":\"stone_bricks\",\"from\":[-1,1,11],\"to\":[25,1,11]},\n"
            + "{\"op\":\"line\",\"block\":\"stone_bricks\",\"from\":[-1,1,-1],\"to\":[-1,1,11]},\n"
            + "{\"op\":\"line\",\"block\":\"stone_bricks\",\"from\":[25,1,-1],\"to\":[25,1,11]},\n"
            + "{\"op\":\"line\",\"block\":\"stone_bricks\",\"from\":[-1,2,-1],\"to\":[25,2,-1]},\n"
            + "{\"op\":\"line\",\"block\":\"stone_bricks\",\"from\":[-1,2,11],\"to\":[25,2,11]},\n"
            + "{\"op\":\"line\",\"block\":\"stone_bricks\",\"from\":[-1,2,-1],\"to\":[-1,2,11]},\n"
            + "{\"op\":\"line\",\"block\":\"stone_bricks\",\"from\":[25,2,-1],\"to\":[25,2,11]},\n"
            + "{\"op\":\"line\",\"block\":\"stone_bricks\",\"from\":[-1,3,-1],\"to\":[2";

    private static JsonObject parsed(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void truncatedUpdateFromTheGpuPcKeepsItsTwentySixCompleteSteps() throws Exception {
        PlanParser.Normalized n = PlanParser.normalize(CUT_UPDATE);
        assertTrue(n.truncated());
        assertEquals(26, parsed(n.json()).getAsJsonArray("steps").size());
        BuildPlan plan = PlanParser.parse(CUT_UPDATE);
        assertEquals(26, plan.steps().size());
        assertTrue(plan.isUpdate());
        assertEquals("Ich aktualisiere deinen Taschenrechner, damit er voll funktional ist!", plan.reply());
        // Der letzte vollständige Hebel bleibt, der halbe fällt weg.
        assertTrue(n.json().endsWith("\"pos\":[-8,1,3],\"facing\":\"right\"}]}"), n.json());
    }

    @Test
    void truncatedRepairAnswerKeepsItsNineCompleteSteps() throws Exception {
        assertEquals(9, PlanParser.parse(CUT_REPAIR).steps().size());
    }

    @Test
    void validJsonIsLeftAlone() throws Exception {
        String ok = "{\"reply\":\"x\",\"steps\":[{\"op\":\"set\",\"block\":\"stone\",\"pos\":[0,0,0]}]}";
        PlanParser.Normalized n = PlanParser.normalize("Hier ist der Plan:\n```json\n" + ok + "\n```");
        assertEquals(ok, n.json());
        assertTrue(n.fixes().isEmpty());
    }

    @Test
    void typicalModelMistakesWithoutGrammarAreSmoothed() throws Exception {
        String messy = "Klar! ```json\n{\n  // erster Schritt\n  reply: 'Ein Turm!',\n  \"mode\": \"new\",\n"
                + "  \"steps\": [\n    {\"op\": \"set\", \"block\": \"stone\", \"pos\": [0, 0, 0],}\n"
                + "    {\"op\": \"set\", \"block\": \"glass\", \"pos\": [0, 1, 0], \"hollow\": True}\n  ,],\n"
                + "  /* Ende */ \"design\": \"Zeile 1\nZeile 2\"\n}\n```\nViel Spaß!";
        JsonRepair.Result r = JsonRepair.repair(messy);
        JsonObject o = parsed(r.json());
        assertEquals("Ein Turm!", o.get("reply").getAsString());
        assertEquals(2, o.getAsJsonArray("steps").size());
        assertTrue(o.getAsJsonArray("steps").get(1).getAsJsonObject().get("hollow").getAsBoolean());
        assertEquals("Zeile 1\nZeile 2", o.get("design").getAsString());
        assertFalse(r.truncated());
        assertTrue(r.fixes().contains("fehlendes Komma ergänzt"), r.fixes().toString());
        assertTrue(r.fixes().contains("überzähliges Komma entfernt"), r.fixes().toString());
        assertTrue(r.fixes().contains("Kommentar entfernt"), r.fixes().toString());
        assertTrue(r.fixes().contains("True ersetzt"), r.fixes().toString());
        assertTrue(r.fixes().contains("Schlüssel ohne Anführungszeichen ergänzt"), r.fixes().toString());
        assertEquals(2, PlanParser.parse(messy).steps().size());
    }

    @Test
    void wrongClosingBracketIsCorrected() {
        JsonObject o = parsed(JsonRepair.repair("{\"steps\":[{\"op\":\"set\",\"pos\":[1,2,3}}").json());
        assertEquals(3, o.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonArray("pos").size());
    }

    @Test
    void cutInsideTheDesignTextLeavesNoStepsAndStaysAnOrdinaryPlanError() {
        PlanException e = assertThrows(PlanException.class,
                () -> PlanParser.parse("{\"design\":\"ein großes Haus mit"));
        assertTrue(e.getMessage().contains("steps"), e.getMessage());
    }

    @Test
    void cutRightAfterAKeyOrInsideANumberOrLiteralNeverInventsAValue() {
        for (String cut : new String[] {"{\"reply\":\"a\",\"steps\":[{\"op\":\"set\"},{\"op\":", "{\"reply\":\"a\","
                + "\"steps\":[{\"op\":\"set\"},{\"op\":\"set\",\"pos\":[1,-", "{\"reply\":\"a\",\"steps\":[{\"op\":"
                        + "\"set\"},{\"hollow\":tru"}) {
            JsonObject o = parsed(JsonRepair.repair(cut).json());
            assertEquals(1, o.getAsJsonArray("steps").size(), cut);
        }
    }

    @Test
    void noBraceAtAllStaysUnreadable() {
        assertNull(JsonRepair.repair("Ich kann leider keinen Plan erstellen."));
        assertThrows(PlanException.class, () -> PlanParser.normalize("Ich kann leider keinen Plan erstellen."));
    }

    @Test
    void reviewBuildsATruncatedHouseInsteadOfAskingAgain() {
        // Ende-zu-Ende über PlanReview: das saubere Testhaus, im letzten Schritt (Laterne) abgeschnitten.
        String full = HouseSite.plan(
                "{\"op\":\"fill\",\"block\":\"cobblestone\",\"from\":[0,0,0],\"to\":[6,0,6]}",
                "{\"op\":\"walls\",\"block\":\"oak_planks\",\"from\":[0,1,0],\"to\":[6,3,6]}",
                "{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[3,1,0],\"facing\":\"front\"}",
                "{\"op\":\"roof\",\"block\":\"oak_stairs\",\"from\":[0,4,0],\"to\":[6,4,6],\"shape\":\"gable\","
                        + "\"overhang\":1,\"gable_block\":\"oak_planks\"}",
                "{\"op\":\"set\",\"block\":\"lantern\",\"pos\":[3,1,4]}");
        String cut = full.substring(0, full.lastIndexOf("\"lantern\"") + 5);
        PlanReview review = new PlanReview(HouseSite.COMPILER, ModuleLibrary.EMPTY, new PlanReview.Context(
                "bau ein kleines Haus", RequestHints.parse("bau ein kleines Haus", Set.of()), HouseSite.free(), false,
                12, 1, LintMode.STRICT));
        Verdict v = review.judge(cut, 0);
        Accept ok = assertInstanceOf(Accept.class, v, v::toString);
        assertEquals(4, ok.plan().steps().size());
        assertFalse(ok.repairs().isEmpty());
        assertTrue(ok.repairs().getFirst().startsWith("abgeschnittene Antwort"), ok.repairs().toString());
    }
}
