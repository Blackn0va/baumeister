package com.blackn0va.baumeister.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.dataset.DatasetGenerator.KindStats;
import com.blackn0va.baumeister.dataset.DatasetGenerator.Sample;
import com.blackn0va.baumeister.plan.LlmProtocol;
import com.blackn0va.baumeister.plan.PlanPrompt;
import com.blackn0va.baumeister.plan.PlanReview;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Import gespeicherter Pläne mit nachgebauten Dateien (Format von BuildService.savePlan): Reparaturrunden werden zu
 * Mehrrunden-Beispielen mit genau der Rückmeldung des Plugins, aber nur, wenn der letzte Versuch besteht. Die echten
 * dev01-Pläne kommen nie ins Repo – Auftragstexte von Spielern.
 */
class PlanImportTest {

    private static final String ORDER = "ein Turm aus Stein";

    /** Der saubere Turm aus DatasetGeneratorTest – besteht die strenge Prüfung. */
    private static final String TOWER = "{\"design\":\"x\",\"reply\":\"x\",\"mode\":\"new\",\"steps\":["
            + "{\"op\":\"cylinder\",\"block\":\"stone_bricks\",\"pos\":[4,0,4],\"radius\":4,\"height\":1},"
            + "{\"op\":\"cylinder\",\"block\":\"stone_bricks\",\"pos\":[4,1,4],\"radius\":4,\"height\":12,"
            + "\"hollow\":true},"
            + "{\"op\":\"door\",\"block\":\"spruce_door\",\"pos\":[4,1,0],\"facing\":\"front\"},"
            + "{\"op\":\"set\",\"block\":\"glass_pane\",\"pos\":[0,5,4]},"
            + "{\"op\":\"cylinder\",\"block\":\"stone_bricks\",\"pos\":[4,13,4],\"radius\":4,\"height\":1},"
            + "{\"op\":\"cylinder\",\"block\":\"cobblestone_wall\",\"pos\":[4,14,4],\"radius\":4,\"height\":1,"
            + "\"hollow\":true},"
            + "{\"op\":\"set\",\"block\":\"lantern\",\"pos\":[4,14,4]}]}";

    /** Unbekannte op: PlanParser wirft, PlanReview schickt in die Reparaturrunde. */
    private static final String BROKEN = "{\"design\":\"x\",\"reply\":\"x\",\"mode\":\"new\",\"steps\":["
            + "{\"op\":\"zauberturm\",\"block\":\"stone_bricks\",\"pos\":[4,0,4]}]}";

    /** Massiver Zylinder: PlanReview macht ihn hohl – gebaut, aber nicht streng bestanden. */
    private static final String SOLID = "{\"design\":\"x\",\"reply\":\"x\",\"mode\":\"new\",\"steps\":["
            + "{\"op\":\"cylinder\",\"block\":\"stone_bricks\",\"pos\":[5,0,5],\"radius\":4,\"height\":12}]}";

    private static PlanJudge judge;

    @BeforeAll
    static void judge() {
        judge = new PlanJudge();
    }

