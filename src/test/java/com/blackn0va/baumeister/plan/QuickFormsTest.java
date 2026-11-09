package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.BuildPlan.Op;
import com.blackn0va.baumeister.plan.BuildPlan.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** Schnellaufträge für Burg, Mauer, Brücke, Garten und Baum (QuickForms über QuickOrders.match). */
class QuickFormsTest {

    private static final Set<String> NAMES = new Machines().all().stream().map(ModuleLibrary.Module::name)
            .collect(Collectors.toSet());

    private static BuildPlan plan(String prompt, int maxExtent) throws PlanException {
        Optional<String> json = QuickOrders.match(prompt, NAMES, maxExtent);
        assertTrue(json.isPresent(), () -> "kein Schnellauftrag: " + prompt);
        return PlanParser.parse(json.get());
    }

    private static Step step(String prompt) throws PlanException {
        BuildPlan p = plan(prompt, 192);
        assertEquals(1, p.steps().size(), prompt);
        return p.steps().getFirst();
    }

    /** Pyramide (2026-11-08): Seite aus Breite, Fläche oder Höhe, ungerade; Rechteck und Zusatzwünsche → LLM. */
    @Test
    void pyramidSizesAndWhatGoesToTheLlm() throws Exception {
        BuildPlan p = plan("bau mir eine Pyramide", 192);
        assertEquals("sandstone", p.steps().getFirst().block());
        assertEquals(new Pos(14, 0, 14), p.steps().getFirst().to(), "Vorgabe 15 × 15");
        assertEquals(Op.FILL, p.steps().getLast().op(), "Eingang zum Schluss");
        assertEquals(new Pos(20, 0, 20), plan("eine Pyramide 21 Blöcke breit", 192).steps().getFirst().to());
        assertEquals(new Pos(18, 0, 18), plan("Pyramide 10 Blöcke hoch", 192).steps().getFirst().to(),
                "Höhe 10 → Seite 19");
        assertEquals(new Pos(20, 0, 20), plan("Pyramide 22x22", 192).steps().getFirst().to(), "gerade → ungerade");
        assertEquals(new Pos(28, 0, 28), plan("eine riesige Pyramide", 30).steps().getFirst().to(),
                "riesig 41, gekappt auf max-ausdehnung 30, ungerade 29");
        for (String llm : List.of("Pyramide 20x24", "eine Pyramide mit Garten", "Pyramide aus Glas")) {
            assertTrue(QuickOrders.match(llm, NAMES, 192).isEmpty(), llm);
        }
    }

    @Test
    void castleOrderBecomesOneCastleStep() throws Exception {
        Step s = step("bau eine Burg");
        assertEquals(Op.CASTLE, s.op());
        assertEquals("stone_bricks", s.block());
        assertEquals(new Pos(24, 0, 24), s.to());
        assertEquals(6, s.height());
        Step big = step("eine große Burg aus Sandstein");
        assertEquals("sandstone", big.block());
        assertEquals(new Pos(36, 0, 36), big.to());
        assertEquals(8, big.height());
        Step area = step("bitte bau mir eine Ritterburg 30x40 10 Blöcke hoch");
        assertEquals(new Pos(29, 0, 39), area.to());
        assertEquals(10, area.height());
    }

    @Test
    void castleAreaWithMalIsRead() throws Exception {
        // Vorher ging „30 mal 30“ ans LLM, weil die Zahlen als Nicht-Füllwörter stehen blieben.
        Step s = step("eine Burg 30 mal 30");
        assertEquals(Op.CASTLE, s.op());
        assertEquals(new Pos(29, 0, 29), s.to());
        // Direkt über QuickForms, ohne den Umweg über QuickOrders.match
        assertTrue(QuickForms.match(RequestHints.normalize("eine Burg 30 mal 30"), 192).isPresent());
    }

    @Test
    void bridgeOverTwentyBlocksSpansTwentyWithABankBlockOnEachSide() throws Exception {
        Step s = step("eine Brücke über 20 Blöcke");
        assertEquals(Op.BRIDGE, s.op());
        assertEquals(new Pos(0, 0, 0), s.from());
        assertEquals(new Pos(0, 0, 21), s.to());
        assertEquals(3, s.width());
        Step wood = step("eine Holzbrücke 12 Blöcke lang 5 Blöcke breit");
        assertEquals("oak_planks", wood.block());
        // „12 Blöcke lang“ ist die ganze Brücke, „über 20 Blöcke“ die freie Spannweite (Prüfer-Befund 2026-10-29).
        assertEquals(new Pos(0, 0, 11), wood.to());
        assertEquals(5, wood.width());
        assertEquals("beam", step("Balkenbrücke").style());
    }

