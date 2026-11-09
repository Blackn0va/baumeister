package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlanParserTest {

    /**
     * dev01 2026-10-30, Qwen2.5 3B: dieselben line-Schritte zweimal hintereinander. Doppelte fallen weg, und zwar das
     * frühere Vorkommen – bei A, B, A liegt A am Ende über B, das muss so bleiben.
     */
    @Test
    void repeatedStepsAreDroppedKeepingTheLastOne() throws Exception {
        String a = "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[4,0,4]}";
        String aOtherOrder = "{\"block\":\"stone\",\"op\":\"fill\",\"to\":[4,0,4],\"from\":[0,0,0]}";
        String b = "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[0,0,0],\"to\":[2,0,2]}";
        String raw = "{\"design\":\"\",\"reply\":\"\",\"steps\":[" + a + "," + b + "," + aOtherOrder + "," + a + "]}";
        PlanParser.Normalized n = PlanParser.normalize(raw);
        assertEquals(List.of("2 doppelte Schritte entfernt"), n.fixes());
        BuildPlan p = PlanParser.parse(raw);
        assertEquals(2, p.steps().size());
        assertEquals("oak_planks", p.steps().get(0).block(), "B zuerst, A (Stein) zuletzt – A liegt oben");
        assertEquals("stone", p.steps().get(1).block());
        // Ohne Wiederholung bleibt der Text unverändert.
        String clean = "{\"design\":\"\",\"reply\":\"\",\"steps\":[" + a + "," + b + "]}";
        assertEquals(clean, PlanParser.normalize(clean).json());
    }

    /** Die Beispiele im System-Prompt sind das, was das Modell nachahmt – sie müssen selbst gültig sein. */
    @Test
    void examplesInSystemPromptParseAndCompile() throws Exception {
        List<String> examples = promptExamples();
        assertEquals(4, examples.size(), "Beispiele im Prompt");
        // Mit den Vorgaben aus Settings (150 000 / 192) – das Hochhaus-Beispiel sprengt die alten 6000 / 48.
        PlanCompiler compiler = new PlanCompiler(new TestCatalog(), "stone", new PlanCompiler.Limits(150_000, 192));
        for (String json : examples) {
            BuildPlan plan = PlanParser.parse(json);
            CompiledBuild build = compiler.compile(plan);
            assertTrue(build.warnings().isEmpty(), () -> "Warnungen: " + build.warnings());
            assertTrue(build.blockCount() > 50);
        }
    }

    @Test
    void stripsMarkdownFenceAroundJson() throws Exception {
        String raw = "Hier ist der Plan:\n```json\n{\"design\":\"x\",\"reply\":\"ok\",\"steps\":["
                + "{\"op\":\"set\",\"block\":\"stone\",\"pos\":[0,0,0]}]}\n```";
        assertEquals(1, PlanParser.parse(raw).steps().size());
    }

    @Test
    void acceptsFromInsteadOfPosForSingleBlock() throws Exception {
        BuildPlan plan = PlanParser.parse("{\"steps\":[{\"op\":\"set\",\"block\":\"stone\",\"from\":[1,2,3]}]}");
        assertEquals(new Pos(1, 2, 3), plan.steps().getFirst().pos());
    }

    @Test
    void rejectsUnknownOperation() {
        PlanException e = assertThrows(PlanException.class,
                () -> PlanParser.parse("{\"steps\":[{\"op\":\"explode\",\"block\":\"stone\"}]}"));
        assertTrue(e.getMessage().contains("unbekannte op"));
    }

    @Test
    void rejectsFillWithoutCoordinates() {
        assertThrows(PlanException.class,
                () -> PlanParser.parse("{\"steps\":[{\"op\":\"fill\",\"block\":\"stone\"}]}"));
    }

    @Test
    void rejectsCoordinatesFarOutside() {
        assertThrows(PlanException.class, () -> PlanParser.parse(
                "{\"steps\":[{\"op\":\"set\",\"block\":\"stone\",\"pos\":[0,100000,0]}]}"));
    }

    @Test
    void rejectsTooManySteps() {
        StringBuilder sb = new StringBuilder("{\"steps\":[");
        for (int i = 0; i <= PlanParser.MAX_STEPS; i++) {
            // Verschiedene Stellen: 61 gleiche Schritte fielen seit 2026-10-30 auf einen zusammen (withoutDuplicates).
            sb.append(i == 0 ? "" : ",").append("{\"op\":\"set\",\"block\":\"stone\",\"pos\":[" + i + ",0,0]}");
        }
        sb.append("]}");
        assertThrows(PlanException.class, () -> PlanParser.parse(sb.toString()));
    }

    @Test
    void houseDefaultsToOneFloorAndKeepsRoofBlock() throws Exception {
        BuildPlan.Step s = PlanParser.parse("{\"steps\":[{\"op\":\"house\",\"block\":\"oak_planks\",\"from\":[0,0,0],"
                + "\"to\":[6,0,6],\"roof_block\":\"brick_stairs\"}]}").steps().getFirst();
        assertEquals(BuildPlan.Op.HOUSE, s.op());
        assertEquals(1, s.floors());
        assertEquals("brick_stairs", s.roofBlock());
    }

    @Test
    void houseRejectsMoreThanFourFloorsAndOddFootprints() {
        String head = "{\"steps\":[{\"op\":\"house\",\"block\":\"oak_planks\",";
        PlanException tall = assertThrows(PlanException.class,
                () -> PlanParser.parse(head + "\"from\":[0,0,0],\"to\":[8,0,8],\"floors\":5}]}"));
        assertTrue(tall.getMessage().contains("skyscraper"), tall.getMessage());
        assertThrows(PlanException.class,
                () -> PlanParser.parse(head + "\"from\":[0,0,0],\"to\":[8,0,8],\"floors\":0}]}"));
        assertThrows(PlanException.class, () -> PlanParser.parse(head + "\"from\":[0,0,0],\"to\":[3,0,8]}]}"));
        assertThrows(PlanException.class, () -> PlanParser.parse(head + "\"from\":[0,0,0],\"to\":[24,0,8]}]}"));
        assertThrows(PlanException.class, () -> PlanParser.parse(head + "\"pos\":[0,0,0]}]}"));
    }

    @Test
    void rejectsTextWithoutJson() {
        assertThrows(PlanException.class, () -> PlanParser.parse("Ich kann leider nicht bauen."));
    }

    static List<String> promptExamples() throws IOException {
        String prompt;
        try (InputStream in = PlanParserTest.class.getResourceAsStream("/system-prompt.txt")) {
            prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> out = new ArrayList<>();
        int idx = 0;
        while ((idx = prompt.indexOf("{\"design\"", idx)) >= 0) {
            int end = prompt.indexOf("]}\n", idx);
            if (end < 0) {
                end = prompt.lastIndexOf("]}");
            }
            out.add(prompt.substring(idx, end + 2));
            idx = end;
        }
        return out;
    }
}
