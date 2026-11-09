package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.RequestHints.Hints;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RequestHintsTest {

    private static final Set<String> NAMES = Set.of("xor_gatter", "kolbentuer", "klingel", "lampe"); // umlaut-ok

    @Test
    void readsFloorsFromStoreyAdjective() {
        Hints h = RequestHints.parse("30 stöckiges Hochhaus", NAMES);
        assertEquals(OptionalInt.of(30), h.floors());
        assertTrue(h.highRise());
        assertFalse(h.machineWanted());
        assertEquals(OptionalInt.of(30), RequestHints.parse("bau mir ein 30-stöckiges Hochhaus", NAMES).floors());
    }

    @Test
    void readsFloorsFromNumberWords() {
        // Vorher nur Ziffern: „zwanzig Stockwerke“ ergab keine Geschosszahl, der Rückfallplan baute 10.
        assertEquals(OptionalInt.of(20), RequestHints.parse("Hochhaus mit zwanzig Stockwerken", NAMES).floors());
        assertEquals(OptionalInt.of(10), RequestHints.parse("ein zehnstöckiges Hochhaus", NAMES).floors());
        assertEquals(OptionalInt.of(45), RequestHints.parse("fünfundvierzig Etagen", NAMES).floors());
        assertEquals(OptionalInt.of(18), RequestHints.parse("achtzehn Geschosse", NAMES).floors());
        // „ein“ ist Artikel oder Aufstockwunsch, keine Geschosszahl
        assertEquals(OptionalInt.empty(), RequestHints.parse("ein Stockwerk", NAMES).floors());
        assertEquals(OptionalInt.empty(), RequestHints.parse("ein Haus", NAMES).floors());
    }

    @Test
    void houseWithThreeStoreysIsNoHighRise() {
        Hints h = RequestHints.parse("Haus mit 3 Etagen aus Fichtenholz", NAMES);
        assertEquals(OptionalInt.of(3), h.floors());
        assertEquals(Optional.of("spruce_planks"), h.material());
        assertFalse(h.highRise());
        assertFalse(h.facts().contains("skyscraper"), h.facts());
    }

    @Test
    void floorsAreCappedAndTheFactsSaySo() {
        Hints h = RequestHints.parse("60 stöckig", NAMES);
        assertEquals(OptionalInt.of(PlanParser.MAX_FLOORS), h.floors());
        assertTrue(h.highRise());
        assertTrue(h.facts().contains("max 45"), h.facts());
        assertTrue(h.facts().contains("skyscraper"), h.facts());
    }

    @Test
    void machineWordsAndModuleNamesCountAsMachineWish() {
        assertTrue(RequestHints.parse("Kolbentür", NAMES).machineWanted());
        assertTrue(RequestHints.parse("stell eine lampe auf", NAMES).machineWanted());
        assertTrue(RequestHints.parse("ein Logikgatter aus Redstone", Set.of()).machineWanted());
        assertFalse(RequestHints.parse("stell eine lampe auf", Set.of()).machineWanted());
        assertFalse(RequestHints.parse("ein Turm aus Stein", NAMES).machineWanted());
        assertTrue(RequestHints.parse("ein Turm aus Stein", NAMES).facts().contains("do not use op module"));
    }

    @Test
    void storeysWithoutSkyscraperAreRejected() throws Exception {
        // dev01 2026-10-28, Llama 3.1 8B: „20 stöckig“ → 60 Logikgatter-Module, kein skyscraper.
        StringBuilder sb = new StringBuilder("{\"steps\":[");
        for (int i = 0; i < 60; i++) {
            // 4 Gatter je Geschoss in die Fassade, 15 Geschosse hoch
            sb.append(i == 0 ? "" : ",").append("{\"op\":\"module\",\"name\":\"und_gatter\",\"pos\":[")
                    .append(i % 4 * 4).append(',').append(i / 4 * 4).append(",0]}");
        }
        BuildPlan gates = PlanParser.parse(sb.append("]}").toString());
        List<String> problems = RequestHints.check(gates, RequestHints.parse("20 stöckig", NAMES));
        assertTrue(problems.stream().anyMatch(p -> p.contains("skyscraper")), problems::toString);
    }

    /**
     * Module ohne Maschinenwunsch sind seit 2026-10-31 kein Reparaturgrund mehr (26 verworfene Pläne auf dev01):
     * check meldet sie nicht, unwantedModules nennt sie, PlanReview entscheidet ohne LLM.
     */
    @Test
    void moduleWithoutMachineWishIsNamedButNoLongerAProblem() throws Exception {
        BuildPlan p = PlanParser.parse(withStep(skyscraperExample(),
                "{\"op\":\"module\",\"name\":\"xor_gatter\",\"pos\":[20,0,0]}"));
        assertEquals(List.of(), RequestHints.check(p, RequestHints.parse("30 stöckig", NAMES)));
        assertEquals(List.of(p.steps().size() - 1), RequestHints.unwantedModules(p,
                RequestHints.parse("30 stöckig", NAMES)));
        // Mit Maschinenwunsch darf das Modul bleiben.
        assertEquals(List.of(), RequestHints.unwantedModules(p, RequestHints.parse("30 stöckig mit XOR-Gatter",
                NAMES)));
        // Schloss und Klingel sind Maschinenwünsche (dev01 2026-10-31), „Schloss“ allein (Burg) nicht.
        assertTrue(RequestHints.parse("bau mir ein Zahlenschloss", NAMES).machineWanted());
        assertTrue(RequestHints.parse("eine Türklingel bitte", NAMES).machineWanted());
        assertFalse(RequestHints.parse("bau ein Schloss am See", NAMES).machineWanted());
    }

    @Test
    void wrongFloorCountIsRejected() throws Exception {
        BuildPlan p = PlanParser.parse(skyscraperExample());
        List<String> problems = RequestHints.check(p, RequestHints.parse("Hochhaus mit 20 Stockwerken", NAMES));
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().contains("floors to 20"), problems::toString);
    }

    @Test
    void skyscraperExampleOfThePromptHasNoProblems() throws Exception {
        BuildPlan p = PlanParser.parse(skyscraperExample());
        assertEquals(List.of(), RequestHints.check(p, RequestHints.parse("30 stöckig", NAMES)));
    }

    @Test
    void defaultPlanCompilesWithoutWarnings() throws Exception {
        PlanCompiler defaults = new PlanCompiler(new TestCatalog(), "stone", new PlanCompiler.Limits(150_000, 192));
        for (String prompt : new String[] {"bau mir ein 30 stöckiges Hochhaus", "60 stöckig", "ein Wolkenkratzer",
                "Hochhaus mit 12 Etagen aus Stein"}) {
            Hints h = RequestHints.parse(prompt, NAMES);
            BuildPlan p = RequestHints.defaultPlan(h);
            CompiledBuild b = defaults.compile(p);
            assertTrue(b.warnings().isEmpty(), () -> prompt + ": " + b.warnings());
            assertEquals(List.of(), RequestHints.check(p, h), prompt);
            assertEquals(new Pos(15, 0, 15), p.steps().getFirst().to());
        }
    }

    @Test
    void normalizationMatchesTheModuleNames() {
        assertEquals("xor gatter", RequestHints.normalize("XOR-Gatter!"));
        assertEquals("30 stoeckiges buerohaus aus strassenpflaster", // umlaut-ok: erwartete Normalform
                RequestHints.normalize("30-Stöckiges Bürohaus aus Straßenpflaster"));
    }

    /** Das Hochhaus-Beispiel aus dem System-Prompt – das, was das Modell nachahmt. */
    private static String skyscraperExample() throws IOException {
        return PlanParserTest.promptExamples().stream().filter(e -> e.contains("\"op\":\"skyscraper\"")).findFirst()
                .orElseThrow(() -> new AssertionError("Hochhaus-Beispiel fehlt im System-Prompt"));
    }

    /** Hängt einen Schritt hinten an die Schrittliste eines Plan-JSON an. */
    /**
     * Was MachineHall als Maschinen- oder Prozessorauftrag baut, ist auch für RequestHints ein Maschinenwunsch.
     * Vorher fehlten „fabrik“ und „computer“: Der Plan zu „bau mir eine Fabrik“ verlor in PlanReview alle Anlagen
     * („als Verzierung entfernt“, gemessen 2026-10-31 im Lehrset-Generator, 15 Pläne).
     */
    @Test
    void everyMachineHallWordIsAMachineWish() {
        for (String w : MachineHall.WORDS) {
            assertTrue(RequestHints.parse("bau mir eine " + w, Set.of()).machineWanted(), w);
        }
        for (String w : MachineHall.CPU_WORDS) {
            assertTrue(RequestHints.parse("bau mir einen " + w, Set.of()).machineWanted(), w);
        }
        assertFalse(RequestHints.parse("bau mir eine Fabrikantenvilla", Set.of()).machineWanted());
    }

    @Test
    void machineHallPlansKeepTheirModules() throws Exception {
        Machines lib = new Machines();
        List<String> names = lib.all().stream().map(ModuleLibrary.Module::name).toList();
        for (String order : List.of("bau mir eine Fabrik", "bau mir einen Computer", "bau mir eine Maschine")) {
            String json = QuickOrders.match(order, lib, 192).orElseThrow(() -> new AssertionError(order));
            BuildPlan p = PlanParser.parse(json);
            assertEquals(List.of(), RequestHints.unwantedModules(p, RequestHints.parse(order, names)), order);
        }
    }

    private static String withStep(String plan, String step) {
        assertTrue(plan.endsWith("]}"), plan);
        return plan.substring(0, plan.length() - 2) + "," + step + "]}";
    }
}