    @Test
    void gardenWallAndTreeOrders() throws Exception {
        Step g = step("ein Garten");
        assertEquals(Op.GARDEN, g.op());
        assertEquals(new Pos(10, 0, 8), g.to());
        assertEquals("oak_fence", g.block());
        assertEquals("spruce_planks", step("ein kleiner Garten aus Fichtenholz").block());
        Step w = step("eine Stadtmauer 30 Blöcke lang 8 Blöcke hoch");
        assertEquals(Op.WALL, w.op());
        assertEquals(29, w.to().x() - w.from().x());
        assertEquals(8, w.height());
        Step t = step("bau mir eine kleine Tanne");
        assertEquals(Op.TREE, t.op());
        assertEquals("spruce_log", t.block());
        assertEquals("cone", t.style());
        assertEquals(6, t.height());
        Step birch = step("ein Baum aus Birke");
        assertEquals("birch_planks", birch.block());
        assertEquals(7, birch.height());
        assertEquals(16, step("ein 16 Blöcke hoher Baum").height());
    }

    @Test
    void everyQuickFormCompilesWithoutWarningsAndHolds() throws Exception {
        for (String prompt : new String[] {"bau eine Burg", "eine riesige Festung", "eine kleine Burg",
                "eine Brücke über 20 Blöcke", "eine Holzbrücke", "eine Mauer", "eine Stadtmauer aus Ziegeln",
                "ein Garten", "ein großer Garten", "ein Baum", "eine riesige Tanne", "eine Eiche"}) {
            CompiledBuild b = FormChecks.compiler().compile(plan(prompt, 192));
            assertTrue(b.warnings().isEmpty(), () -> prompt + ": " + b.warnings());
            Map<Pos, BlockSpec> s = FormChecks.state(b);
            FormChecks.assertHolds(s, prompt);
            FormChecks.assertAttached(s, prompt);
        }
    }

    @Test
    void sizesAreCappedToTheExtentOrLeftToTheLlm() throws Exception {
        // max-ausdehnung 48: riesige Burg 49 → 48, Brücke über 100 → 48 lang
        assertEquals(new Pos(47, 0, 47), plan("eine riesige Burg", 48).steps().getFirst().to());
        assertEquals(new Pos(0, 0, 47), plan("eine Brücke über 100 Blöcke", 48).steps().getFirst().to());
        new PlanCompiler(FormChecks.CATALOG, "stone", new PlanCompiler.Limits(150_000, 48))
                .compile(plan("eine riesige Burg", 48));
        // Unter 17 passt keine Burg: ans LLM statt „Bauwerk zu groß“
        assertEquals(Optional.empty(), QuickOrders.match("eine Burg", NAMES, 16));
    }

    @Test
    void ordersWithExtraWishesStayWithTheLlm() {
        for (String prompt : new String[] {"eine Burg mit Wassergraben", "eine Brücke über den Fluss",
                "eine Brücke mit 3 Bögen", "Burg und Garten", "ein Baumhaus", "ein Garten 10 Blöcke hoch",
                "eine Burg aus Glas", "ein Haus mit Garten", "eine Mauer 20x30", "zwei Bäume", "eine Burg 20 40",
                "eine große kleine Burg", "eine Brücke über 20 Blöcke über 30 Blöcke"}) {
            assertEquals(Optional.empty(), QuickOrders.match(prompt, NAMES, 192), prompt);
        }
    }

    @Test
    void formWordsAreNoModuleNames() {
        Set<String> modules = NAMES.stream().map(n -> RequestHints.normalize(n.replace('_', ' ')).replace(" ", ""))
                .collect(Collectors.toSet());
        for (String w : QuickForms.words()) {
            assertFalse(modules.contains(w), "Formwort ist zugleich Bauteilname: " + w);
        }
    }

