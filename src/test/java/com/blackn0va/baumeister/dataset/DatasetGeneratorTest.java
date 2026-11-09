package com.blackn0va.baumeister.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.dataset.DatasetGenerator.Result;
import com.blackn0va.baumeister.dataset.DatasetGenerator.Sample;
import com.blackn0va.baumeister.plan.QuickOrders;
import com.blackn0va.baumeister.plan.RequestHints;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lehrset: Jeder Eintrag besteht die strenge Prüfung, jede Art liefert, die Testaufträge des Messgeräts kommen nie
 * hinein. Dazu die Gegenproben am Richter selbst – ein Generator, dessen Richter alles durchwinkt, hätte sonst
 * dieselben grünen Tests.
 */
class DatasetGeneratorTest {

    private static PlanJudge judge;
    private static Result result;

    @BeforeAll
    static void generate() {
        judge = new PlanJudge();
        result = new DatasetGenerator(judge).generate(600, 20261031L);
    }

    @Test
    void everyGeneratedPlanPassesStrictReview() {
        assertEquals(600, result.samples().size());
        for (Sample s : result.samples()) {
            Optional<String> why = judge.rejection(s.order(), s.json());
            assertTrue(why.isEmpty(), () -> s.kind() + " „" + s.order() + "“: " + why.get() + "\n" + s.json());
        }
    }

    @Test
    void everyKindContributesSamples() {
        result.stats().forEach((kind, ks) -> assertTrue(ks.accepted() >= 5,
                () -> kind + " lieferte nur " + ks.accepted() + " von " + ks.tried() + ", verworfen: "
                        + ks.rejected()));
    }

    @Test
    void ordersAreUniqueAndNeverBenchOrders() {
        Set<String> bench = new HashSet<>();
        BenchOrders.load().forEach(o -> bench.add(RequestHints.normalize(o)));
        Set<String> seen = new HashSet<>();
        for (Sample s : result.samples()) {
            String key = RequestHints.normalize(s.order());
            assertTrue(seen.add(key), () -> "doppelt: " + s.order());
            for (String b : bench) {
                assertFalse(key.contains(b) || b.contains(key), () -> "Testauftrag im Lehrset: " + s.order());
            }
        }
    }

    @Test
    void sameSeedGivesSameDataset() {
        Result again = new DatasetGenerator(judge).generate(600, 20261031L);
        assertEquals(result.samples(), again.samples());
    }

    /** Messgerät misst das LLM: Ein Testauftrag, den QuickOrders ohne LLM baut, käme dort nie an. */
    @Test
    void benchOrdersAllGoToTheLlm() {
        List<String> bench = BenchOrders.load();
        assertTrue(bench.size() >= 20, "zu wenige Testaufträge: " + bench.size());
        for (String o : bench) {
            assertTrue(QuickOrders.match(o, judge.modules(), PlanJudge.MAX_EXTENT).isEmpty(),
                    () -> "Schnellauftrag statt LLM: " + o);
        }
    }

    @Test
    void writtenFilesHoldEverySampleOnce(@TempDir Path dir) throws Exception {
        List<Sample> few = result.samples().subList(0, 60);
        DatasetGenerator.write(dir, judge, few, result.stats(), 1L, 0L);
        long train = Files.readAllLines(dir.resolve("train.jsonl"), StandardCharsets.UTF_8).size();
        long eval = Files.readAllLines(dir.resolve("eval.jsonl"), StandardCharsets.UTF_8).size();
        assertEquals(60, train + eval);
        assertEquals(judge.systemPrompt(), Files.readString(dir.resolve("system-prompt.txt"),
                StandardCharsets.UTF_8));
        String first = Files.readAllLines(dir.resolve(DatasetGenerator.isEval(few.getFirst()) ? "eval.jsonl"
                : "train.jsonl"), StandardCharsets.UTF_8).getFirst();
        assertTrue(first.contains("\"role\":\"user\"") && first.contains("\"role\":\"assistant\""), first);
        assertTrue(first.contains("Bauauftrag: "), "Nutzernachricht wie im Plugin (PlanPrompt) fehlt: " + first);
    }

