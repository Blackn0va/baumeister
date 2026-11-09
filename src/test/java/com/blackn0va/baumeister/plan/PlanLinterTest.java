package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.BuildPlan.Step;
import com.blackn0va.baumeister.plan.CompiledBuild.Change;
import com.blackn0va.baumeister.plan.PlanLinter.Issue;
import com.blackn0va.baumeister.plan.PlanLinter.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PlanLinterTest {

    /** TestCatalog kennt weder Wasser (Becken-Regeln) noch Hebel (Testbauteil). */
    private static final BlockCatalog CATALOG = id -> TestCatalog.KNOWN.contains(id)
            || Set.of("water", "lever", "jack_o_lantern").contains(id);

    /**
     * Bauteil wie aus der Bibliothek: zwei Steine nebeneinander, darüber mit einer Lücke ein Hebel auf face=floor –
     * frei hängendes Redstone, das der Linter bei LLM-Schritten melden müsste. Der zweite Stein macht das Teil
     * unsymmetrisch, damit facing=left die Lage wirklich verschiebt.
     */
    private static final ModuleLibrary LIB = new ModuleLibrary() {
        final Module gate = new Module("gate", "Testteil", new Pos(2, 3, 1), List.of(
                new Change(new Pos(0, 0, 0), BlockSpec.of("stone")),
                new Change(new Pos(1, 0, 0), BlockSpec.of("stone")),
                new Change(new Pos(0, 2, 0), BlockSpec.of("lever").with("face", "floor"))));

        @Override
        public Optional<Module> find(String name) {
            return name.equals("gate") ? Optional.of(gate) : Optional.empty();
        }

        @Override
        public List<Module> all() {
            return List.of(gate);
        }
    };

    /** Grenzen wie in Settings – das Hochhaus-Beispiel sprengt die kleinen Testgrenzen. */
    private static final PlanCompiler COMPILER = new PlanCompiler(CATALOG, "stone",
            new PlanCompiler.Limits(150_000, 192), LIB);

    /**
     * Das 7×7-Haus aus dem System-Prompt, hier fest abgeschrieben: Ändert sich das Prompt-Beispiel (op house),
     * sollen die Fehlerfälle unten trotzdem auf demselben Haus stehen. 13 Schritte, ein Zusatz ist also Step 14.
     */
    private static final List<String> HOUSE = List.of(
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
            "{\"op\":\"set\",\"block\":\"lantern\",\"pos\":[3,1,4]}");

    private static BuildPlan plan(List<String> steps) throws PlanException {
        return PlanParser.parse("{\"steps\":[" + String.join(",", steps) + "]}");
    }

    private static List<String> houseWith(String... extra) {
        List<String> steps = new ArrayList<>(HOUSE);
        steps.addAll(List.of(extra));
        return steps;
    }

    private static List<Issue> lint(BuildPlan plan, boolean freeSite) throws PlanException {
        return PlanLinter.lint(plan, COMPILER.compile(plan), freeSite);
    }

    private static List<Issue> lint(List<String> steps) throws PlanException {
        return lint(plan(steps), true);
    }

    /** Mit der Bauteilbibliothek, wie BuildService sie dem Compiler gibt. */
    private static List<Issue> lintWithLibrary(List<String> steps) throws PlanException {
        BuildPlan plan = plan(steps);
        return PlanLinter.lint(plan, COMPILER.compile(plan), LIB, true);
    }

    /** 7×7×6-Steinblock, innen mit fill air ausgehöhlt – das Muster "clear space", das der Prompt lehrt. */
    private static final List<String> CARVED = List.of(
            "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[6,5,6]}",
            "{\"op\":\"fill\",\"block\":\"air\",\"from\":[1,1,1],\"to\":[5,4,5]}");

    private static List<String> carvedWith(String... extra) {
        List<String> steps = new ArrayList<>(CARVED);
        steps.addAll(List.of(extra));
        return steps;
    }

    /** Boden mit gemauertem Raum (Wände y 1..3), Grundlage der Halt-Fälle. */
    private static List<String> roomWith(String... extra) {
        List<String> steps = new ArrayList<>(List.of(
                "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[6,0,6]}",
                "{\"op\":\"walls\",\"block\":\"stone\",\"from\":[0,1,0],\"to\":[6,3,6]}"));
        steps.addAll(List.of(extra));
        return steps;
    }

    /**
     * op house (P1) setzt Leiter, hängende Laterne und Möbel selbst – das LLM kann daran nichts beheben. Beim
     * Zusammenführen von P1 und P3 (2026-10-28) kannte der Linter house noch nicht (Kompilierfehler im switch).
     * Gegenprobe ohne house in UNCHECKED_OPS blieb ebenfalls grün: Die heutigen Haus-Zellen verletzt keine Regel.
     * Der Eintrag schützt künftige Möbel; dieser Test hält nur fest, dass ein Haus fehlerfrei bleibt.
     */
    @Test
    void compilerBuiltHouseIsNotFlagged() throws Exception {
        List<Issue> issues = lint(List.of(
                "{\"op\":\"house\",\"block\":\"spruce_planks\",\"from\":[0,0,0],\"to\":[10,0,12],\"floors\":2}"));
        assertTrue(issues.stream().noneMatch(i -> i.severity() == Severity.ERROR), () -> "Meldungen: " + issues);
    }

    /** Genau eine Meldung mit dieser Schwere, deren Text das Stichwort enthält. */
    private static Issue single(List<Issue> issues, Severity severity, String keyword) {
        assertEquals(1, issues.size(), () -> "Meldungen: " + issues);
        Issue issue = issues.getFirst();
        assertEquals(severity, issue.severity(), () -> "Meldung: " + issue);
        assertTrue(issue.llmText().contains(keyword), () -> "Text ohne '" + keyword + "': " + issue.llmText());
        return issue;
    }

    private static Step shiftUp(Step s, int dy) {
        return new Step(s.op(), s.block(), up(s.from(), dy), up(s.to(), dy), up(s.pos(), dy), s.radius(),
                s.height(), s.hollow(), s.dome(), s.shape(), s.overhang(), s.gableBlock(), s.facing(), s.name(),
                s.floors(), s.windowBlock(), s.roofBlock(), s.expr());
    }

    private static Pos up(Pos p, int dy) {
        return p == null ? null : p.add(0, dy, 0);
    }

    // ---------------------------------------------------------------- saubere Pläne

    /** Die Prompt-Beispiele ahmt das Modell nach – meldet der Linter dort etwas, lehrt der Prompt den Fehler. */
    @Test
    void promptExamplesHaveNoIssues() throws Exception {
        List<String> examples = PlanParserTest.promptExamples();
        assertTrue(examples.size() >= 3, "Beispiele im Prompt");
        for (String json : examples) {
            BuildPlan plan = PlanParser.parse(json);
            for (boolean freeSite : new boolean[] {true, false}) {
                List<Issue> issues = lint(plan, freeSite);
                assertTrue(issues.isEmpty(), () -> plan.design() + " (freeSite=" + freeSite + "): " + issues);
            }
        }
    }

    @Test
    void doorWithExplicitStateInFrontWallIsFine() throws Exception {
        List<String> steps = new ArrayList<>(HOUSE);
        steps.set(6, "{\"op\":\"door\",\"block\":\"oak_door[facing=north]\",\"pos\":[3,1,0],\"facing\":\"front\"}");
        assertEquals(List.of(), lint(steps));
    }

    @Test
    void doubleDoorCountsNeighbourDoorAsWall() throws Exception {
        assertEquals(List.of(), lint(houseWith(
                "{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[2,1,0],\"facing\":\"front\"}")));
    }

    @Test
    void leafSphereOnTrunkIsFine() throws Exception {
        assertEquals(List.of(), lint(List.of(
                "{\"op\":\"fill\",\"block\":\"oak_log\",\"from\":[3,0,3],\"to\":[3,4,3]}",
                "{\"op\":\"sphere\",\"block\":\"oak_leaves\",\"pos\":[3,6,3],\"radius\":3}")));
    }

    @Test
    void pondWithJettyIsFine() throws Exception {
        assertEquals(List.of(), lint(List.of(
                "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[6,0,6]}",
                "{\"op\":\"walls\",\"block\":\"stone\",\"from\":[0,1,0],\"to\":[6,1,6]}",
                "{\"op\":\"fill\",\"block\":\"water\",\"from\":[1,1,1],\"to\":[5,1,5]}",
                "{\"op\":\"line\",\"block\":\"oak_planks\",\"from\":[3,2,-2],\"to\":[3,2,3]}")));
    }

    /** Ohne Boden-Schritt liegt das Wasser direkt auf dem Gelände – das ist ein Teich, kein Leck. */
    @Test
    void waterAtGroundLevelWithoutFloorIsFine() throws Exception {
        assertEquals(List.of(), lint(List.of(
                "{\"op\":\"walls\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[4,1,4]}",
                "{\"op\":\"fill\",\"block\":\"water\",\"from\":[1,0,1],\"to\":[3,0,3]}")));
    }

    @Test
    void raisedHouseDoesNotFloat() throws Exception {
        BuildPlan base = plan(HOUSE);
        BuildPlan raised = new BuildPlan(base.design(), base.reply(),
                base.steps().stream().map(s -> shiftUp(s, 4)).toList());
        assertEquals(List.of(), lint(raised, true));
    }

    /** Treppendächer steigen diagonal: Ohne Giebelfüllung berührt keine Dachschicht die nächste über eine Seite. */
    @Test
    void roofsWithoutGableFillDoNotFloat() throws Exception {
        for (String shape : List.of("gable", "hip")) {
            List<String> steps = new ArrayList<>(HOUSE);
            steps.set(11, "{\"op\":\"roof\",\"block\":\"oak_stairs\",\"from\":[0,4,0],\"to\":[6,4,6],\"shape\":\""
                    + shape + "\",\"overhang\":1}");
            assertEquals(List.of(), lint(steps), shape);
        }
    }

    /** Große Böden sind kein Vollkörper (kein Kern), ein mit air ausgehöhlter Klotz auch nicht (Kern leer). */
    @Test
    void floorsAndCarvedBlocksAreNotSolidBodies() throws Exception {
        assertEquals(List.of(), lint(List.of(
                "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[23,0,23]}")));
        assertEquals(List.of(), lint(List.of(
                "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[8,5,8]}",
                "{\"op\":\"fill\",\"block\":\"air\",\"from\":[1,1,1],\"to\":[7,4,7]}")));
    }

    // ---------------------------------------------------------------- Bauteile und Hochhaus

    @Test
    void moduleOnFloorIsNotChecked() throws Exception {
        List<String> steps = List.of(
                "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[6,0,6]}",
                "{\"op\":\"module\",\"name\":\"gate\",\"pos\":[3,1,3]}");
        assertEquals(List.of(), lint(steps));
        assertEquals(List.of(), lintWithLibrary(steps));
    }

    /**
     * Der Hebel überschreibt eine Zelle des fill-stone-Schritts (über fill air). Action.step nennt dort Schritt 1;
     * hinge die Ausnahme daran, käme "Step 1: lever ... has nothing to hang on" heraus (gemessen 2026-10-28).
     */
    @Test
    void moduleInCarvedSpaceIsNotChecked() throws Exception {
        List<String> steps = carvedWith("{\"op\":\"module\",\"name\":\"gate\",\"pos\":[3,1,3]}");
        assertEquals(List.of(), lint(steps));
        assertEquals(List.of(), lintWithLibrary(steps));
    }

    /** Die Ausnahme gilt dem Bauteil, nicht der Stelle: Setzt das LLM danach etwas darüber, wird es geprüft. */
    @Test
    void llmStepOverModuleCellIsChecked() throws Exception {
        List<String> steps = carvedWith("{\"op\":\"module\",\"name\":\"gate\",\"pos\":[3,1,3]}",
                "{\"op\":\"set\",\"block\":\"lantern\",\"pos\":[3,3,3]}");
        Issue issue = single(lint(steps), Severity.ERROR, "directly below");
        assertEquals(4, issue.step());
        assertEquals(new Pos(3, 3, 3), issue.pos());
        // Mit Bibliothek ist bekannt, dass [3,3,3] der Hebel des Moduls ist: Die Laterne zerstört ihn (MODULE_BURIED).
        List<Issue> withLib = lintWithLibrary(steps);
        assertEquals(2, withLib.size(), "Meldungen: " + withLib);
        assertTrue(withLib.stream().anyMatch(i -> i.rule() == PlanLinter.Rule.SUPPORT && i.step() == 4));
        assertTrue(withLib.stream().anyMatch(i -> i.rule() == PlanLinter.Rule.MODULE_BURIED && i.step() == 4
                && i.llmText().contains("module \"gate\" from step 3")));
    }

    /**
     * dev01 2026-10-30, „bau mir eine fette maschine“: Modul auf y 1, danach ein fill quer hindurch. Vorher meldete
     * der Linter nur schwebende Fackeln, das Modul stand im Stein.
     */
    @Test
    void fillThroughAModuleIsReported() throws Exception {
        List<String> steps = carvedWith("{\"op\":\"module\",\"name\":\"gate\",\"pos\":[3,1,3]}",
                "{\"op\":\"fill\",\"block\":\"stone_bricks\",\"from\":[2,1,2],\"to\":[5,1,5]}");
        List<Issue> issues = lintWithLibrary(steps);
        assertTrue(issues.stream().anyMatch(i -> i.rule() == PlanLinter.Rule.MODULE_BURIED && i.step() == 4),
                "Meldungen: " + issues);
    }

    /**
     * Wie fillThroughAModuleIsReported, aber mit op logic: Vorher zählte MODULE_BURIED nur Bibliotheksmodule, ein fill
     * quer durch die übersetzte Schaltung blieb ohne Befund.
     */
    @Test
    void fillThroughALogicCircuitIsReported() throws Exception {
        Pos size = com.blackn0va.baumeister.logic.LogicCircuit.compile("A & B").module().size();
        List<String> steps = List.of(
                "{\"op\":\"logic\",\"expr\":\"A & B\",\"pos\":[0,0,0]}",
                "{\"op\":\"fill\",\"block\":\"stone_bricks\",\"from\":[0,0,0],\"to\":[" + (size.x() - 1) + ","
                        + (size.y() - 1) + "," + (size.z() - 1) + "]}");
        List<Issue> issues = lint(steps);
        assertTrue(issues.stream().anyMatch(i -> i.rule() == PlanLinter.Rule.MODULE_BURIED && i.step() == 2
                && i.llmText().contains("logic circuit \"A & B\" from step 1")), "Meldungen: " + issues);
    }

    /** Boden darunter und Wände daneben sind kein Überbau. */
    @Test
    void floorBelowAModuleIsFine() throws Exception {
        List<String> steps = carvedWith("{\"op\":\"module\",\"name\":\"gate\",\"pos\":[3,1,3]}",
                "{\"op\":\"fill\",\"block\":\"stone_bricks\",\"from\":[2,0,2],\"to\":[5,0,5]}");
        assertTrue(lintWithLibrary(steps).stream().noneMatch(i -> i.rule() == PlanLinter.Rule.MODULE_BURIED));
    }

    /**
     * Das Modul (facing=left: der Hebel landet bei [3,3,4]) überschreibt einen frei gesetzten Hebel mit demselben
     * Block. Nur mit der Bibliothek ist klar, dass das Modul zuletzt geschrieben hat; ohne sie erklärt der
     * set-Schritt den Block, und die Zelle wird ihm zugerechnet.
     */
    @Test
    void knownModuleOverEarlierSameBlockIsNotChecked() throws Exception {
        List<String> steps = List.of(
                "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[6,0,6]}",
                "{\"op\":\"set\",\"block\":\"lever\",\"pos\":[3,3,4]}",
                "{\"op\":\"module\",\"name\":\"gate\",\"pos\":[3,1,3],\"facing\":\"left\"}");
        assertEquals(List.of(), lintWithLibrary(steps));
        assertEquals(2, single(lint(steps), Severity.ERROR, "nothing to hang on").step());
    }

    // ---------------------------------------------------------------- ERROR

    @Test
    void doorInsideTheRoomIsError() throws Exception {
        Issue issue = single(lint(houseWith(
                "{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[3,1,3],\"facing\":\"front\"}")),
                Severity.ERROR, "not set in a wall");
        assertEquals(14, issue.step());
        assertEquals(new Pos(3, 1, 3), issue.pos());
    }

    @Test
    void windowWithoutWallIsError() throws Exception {
        Issue issue = single(lint(houseWith("{\"op\":\"set\",\"block\":\"glass_pane\",\"pos\":[3,1,3]}")),
                Severity.ERROR, "not inside a wall");
        assertEquals(14, issue.step());
    }

    @Test
    void lanternInMidAirIsError() throws Exception {
        Issue issue = single(lint(houseWith("{\"op\":\"set\",\"block\":\"lantern\",\"pos\":[3,5,3]}")),
                Severity.ERROR, "directly below");
        assertEquals(new Pos(3, 5, 3), issue.pos());
        assertEquals(14, issue.step());
    }

    /** Eine Laterne hält in 1.21 nur auf einem Block (oder hängend darunter), nie seitlich an der Wand. */
    @Test
    void lanternBesideWallOnlyIsError() throws Exception {
        Issue issue = single(lint(roomWith("{\"op\":\"set\",\"block\":\"lantern\",\"pos\":[1,2,3]}")),
                Severity.ERROR, "directly below");
        assertEquals(3, issue.step());
        assertTrue(!issue.llmText().contains("wall block"), () -> "Text rät zur Wand: " + issue.llmText());
    }

    /**
     * Seelaterne und Kürbislaterne sind Vollblöcke, keine Laternen. Vorher fielen sie wegen des Wortteils „lantern“ in
     * PlanCompiler.needsSupport: Eine frei stehende Säule aus Seelaternen meldete „ohne Halt“ (PlanReview ließ sie
     * weg), und eine Fackel darauf galt als haltlos. Gesehen 2026-11-01 an einer echten Schematic (blackhome.schem,
     * Rückübersetzer), 64 solcher Meldungen.
     */
    @Test
    void seaLanternAndJackOLanternAreFullBlocks() throws Exception {
        assertEquals(List.of(), lint(roomWith(
                "{\"op\":\"fill\",\"block\":\"sea_lantern\",\"from\":[3,1,3],\"to\":[3,3,3]}",
                "{\"op\":\"set\",\"block\":\"torch\",\"pos\":[3,4,3]}",
                "{\"op\":\"fill\",\"block\":\"jack_o_lantern\",\"from\":[2,1,4],\"to\":[2,2,4]}")));
        assertTrue(PlanCompiler.needsSupport("lantern") && PlanCompiler.needsSupport("copper_lantern")
                && PlanCompiler.needsSupport("soul_lantern"), "echte Laternen brauchen weiter Halt");
    }

    @Test
    void torchOnWallIsFine() throws Exception {
        assertEquals(List.of(), lint(roomWith("{\"op\":\"set\",\"block\":\"torch\",\"pos\":[1,2,3]}")));
    }

    @Test
    void torchInMidAirIsError() throws Exception {
        single(lint(roomWith("{\"op\":\"set\",\"block\":\"torch\",\"pos\":[3,2,3]}")), Severity.ERROR,
                "no wall block beside it");
    }

    /** hanging=true dreht die Halt-Richtung um: Block darüber statt darunter. */
    @Test
    void hangingLanternNeedsCeiling() throws Exception {
        String ceiling = "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,4,0],\"to\":[6,4,6]}";
        assertEquals(List.of(), lint(roomWith(ceiling,
                "{\"op\":\"set\",\"block\":\"lantern[hanging=true]\",\"pos\":[3,3,3]}")));
        single(lint(roomWith(ceiling, "{\"op\":\"set\",\"block\":\"lantern\",\"pos\":[3,3,3]}")),
                Severity.ERROR, "directly below");
        single(lint(roomWith("{\"op\":\"set\",\"block\":\"lantern[hanging=true]\",\"pos\":[3,1,3]}")),
                Severity.ERROR, "directly above");
    }

    @Test
    void waterUnderClosedLidIsError() throws Exception {
        single(lint(List.of(
                "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[4,0,4]}",
                "{\"op\":\"walls\",\"block\":\"stone\",\"from\":[0,1,0],\"to\":[4,2,4]}",
                "{\"op\":\"fill\",\"block\":\"water\",\"from\":[1,1,1],\"to\":[3,1,3]}",
                "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[1,2,1],\"to\":[3,2,3]}")),
                Severity.ERROR, "sealed in");
    }

    /** Wasser über Wasser ist keine offene Oberfläche – sonst rutscht ein zwei Blöcke tiefes Becken durch. */
    @Test
    void deepWaterUnderClosedLidIsError() throws Exception {
        single(lint(List.of(
                "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[4,0,4]}",
                "{\"op\":\"walls\",\"block\":\"stone\",\"from\":[0,1,0],\"to\":[4,3,4]}",
                "{\"op\":\"fill\",\"block\":\"water\",\"from\":[1,1,1],\"to\":[3,2,3]}",
                "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[1,3,1],\"to\":[3,3,3]}")),
                Severity.ERROR, "sealed in");
    }

    @Test
    void solidCylinderIsError() throws Exception {
        Issue issue = single(lint(List.of(
                "{\"op\":\"cylinder\",\"block\":\"stone_bricks\",\"pos\":[4,0,4],\"radius\":4,\"height\":12}")),
                Severity.ERROR, "solid block of 9x12x9");
        assertEquals(1, issue.step());
    }

    // ---------------------------------------------------------------- HINT

    @Test
    void basinWithMissingSideWallLeaks() throws Exception {
        single(lint(List.of(
                "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[4,0,4]}",
                "{\"op\":\"walls\",\"block\":\"stone\",\"from\":[0,1,0],\"to\":[4,1,4]}",
                "{\"op\":\"fill\",\"block\":\"air\",\"from\":[4,1,1],\"to\":[4,1,3]}",
                "{\"op\":\"fill\",\"block\":\"water\",\"from\":[1,1,1],\"to\":[3,1,3]}")),
                Severity.HINT, "will flow out");
    }

    @Test
    void detachedBlockFloatsOnlyOnFreeSite() throws Exception {
        BuildPlan plan = plan(houseWith("{\"op\":\"set\",\"block\":\"stone\",\"pos\":[12,8,12]}"));
        Issue issue = single(lint(plan, true), Severity.HINT, "float in the air");
        assertEquals(new Pos(12, 8, 12), issue.pos());
        assertEquals(14, issue.step());
        // Neben einem bestehenden Bau kann der Block auf etwas ruhen, das der Plan nicht kennt.
        assertEquals(List.of(), lint(plan, false));
    }

    // ---------------------------------------------------------------- Laufzeit

    /** Größtes Prompt-Beispiel: rund 30 000 Zellen. Nur gemessen und ausgegeben, keine Grenze (Testrechner schwanken). */
    @Test
    void measuresRuntimeOnSkyscraperExample() throws Exception {
        String json = PlanParserTest.promptExamples().stream().filter(e -> e.contains("\"skyscraper\""))
                .findFirst().orElseThrow();
        BuildPlan plan = PlanParser.parse(json);
        CompiledBuild build = COMPILER.compile(plan);
        for (int i = 0; i < 3; i++) {
            PlanLinter.lint(plan, build, true);
        }
        int runs = 5;
        long start = System.nanoTime();
        List<Issue> issues = List.of();
        for (int i = 0; i < runs; i++) {
            issues = PlanLinter.lint(plan, build, true);
        }
        double ms = (System.nanoTime() - start) / 1e6 / runs;
        System.out.printf("PlanLinter | hochhaus | zellen=%d ms=%.1f%n", build.blockCount(), ms);
        assertEquals(List.of(), issues);
    }
}