    @Test
    void requestFactsNameTheFormForTheLlm() {
        String facts = RequestHints.parse("bau mir eine Burg mit Wassergraben", NAMES).facts();
        assertTrue(facts.contains("castle wanted: use op castle"), facts);
        String both = RequestHints.parse("zwei Brücken und ein Apfelbaum", NAMES).facts();
        assertTrue(both.contains("bridge wanted: use op bridge"), both);
        assertTrue(both.contains("tree wanted: use op tree"), both);
        // Hauswände sind keine Wehrmauer, ein Turm keine Burg
        String house = RequestHints.parse("ein Haus mit Steinmauern", NAMES).facts();
        assertFalse(house.contains(" wanted: use op"), house);
        assertFalse(RequestHints.parse("ein Turm aus Stein", NAMES).facts().contains(" wanted: use op"));
    }

    @Test
    void requestFactsAskForOneStepPerFormAndOneWallStepPerSide() {
        // Vorher „use ONE bridge step for it“: „zwei Brücken“ ergab eine Brücke, „Mauer um das Haus“ eine Seite.
        String two = RequestHints.parse("zwei Brücken", NAMES).facts();
        assertTrue(two.contains("one step for each bridge"), two);
        assertFalse(two.contains("ONE"), two);
        String ring = RequestHints.parse("bau eine Mauer um das Haus", NAMES).facts();
        assertTrue(ring.contains("one wall step per side"), ring);
    }

    @Test
    void compoundsThatAreNoFormsGiveNoFormFact() {
        // Vorher per Wortende erkannt: Kindergarten → garden, Schlagbaum → tree.
        List<String> wrong = new ArrayList<>();
        for (String prompt : new String[] {"bau einen Kindergarten", "ein Biergarten mit Bänken",
                "ein Schlagbaum an der Einfahrt", "ein Stammbaum", "ein Tisch aus Birke", "ein Haus aus Eiche"}) {
            String facts = RequestHints.parse(prompt, NAMES).facts();
            if (facts.contains(" wanted: use op") || facts.contains("wanted: use ONE")) {
                wrong.add(prompt + " → " + facts);
            }
        }
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
        // Echte Zusammensetzungen bleiben erkannt
        assertTrue(RequestHints.parse("eine Zugbrücke", NAMES).facts().contains("bridge wanted"));
        assertTrue(RequestHints.parse("drei Apfelbäume", NAMES).facts().contains("tree wanted"));
        assertTrue(RequestHints.parse("ein Gemüsegarten", NAMES).facts().contains("garden wanted"));
    }

    @Test
    void widthIsTheSecondSideOfCastleAndGarden() throws Exception {
        // Vorher still verworfen: „Burg 40 Blöcke breit“ wurde 25×25, „Garten 20 Blöcke breit“ 11×9.
        Step c = step("eine Burg 40 Blöcke breit");
        assertEquals(new Pos(39, 0, 24), c.to());
        assertEquals(new Pos(39, 0, 29), step("eine Burg 30 Blöcke lang 40 Blöcke breit").to());
        assertEquals(new Pos(19, 0, 8), step("ein Garten 20 Blöcke breit").to());
        assertEquals(new Pos(10, 0, 14), step("ein Garten 15 Blöcke lang").to());
        // Fläche und Seitenmaß zugleich: unklar, welches gilt – ans LLM
        assertEquals(Optional.empty(), QuickOrders.match("eine Burg 30x40 20 Blöcke breit", NAMES, 192));
        assertEquals(Optional.empty(), QuickOrders.match("eine Burg 30x40 20 Blöcke lang", NAMES, 192));
    }

    @Test
    void treeOfNonWoodStaysWithTheLlm() {
        // Vorher baute „ein Baum aus Stein“ still eine Eiche.
        assertEquals(Optional.empty(), QuickOrders.match("ein Baum aus Stein", NAMES, 192));
        assertEquals(Optional.empty(), QuickOrders.match("eine Tanne aus Sandstein", NAMES, 192));
    }

    @Test
    void aUserModuleNamedLikeAFormIsBuiltAsTheModule() {
        // Ein selbst angelegtes Bauteil „baum“ darf nicht von der Bauform überdeckt werden.
        Optional<String> json = QuickOrders.match("bau einen Baum", Set.of("baum"), 192);
        assertTrue(json.isPresent());
        assertTrue(json.get().contains("\"op\":\"module\""), json.get());
        assertTrue(QuickOrders.match("bau eine Burg", Set.of("baum"), 192).orElseThrow().contains("castle"));
    }
}