    private static void save(Path dir, String stamp, String owner, String order, int attempt, String answer)
            throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("besitzer", owner);
        o.addProperty("auftrag", order);
        o.addProperty("versuch", attempt);
        o.addProperty("dauer_ms", 1000);
        o.addProperty("tokens", 100);
        o.addProperty("antwort", answer);
        Files.writeString(dir.resolve(stamp + "-" + owner + "-" + attempt + ".json"), o.toString(),
                StandardCharsets.UTF_8);
    }

    @Test
    void repairRoundBecomesMultiTurnSampleWithThePluginsFeedback(@TempDir Path dir) throws Exception {
        save(dir, "20261104-141547", "Spieler", ORDER, 0, BROKEN);
        save(dir, "20261104-141555", "Spieler", ORDER, 1, TOWER);
        PlanImport.Result r = PlanImport.collectRepairs(dir, judge, Set.of());
        assertEquals(1, r.samples().size(), () -> "verworfen: " + r.stats().rejected());
        Sample s = r.samples().getFirst();
        assertEquals("llm_reparatur", s.kind());
        assertEquals(TOWER, s.json());
        assertEquals(BROKEN, s.broken());
        // Rückmeldung = was PlanReview dem Plugin für die Korrekturrunde gibt, nicht ein nachgebauter Text
        PlanReview.Repair rep = assertInstanceOf(PlanReview.Repair.class, judge.judge(ORDER, BROKEN, 0, 1));
        assertEquals(rep.llmRequest(), s.request());
        assertTrue(s.request().startsWith("The plan is invalid: "), s.request());
    }

    @Test
    void writtenRepairLineIsTheConversationThePluginSends(@TempDir Path dir) throws Exception {
        Sample s = new Sample("llm_reparatur", ORDER, TOWER, BROKEN, "The plan is invalid: x Return the complete "
                + "corrected plan as JSON.");
        DatasetGenerator.write(dir, judge, List.of(s), Map.of("llm_reparatur", new KindStats()), 1L, 0L);
        String file = DatasetGenerator.isEval(s) ? "eval.jsonl" : "train.jsonl";
        JsonObject line = JsonParser.parseString(Files.readAllLines(dir.resolve(file), StandardCharsets.UTF_8)
                .getFirst()).getAsJsonObject();
        JsonArray m = line.getAsJsonArray("messages");
        List<String> roles = new ArrayList<>();
        for (JsonElement e : m) {
            roles.add(e.getAsJsonObject().get("role").getAsString());
        }
        assertEquals(List.of("user", "assistant", "user", "assistant"), roles);
        // Wie BuildService: Gespräch der ersten Runde → PlanPrompt.repairConversation, ohne System-Prompt
        List<JsonObject> plugin = PlanPrompt.repairConversation(List.of(LlmProtocol.message("system",
                judge.systemPrompt()), LlmProtocol.message("user", judge.userMessage(ORDER))), ORDER,
                judge.moduleNames(), BROKEN, s.request());
        for (int i = 0; i < 3; i++) {
            assertEquals(plugin.get(i + 1), m.get(i).getAsJsonObject(), "Nachricht " + i);
        }
        assertTrue(m.get(0).getAsJsonObject().get("content").getAsString().contains("Correction round"));
        assertEquals(TOWER, m.get(3).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void repairIsDroppedWhenTheLastAttemptFailsStrictReview(@TempDir Path dir) throws Exception {
        save(dir, "20261104-141547", "Spieler", "ein turm", 0, BROKEN);
        save(dir, "20261104-141555", "Spieler", "ein turm", 1, SOLID);
        PlanImport.Result r = PlanImport.collectRepairs(dir, judge, Set.of());
        assertEquals(0, r.samples().size());
        assertEquals(1, r.stats().rejected().values().stream().mapToInt(Integer::intValue).sum(),
                () -> r.stats().rejected().toString());
    }

    @Test
    void onlyTheLastPairOfAChainCounts(@TempDir Path dir) throws Exception {
        save(dir, "20261104-141547", "Spieler", ORDER, 0, BROKEN);
        save(dir, "20261104-141550", "Spieler", ORDER, 1, BROKEN);
        save(dir, "20261104-141555", "Spieler", ORDER, 2, TOWER);
        PlanImport.Result r = PlanImport.collectRepairs(dir, judge, Set.of());
        assertEquals(1, r.samples().size());
        assertEquals(1, r.stats().tried());
    }

    @Test
    void firstAnswersAloneGiveNoRepairAndStayFirstAnswers(@TempDir Path dir) throws Exception {
        save(dir, "20261104-141547", "Spieler", ORDER, 0, TOWER);
        save(dir, "20261104-141822", "Spieler", "ein turm", 0, SOLID);
        assertEquals(0, PlanImport.collectRepairs(dir, judge, Set.of()).samples().size());
        List<Sample> first = PlanImport.collect(dir, judge, Set.of()).samples();
        assertEquals(1, first.size());
        assertEquals(new Sample("llm_angenommen", ORDER, TOWER), first.getFirst());
        assertNull(first.getFirst().broken());
    }

    /** savePlan behält nur 50 Dateien: ein Versuch 1, dessen Versuch 0 schon gelöscht ist, hat keine Rückmeldung. */
    @Test
    void repairWithoutItsBrokenPlanIsSkipped(@TempDir Path dir) throws Exception {
        save(dir, "20261104-141555", "Spieler", ORDER, 1, TOWER);
        PlanImport.Result r = PlanImport.collectRepairs(dir, judge, Set.of());
        assertEquals(0, r.samples().size());
        assertEquals(Map.of("vorgänger_fehlt", 1), r.stats().rejected());
    }

    /** Zwei Spieler planen zugleich: Die Kette gehört zum Besitzer, nicht zur Dateifolge. */
    @Test
    void chainsAreFollowedPerOwner(@TempDir Path dir) throws Exception {
        save(dir, "20261104-141547", "Spieler", ORDER, 0, BROKEN);
        save(dir, "20261104-141550", "Anderer", "ein turm", 0, SOLID);
        save(dir, "20261104-141555", "Spieler", ORDER, 1, TOWER);
        assertEquals(1, PlanImport.collectRepairs(dir, judge, Set.of()).samples().size());
    }

    @Test
    void benchOrdersNeverBecomeRepairs(@TempDir Path dir) throws Exception {
        save(dir, "20261104-141547", "Spieler", ORDER, 0, BROKEN);
        save(dir, "20261104-141555", "Spieler", ORDER, 1, TOWER);
        PlanImport.Result r = PlanImport.collectRepairs(dir, judge,
                Set.of(com.blackn0va.baumeister.plan.RequestHints.normalize(ORDER)));
        assertEquals(0, r.samples().size());
        assertEquals(1, r.stats().duplicate);
    }
}