    /**
     * Grammatik des Hosts: QuickOrders schreibt beim Hochhaus window_block vor from – das kann das Modell im Host nie
     * erzeugen. Der Richter muss es ablehnen, und PlanText muss es beim Rendern in die Schema-Reihenfolge bringen.
     */
    @Test
    void quickSkyscraperIsRenderedInGrammarOrder() {
        String raw = QuickOrders.match("bau mir ein Hochhaus mit 12 Stockwerken", judge.modules(), 192).orElseThrow();
        assertTrue(judge.rejection("bau mir ein Hochhaus mit 12 Stockwerken", raw).orElse("").startsWith("grammatik"),
                raw);
        String rendered = DatasetGenerator.reRender(raw);
        assertEquals(Optional.empty(), judge.rejection("bau mir ein Hochhaus mit 12 Stockwerken", rendered));
        assertTrue(rendered.contains("\"to\":[15,0,15],\"floors\":12,\"window_block\""), rendered);
    }

    // ---------------------------------------------------------------- Gegenproben am Richter

    private static String plan(String... steps) {
        return "{\"design\":\"x\",\"reply\":\"x\",\"mode\":\"new\",\"steps\":[" + String.join(",", steps) + "]}";
    }

    @Test
    void judgeRejectsSealedWater() {
        String sealed = plan("{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[4,0,4]}",
                "{\"op\":\"walls\",\"block\":\"stone\",\"from\":[0,1,0],\"to\":[4,2,4]}",
                "{\"op\":\"fill\",\"block\":\"water\",\"from\":[1,1,1],\"to\":[3,2,3]}",
                "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,3,0],\"to\":[4,3,4]}");
        assertTrue(judge.rejection("ein becken", sealed).isPresent());
    }

    /** Ein schwebender Klotz: PlanReview stützt ihn – im Plugin gebaut, ins Lehrset darf er nicht. */
    @Test
    void judgeRejectsPlansThatNeededSupports() {
        String floating = plan("{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[4,0,4]}",
                "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[0,8,0],\"to\":[4,8,4]}");
        Optional<String> why = judge.rejection("eine plattform", floating);
        assertTrue(why.isPresent());
    }

    @Test
    void judgeRejectsHighRiseWithoutSkyscraper() {
        String walls = plan("{\"op\":\"walls\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[9,40,9]}");
        assertTrue(judge.rejection("ein hochhaus mit 10 stockwerken", walls).isPresent());
    }

    @Test
    void judgeRejectsSolidBuildingThatPlanReviewHollowed() {
        String solid = plan("{\"op\":\"cylinder\",\"block\":\"stone_bricks\",\"pos\":[5,0,5],\"radius\":4,"
                + "\"height\":12}");
        assertTrue(judge.rejection("ein turm", solid).isPresent());
    }

    @Test
    void judgeAcceptsTheCleanTowerFromTheSystemPrompt() {
        String tower = plan("{\"op\":\"cylinder\",\"block\":\"stone_bricks\",\"pos\":[4,0,4],\"radius\":4,\"height\":1}",
                "{\"op\":\"cylinder\",\"block\":\"stone_bricks\",\"pos\":[4,1,4],\"radius\":4,\"height\":12,"
                        + "\"hollow\":true}",
                "{\"op\":\"door\",\"block\":\"spruce_door\",\"pos\":[4,1,0],\"facing\":\"front\"}",
                "{\"op\":\"set\",\"block\":\"glass_pane\",\"pos\":[0,5,4]}",
                "{\"op\":\"cylinder\",\"block\":\"stone_bricks\",\"pos\":[4,13,4],\"radius\":4,\"height\":1}",
                "{\"op\":\"cylinder\",\"block\":\"cobblestone_wall\",\"pos\":[4,14,4],\"radius\":4,\"height\":1,"
                        + "\"hollow\":true}",
                "{\"op\":\"set\",\"block\":\"lantern\",\"pos\":[4,14,4]}");
        assertEquals(Optional.empty(), judge.rejection("ein Turm aus Stein", tower));
    }
}
