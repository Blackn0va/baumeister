package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.BuildPlan.Op;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class QuickOrdersTest {

    private static final Machines MACHINES = new Machines();
    private static final Set<String> NAMES = MACHINES.all().stream().map(ModuleLibrary.Module::name)
            .collect(Collectors.toSet());
    /** Config-Vorgaben (bau.max-blöcke 150 000, bau.max-ausdehnung 192). */
    private static final PlanCompiler.Limits DEFAULTS = new PlanCompiler.Limits(150_000, 192);

    private static BuildPlan plan(String prompt, int maxExtent) throws PlanException {
        Optional<String> json = QuickOrders.match(prompt, NAMES, maxExtent);
        assertTrue(json.isPresent(), () -> "kein Schnellauftrag: " + prompt);
        return PlanParser.parse(json.get());
    }

    private static Map<Pos, BlockSpec> finalState(CompiledBuild b) {
        Map<Pos, BlockSpec> s = new HashMap<>();
        b.actions().forEach(a -> a.changes().forEach(c -> s.put(c.pos(), c.block())));
        return s;
    }

    @Test
    void fortyStoreyTowerIsOneSkyscraperStep() throws Exception {
        // dev01 2026-10-28: Das LLM baute daraus einen 40 Blöcke hohen Vollzylinder.
        BuildPlan p = plan("bau mir ein 40-stöckiges Hochhaus", 192);
        assertEquals(1, p.steps().size());
        assertEquals(Op.SKYSCRAPER, p.steps().getFirst().op());
        assertEquals(40, p.steps().getFirst().floors());
        CompiledBuild b = new PlanCompiler(new TestCatalog(), "stone", DEFAULTS).compile(p);
        assertTrue(b.max().y() >= 160, "Höhe " + b.max().y());
        assertTrue(b.warnings().isEmpty(), () -> "Warnungen: " + b.warnings());
    }

    @Test
    void materialGoesToFacade() throws Exception {
        BuildPlan p = plan("30-stöckiges Hochhaus aus Quarz", 192);
        BuildPlan.Step s = p.steps().getFirst();
        assertEquals("quartz_block", s.block());
        assertEquals(QuickOrders.DEFAULT_WINDOW, s.windowBlock());
        BlockCatalog withQuartz = id -> TestCatalog.KNOWN.contains(id) || id.equals("quartz_block");
        Map<Pos, BlockSpec> state = finalState(new PlanCompiler(withQuartz, "stone", DEFAULTS).compile(p));
        // Eckpfeiler und Dach in Quarz, Fenster dazwischen im Standardglas
        assertEquals("quartz_block", state.get(new Pos(0, 6, 0)).id());
        assertEquals("quartz_block", state.get(new Pos(5, 30 * PlanCompiler.FLOOR_HEIGHT, 5)).id());
        assertEquals("light_blue_stained_glass", state.get(new Pos(2, 6, 0)).id());
    }

    @Test
    void glassGoesToWindowsAndSizeIsRead() throws Exception {
        BuildPlan.Step s = plan("Wolkenkratzer 20x24 mit 12 Etagen aus Glas", 192).steps().getFirst();
        assertEquals("glass", s.windowBlock());
        assertEquals(QuickOrders.DEFAULT_FACADE, s.block());
        assertEquals(new Pos(19, 0, 23), s.to());
        assertEquals(12, s.floors());
    }

    @Test
    void floorsAsNumberWordGoThroughTheQuickPath() throws Exception {
        // Vorher blieb „zwanzig“ als Nicht-Füllwort stehen, und der Auftrag ging für Minuten ans LLM.
        BuildPlan.Step s = plan("bau mir ein Hochhaus mit zwanzig Stockwerken", 192).steps().getFirst();
        assertEquals(Op.SKYSCRAPER, s.op());
        assertEquals(20, s.floors());
    }

    @Test
    void sizeWithTimesSignOrMalIsRead() throws Exception {
        // Vorher wurde „20×24“ zu „20 24“ und „20 mal 24“ blieb stehen – beides ging ans LLM.
        for (String prompt : new String[] {"Hochhaus mit 20 Stockwerken 20 mal 24",
                "Hochhaus mit 20 Stockwerken 20×24", "Hochhaus mit 20 Stockwerken 20x24"}) {
            BuildPlan.Step s = plan(prompt, 192).steps().getFirst();
            assertEquals(new Pos(0, 0, 0), s.from(), prompt);
            assertEquals(new Pos(19, 0, 23), s.to(), prompt);
        }
        // „mal“ ohne Zahlen bleibt Füllwort, die Grundfläche bleibt die Vorgabe
        BuildPlan.Step plain = plan("bau mal ein Hochhaus mit 20 Stockwerken", 192).steps().getFirst();
        assertEquals(20, plain.floors());
        assertEquals(new Pos(15, 0, 15), plain.to());
    }

    @Test
    void severalTowersKeepTheirFloorsAndStandInARow() throws Exception {
        // Gedächtnis „Offen“ (2026-11-01): „3 mal 20 Stockwerke“ wurde zur Grundfläche 3×20, die Geschosszahl fiel weg,
        // und der Auftrag ging ans LLM.
        for (String prompt : new String[] {"3 mal 20 Stockwerke", "bau mir 3 Hochhäuser mit 20 Stockwerken",
                "drei Wolkenkratzer mit zwanzig Etagen", "bitte 3 mal ein Hochhaus mit 20 Stockwerken"}) {
            BuildPlan p = plan(prompt, 192);
            assertEquals(3, p.steps().size(), prompt);
            for (int i = 0; i < 3; i++) {
                BuildPlan.Step s = p.steps().get(i);
                assertEquals(Op.SKYSCRAPER, s.op(), prompt);
                assertEquals(20, s.floors(), prompt);
                assertEquals(new Pos(i * (16 + QuickOrders.TOWER_GAP), 0, 0), s.from(), prompt);
                assertEquals(new Pos(i * (16 + QuickOrders.TOWER_GAP) + 15, 0, 15), s.to(), prompt);
            }
            assertTrue(p.reply().startsWith("3 Hochhäuser mit je 20 Stockwerken"), p.reply());
        }
        // Durch den echten Compiler, mit den Config-Grenzen: kein „Bauwerk zu groß“, Türme getrennt.
        CompiledBuild b = new PlanCompiler(new TestCatalog(), "stone", DEFAULTS).compile(plan("3 mal 20 Stockwerke",
                192));
        assertTrue(b.max().x() - b.min().x() + 1 <= 192);
        assertTrue(b.warnings().isEmpty(), () -> "Warnungen: " + b.warnings());
        // Grundfläche bleibt lesbar: „20 mal 24“ ohne Geschosswort dahinter ist weiter ein Maß.
        BuildPlan sized = plan("2 Hochhäuser 20 mal 24 mit 10 Etagen", 192);
        assertEquals(2, sized.steps().size());
        assertEquals(new Pos(19, 0, 23), sized.steps().getFirst().to());
    }

    @Test
    void tooManyTowersShrinkToWhatFitsAndSaySo() throws Exception {
        // max-ausdehnung 48: 16 + 4 + 16 = 36 passt, ein dritter Turm (56) nicht.
        BuildPlan p = plan("3 mal 5 Stockwerke", 48);
        assertEquals(2, p.steps().size());
        assertTrue(p.reply().contains("mehr als 2 passen nicht"), p.reply());
        // Mehr als MAX_TOWERS oder zwei Anzahlen: unklar, das LLM fragt bzw. plant.
        assertEquals(Optional.empty(), QuickOrders.match("20 mal 5 Stockwerke", NAMES, 192));
        assertEquals(Optional.empty(), QuickOrders.match("2 mal 3 Hochhäuser mit 5 Etagen", NAMES, 192));
    }

    @Test
    void machinesByFullModuleName() throws Exception {
        BuildPlan.Step xor = plan("XOR-Gatter", 48).steps().getFirst();
        assertEquals(Op.MODULE, xor.op());
        assertEquals("xor_gatter", xor.name());
        assertEquals("kolbentuer", plan("eine Kolbentür", 48).steps().getFirst().name()); // umlaut-ok: Modulname
        assertEquals("abwaerts_fahrstuhl", // umlaut-ok: Modulname
                plan("bitte bau mir einen Abwärts-Fahrstuhl", 48).steps().getFirst().name());
        // Kompiliert mit der echten Bibliothek; unbekannte Redstone-Blöcke im Testkatalog sind hier egal.
        CompiledBuild b = new PlanCompiler(new TestCatalog(), "stone", new PlanCompiler.Limits(6000, 48), MACHINES)
                .compile(plan("bau ein XOR-Gatter", 48));
        assertTrue(b.blockCount() > 5);
    }

    @Test
    void everythingElseStaysWithTheLlm() {
        for (String prompt : new String[] {"bau ein Haus mit Klingel", "eine Kirche mit Glocke",
                "3-stöckiges Haus aus Fichtenholz", "Hochhaus mit 20 Stockwerken und einem Park davor", "ein Turm",
                "eine Brücke mit 3 Bögen", "bau ein Hochhaus", "20 Stockwerke", "XOR-Gatter und Kolbentür",
                "Kolbentür aus Stein", "Hochhaus mit 20 Stockwerken aus Pudding", "Hochhaus mit 20 Stockwerken "
                + "und XOR-Gatter", "Hochhaus mit 10 Stockwerken und 20 Etagen", ""}) {
            assertEquals(Optional.empty(), QuickOrders.match(prompt, NAMES, 192), prompt);
        }
    }

    @Test
    void floorsAreCappedToTheParserAndTheExtent() throws Exception {
        assertEquals(PlanParser.MAX_FLOORS, plan("Hochhaus mit 99 Stockwerken", 192).steps().getFirst().floors());
        // max-ausdehnung 48: (48 − 6) / 4 = 10 Geschosse, Grundfläche bleibt 16×16.
        BuildPlan small = plan("Hochhaus mit 30 Stockwerken", 48);
        assertEquals(10, small.steps().getFirst().floors());
        new PlanCompiler(new TestCatalog(), "stone", new PlanCompiler.Limits(150_000, 48)).compile(small);
        // 40 Geschosse bei 192: kein „Bauwerk zu groß“
        CompiledBuild b = new PlanCompiler(new TestCatalog(), "stone", DEFAULTS)
                .compile(plan("Hochhaus mit 40 Stockwerken", 192));
        assertTrue(b.max().y() - b.min().y() + 1 <= 192);
        // Grundfläche gekappt auf 5..48
        assertEquals(new Pos(47, 0, 4), plan("Hochhaus 90x2 mit 5 Etagen", 192).steps().getFirst().to());
    }

    /** „Kannst du hier ein Loch graben?“ (dev01-Log): Grube unter Bodenhöhe, ohne Modell, durch den echten Compiler. */
    @Test
    void holeIsDugBelowGroundWithoutTheModel() throws Exception {
        assertTrue(QuickOrders.match("bau mir ein Loch", MACHINES, 192).isPresent(), "Weg des Baumeisters");
        BuildPlan p = PlanParser.parse(QuickOrders.hole("bau mir ein Loch").orElseThrow());
        CompiledBuild b = new PlanCompiler(new TestCatalog(), "stone", DEFAULTS).compile(p);
        Map<Pos, BlockSpec> s = finalState(b);
        assertEquals(27, s.size(), "3 × 3 × 3");
        assertTrue(s.keySet().stream().allMatch(q -> q.y() < 0), "nur unter Bodenhöhe");
        assertTrue(s.values().stream().allMatch(BlockSpec::isAir));
        assertEquals(8, finalState(new PlanCompiler(new TestCatalog(), "stone", DEFAULTS).compile(
                PlanParser.parse(QuickOrders.hole("bau mir ein kleines Loch").orElseThrow()))).size());
        assertTrue(QuickOrders.hole("bau mir ein Loch mit Treppe").isEmpty(), "Unbekanntes Wort → Modell");
        assertEquals(java.util.Optional.of("bau mir ein Loch"),
                com.blackn0va.baumeister.companion.CompanionChat.buildOrder("Kannst du hier ein Loch graben?"));
        assertEquals(java.util.Optional.of("bau mir ein kleines Loch"),
                com.blackn0va.baumeister.companion.CompanionChat.buildOrder("kannst du ein kleines loch machen"));
        assertEquals(java.util.Optional.of("bau mir ein Loch"),
                com.blackn0va.baumeister.companion.CompanionChat.buildOrder("Du sollst ein Loch graben."));
    }

    /** „bau ein funktionierendes Display“ (2026-11-06, ging ans LLM, das nicht lief): die Anzeige aus dem Code. */
    @Test
    void displayIsBuiltWithoutTheModel() {
        for (String p : java.util.List.of("bau ein funktionierendes display", "baue mir eine 7 Segment Anzeige",
                "bau eine Ziffernanzeige", "bau mir ein Display")) {
            Optional<String> plan = QuickOrders.display(p);
            assertTrue(plan.isPresent(), p);
            assertTrue(plan.get().contains("\"name\":\"anzeige\""), plan.get());
        }
        // Derselbe Weg wie ein echter Auftrag (BuildService ruft match mit der Bibliothek).
        assertTrue(QuickOrders.match("bau ein funktionierendes display", MACHINES, 192).orElse("")
                .contains("\"name\":\"anzeige\""));
        for (String p : java.util.List.of("bau ein Display mit Fenstern im Haus", "bau ein haus",
                "bau einen Taschenrechner")) {
            assertTrue(QuickOrders.display(p).isEmpty(), p);
        }
    }
}
